import { sql, type Kysely, type Transaction, type UpdateObject } from 'kysely'
import { read, write, type Tables } from '@/platform/db/database'
import { pdfFileName, type ProductionItem } from '../orders'
import '../schema'

/**
 * Asks for production of a paid order inside the caller's transaction: one cheap row, no order
 * read, no routing. If the caller rolls back, no request exists. The unique `order_id` makes the
 * call idempotent: a repeated or concurrent call answers the same request id.
 */
export async function requestProduction(
  trx: Transaction<Tables>,
  orderId: number,
): Promise<number> {
  await trx
    .insertInto('production_requests')
    .values({ order_id: orderId })
    .onConflict((conflict) => conflict.column('order_id').doNothing())
    .execute()
  const { id } = await trx
    .selectFrom('production_requests')
    .select('id')
    .where('order_id', '=', orderId)
    .executeTakeFirstOrThrow()
  return id
}

export type RequestRepository = ReturnType<typeof createRequestRepository>

/**
 * The open production requests and their transactional split. Open/processed derives from the
 * nullable `processed_at`; there is no in-progress state to strand.
 */
export function createRequestRepository(db: Kysely<Tables>) {
  const updateOpen = async (
    requestId: number,
    values: UpdateObject<Tables, 'production_requests'>,
  ) => {
    const result = await db
      .updateTable('production_requests')
      .set(values)
      .where('id', '=', requestId)
      .where('processed_at', 'is', null)
      .executeTakeFirst()
    return result.numUpdatedRows > 0n
  }

  return {
    openRequests: () =>
      read(db, (trx) =>
        trx
          .selectFrom('production_requests')
          .select(['id', 'order_id as orderId', 'attempt_count as attemptCount'])
          .where('processed_at', 'is', null)
          .orderBy('id')
          .execute(),
      ),

    startAttempt: (requestId: number) =>
      updateOpen(requestId, { attempt_count: sql<number>`attempt_count + 1` }),

    recordFailure: (requestId: number, code: string) =>
      updateOpen(requestId, { last_error_code: code }),

    /**
     * Creates one job per supplier with its item snapshot, one delivery per enabled destination of
     * every SFTP job, and marks the request processed: one transaction, all or nothing.
     *
     * The supplier's enabled destinations decide the job's channel (an enabled SPOD destination
     * makes it a `SPOD` job, everything else `SFTP`) and its deliveries, and both are frozen with
     * the job. A SPOD job gets no deliveries at all; an SFTP supplier without an enabled
     * destination still gets its job and is answered back so the caller can log it. Every insert
     * ignores a duplicate on its identity, so a repeated split heals instead of conflicting.
     */
    completeSplit: (
      requestId: number,
      orderId: number,
      itemsBySupplier: Map<number, ProductionItem[]>,
    ): Promise<number[]> =>
      write(db, async (trx) => {
        const supplierIds = [...itemsBySupplier.keys()]
        const destinations = await trx
          .selectFrom('production_destinations')
          .select(['id', 'supplier_id', 'channel'])
          .where('supplier_id', 'in', supplierIds)
          .where('enabled', '=', true)
          .orderBy('id')
          .execute()
        const destinationsOf = (supplierId: number) =>
          destinations.filter((destination) => destination.supplier_id === supplierId)
        const channelOf = (supplierId: number) =>
          destinationsOf(supplierId).some((destination) => destination.channel === 'SPOD')
            ? 'SPOD'
            : 'SFTP'

        await trx
          .insertInto('production_jobs')
          .values(
            supplierIds.map((supplierId) => ({
              request_id: requestId,
              supplier_id: supplierId,
              fulfillment_channel: channelOf(supplierId),
              file_name: pdfFileName(orderId),
            })),
          )
          .onConflict((conflict) => conflict.columns(['request_id', 'supplier_id']).doNothing())
          .execute()
        const jobs = await trx
          .selectFrom('production_jobs')
          .select(['id', 'supplier_id'])
          .where('request_id', '=', requestId)
          .execute()
        const jobIdOf = new Map(jobs.map((job) => [job.supplier_id, job.id]))
        const jobId = (supplierId: number) => {
          const id = jobIdOf.get(supplierId)
          if (id === undefined) throw new Error('A job of the split vanished in its transaction')
          return id
        }

        await trx
          .insertInto('production_job_items')
          .values(
            [...itemsBySupplier].flatMap(([supplierId, items]) =>
              items.map((item, index) => ({
                production_job_id: jobId(supplierId),
                position: index + 1,
                article_name: item.articleName,
                variant_name: item.variantName,
                supplier_article_number:
                  item.supplierArticleNumber?.trim() === '' ? null : item.supplierArticleNumber,
                quantity: item.quantity,
              })),
            ),
          )
          .onConflict((conflict) => conflict.columns(['production_job_id', 'position']).doNothing())
          .execute()

        const pushed = supplierIds.filter((supplierId) => channelOf(supplierId) === 'SFTP')
        const deliveries = pushed.flatMap((supplierId) =>
          destinationsOf(supplierId).map((destination) => ({
            production_job_id: jobId(supplierId),
            destination_id: destination.id,
          })),
        )
        if (deliveries.length > 0) {
          await trx
            .insertInto('production_deliveries')
            .values(deliveries)
            .onConflict((conflict) =>
              conflict.columns(['production_job_id', 'destination_id']).doNothing(),
            )
            .execute()
        }
        await trx
          .updateTable('production_requests')
          .set({ processed_at: sql<Date>`now()`, last_error_code: null })
          .where('id', '=', requestId)
          .where('processed_at', 'is', null)
          .execute()
        return pushed.filter((supplierId) => destinationsOf(supplierId).length === 0)
      }),
  }
}
