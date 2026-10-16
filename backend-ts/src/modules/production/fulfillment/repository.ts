import { sql, type Kysely, type Transaction } from 'kysely'
import type { EmailModule, RenderedEmail } from '@/modules/email'
import { read, write, type Tables } from '@/platform/db/database'
import type { ShippingCarrier } from './carrier'
import '../schema'

export type JobStatus = 'OPEN' | 'SHIPPED'

/** Who reported a shipment: a signed-in user, or the channel that called back. */
export type ShipActor = { userId: number } | { channel: 'SPOD' }

/** What is known about one package: nothing, a carrier, a number, or both. */
export interface Shipment {
  carrier: ShippingCarrier | null
  trackingNumber: string | null
  /** The carrier name a channel reported verbatim; `null` on a human ship. */
  reportedCarrierName: string | null
}

export type ShipWriteResult = 'shipped' | 'notFound' | 'alreadyShipped' | 'notReady'

/** One snapshotted item line of a job; `position` is its 1-based place in the supplier's share. */
export interface StoredJobItem {
  position: number
  articleName: string
  variantName: string
  supplierArticleNumber: string | null
  quantity: number
}

/** The shipped list is a recent-history view: the 100 most recent rows, no paging. */
const SHIPPED_PAGE_SIZE = 100

export type StoredJob = Awaited<ReturnType<FulfillmentRepository['jobs']>>[number]
export type FulfillmentRepository = ReturnType<typeof createFulfillmentRepository>

/**
 * The job reads of the two fulfillment surfaces and the one ship write. A supplier's scope is a
 * `WHERE` clause, never a filter afterwards, so a foreign job never leaves the database.
 */
export function createFulfillmentRepository(db: Kysely<Tables>, outbox: EmailModule['outbox']) {
  const jobsQuery = (trx: Transaction<Tables>) =>
    trx
      .selectFrom('production_jobs as j')
      .innerJoin('production_requests as r', 'r.id', 'j.request_id')
      .leftJoin('production_spod_orders as o', 'o.production_job_id', 'j.id')
      .select([
        'j.id',
        'r.order_id as orderId',
        'j.supplier_id as supplierId',
        'j.fulfillment_channel as fulfillmentChannel',
        'j.file_name as fileName',
        'j.content_sha256 as contentSha256',
        'j.generated_at as generatedAt',
        'j.prepared_at as preparedAt',
        'j.generation_attempt_count as generationAttemptCount',
        'j.last_generation_error_code as lastGenerationErrorCode',
        'j.shipped_at as shippedAt',
        'j.shipped_by_user_id as shippedByUserId',
        'j.shipped_by_channel as shippedByChannel',
        'j.shipping_carrier as shippingCarrier',
        'j.shipping_carrier_reported as shippingCarrierReported',
        'j.tracking_number as trackingNumber',
        'o.external_reference as externalReference',
        'o.remote_state as remoteState',
      ])

  return {
    /**
     * One list page. `OPEN` is everything not shipped, oldest first and uncapped: a job that is
     * not shown is a job nobody repairs. `SHIPPED` is newest first by `shipped_at DESC, id DESC`
     * (two jobs shipped in one transaction share a timestamp) and capped.
     */
    jobs: (status: JobStatus, supplierId: number | null) =>
      read(db, (trx) => {
        let query = jobsQuery(trx)
        if (supplierId !== null) query = query.where('j.supplier_id', '=', supplierId)
        return status === 'OPEN'
          ? query.where('j.shipped_at', 'is', null).orderBy('j.id').execute()
          : query
              .where('j.shipped_at', 'is not', null)
              .orderBy('j.shipped_at', 'desc')
              .orderBy('j.id', 'desc')
              .limit(SHIPPED_PAGE_SIZE)
              .execute()
      }),

    /** One job, narrowed to the supplier's scope: a foreign job reads like an unknown one. */
    job: (jobId: number, supplierScope: number | null) =>
      read(db, (trx) => {
        let query = jobsQuery(trx).where('j.id', '=', jobId)
        if (supplierScope !== null) query = query.where('j.supplier_id', '=', supplierScope)
        return query.executeTakeFirst()
      }),

    /** The snapshotted lines of every job in `jobIds`, in printing order, in one query. */
    items: async (jobIds: number[]): Promise<Map<number, StoredJobItem[]>> => {
      if (jobIds.length === 0) return new Map()
      const rows = await read(db, (trx) =>
        trx
          .selectFrom('production_job_items')
          .select([
            'production_job_id as jobId',
            'position',
            'article_name as articleName',
            'variant_name as variantName',
            'supplier_article_number as supplierArticleNumber',
            'quantity',
          ])
          .where('production_job_id', 'in', jobIds)
          .orderBy('production_job_id')
          .orderBy('position')
          .execute(),
      )
      const items = new Map<number, StoredJobItem[]>()
      for (const { jobId, ...item } of rows) items.set(jobId, [...(items.get(jobId) ?? []), item])
      return items
    },

    /**
     * Ships one job and enqueues the customer's mail, attributed to `mail.orderId`: one commit, or
     * neither. The `WHERE` clause is the whole rule: the job exists, is not shipped, is prepared (a
     * human ship only), and belongs to the supplier's scope. Two concurrent ships therefore end as
     * one shipment, one `alreadyShipped`, and one mail. A channel ship skips the `prepared_at` guard, because the
     * shipment proves the remote order exists, and sets `prepared_at` if it was missing. When no
     * row was touched, the row is read back in this transaction to say why.
     */
    ship: (
      jobId: number,
      actor: ShipActor,
      supplierScope: number | null,
      shipment: Shipment,
      mail: {
        orderId: number
        render: (items: StoredJobItem[]) => RenderedEmail<'SHIPPING_NOTIFICATION'>
      },
    ): Promise<ShipWriteResult> =>
      write(db, async (trx) => {
        let update = trx
          .updateTable('production_jobs')
          .set({
            shipped_at: sql<Date>`now()`,
            prepared_at: sql<Date>`COALESCE(prepared_at, now())`,
            shipped_by_user_id: 'userId' in actor ? actor.userId : null,
            shipped_by_channel: 'channel' in actor ? actor.channel : null,
            shipping_carrier: shipment.carrier,
            shipping_carrier_reported: shipment.reportedCarrierName,
            tracking_number: shipment.trackingNumber,
          })
          .where('id', '=', jobId)
          .where('shipped_at', 'is', null)
        if ('userId' in actor) update = update.where('prepared_at', 'is not', null)
        if (supplierScope !== null) update = update.where('supplier_id', '=', supplierScope)
        const { numUpdatedRows } = await update.executeTakeFirst()
        if (numUpdatedRows === 0n) {
          let refusal = trx
            .selectFrom('production_jobs')
            .select(['shipped_at', 'prepared_at'])
            .where('id', '=', jobId)
          if (supplierScope !== null) refusal = refusal.where('supplier_id', '=', supplierScope)
          const row = await refusal.executeTakeFirst()
          if (row === undefined) return 'notFound'
          if (row.shipped_at !== null) return 'alreadyShipped'
          if (row.prepared_at === null) return 'notReady'
          throw new Error(`Production job ${String(jobId)} is shippable but was not shipped`)
        }
        const items = await trx
          .selectFrom('production_job_items')
          .select([
            'position',
            'article_name as articleName',
            'variant_name as variantName',
            'supplier_article_number as supplierArticleNumber',
            'quantity',
          ])
          .where('production_job_id', '=', jobId)
          .orderBy('position')
          .execute()
        await outbox.enqueue(trx, mail.render(items), { orderId: mail.orderId })
        return 'shipped'
      }),
  }
}
