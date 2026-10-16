import { renderShippingNotification } from '@/modules/email'
import type { SupplierModule, SupplierSummary } from '@/modules/supplier'
import { createLogger } from '@/platform/logging'
import { invalid, notFound, ok, type OperationResult } from '@/platform/operation'
import type { FulfillmentOrder, ProductionOrders } from '../orders'
import type { ArtifactStore } from '../pdf/artifact-store'
import type { SpodOrderRepository, SpodRelease } from '../spod/repository'
import { parseSpodOrderReference, spodOrderReference } from '../spod/submitter'
import { carrierDisplayName, trackingUrl } from './carrier'
import type {
  FulfillmentRepository,
  JobStatus,
  ShipActor,
  Shipment,
  ShipWriteResult,
  StoredJob,
  StoredJobItem,
} from './repository'
import type { SpodWebhookEvent } from './webhook'

/** One packing line: what to pack and how many, never a price. */
export interface FulfillmentItemView {
  articleName: string
  variantName: string
  supplierArticleNumber: string | null
  quantity: number
}

/**
 * What a supplier sees of one of its jobs. The type is the data minimization: no e-mail address,
 * phone, price, total, or token can appear because there is no field for one.
 */
export interface SupplierJobView {
  jobId: number
  orderId: number
  orderDate: string
  customerFirstName: string
  customerLastName: string
  shippingStreet: string
  shippingHouseNumber: string
  shippingPostalCode: string
  shippingCity: string
  shippingCountry: string
  items: FulfillmentItemView[]
  pdfAvailable: boolean
  shippedAt: string | null
  shippingCarrier: string | null
  trackingNumber: string | null
}

/** Everything a supplier sees plus what an operator needs: whose job, which channel, what state. */
export interface AdminJobView {
  jobId: number
  orderId: number
  orderDate: string
  supplier: { id: number; name: string | null }
  customerFirstName: string
  customerLastName: string
  shippingStreet: string
  shippingHouseNumber: string
  shippingPostalCode: string
  shippingCity: string
  shippingCountry: string
  items: FulfillmentItemView[]
  fulfillmentChannel: string
  pdfAvailable: boolean
  generationAttemptCount: number
  lastGenerationErrorCode: string | null
  externalReference: string | null
  remoteState: string | null
  /** The SPOD creation state (`PENDING`, `CREATED`, `OUTCOME_UNKNOWN`); `null` before the first attempt and for an SFTP job. */
  createState: string | null
  shippedAt: string | null
  shippedByUserId: number | null
  shippedByChannel: string | null
  shippingCarrier: string | null
  shippingCarrierReported: string | null
  trackingNumber: string | null
}

export type ArtifactResult =
  | { kind: 'loaded'; fileName: string; bytes: Uint8Array<ArrayBuffer> }
  | { kind: 'notFound' | 'notGenerated' | 'missing' | 'digestMismatch' }

export type ShipResult<V> =
  | { kind: 'shipped'; job: V }
  | { kind: Exclude<ShipWriteResult, 'shipped'> }

export type FulfillmentService = ReturnType<typeof createFulfillmentService>

const logger = createLogger('production-fulfillment')

/**
 * The two job surfaces and the one ship path every reporter goes through: a supplier (scoped to
 * itself), an admin (no scope), and the partner's webhook (a channel instead of a user). A page
 * loads its jobs once and resolves every order header with one order-module call and every item
 * line with one query; the admin list adds one supplier-name call.
 */
export function createFulfillmentService({
  repository,
  orders,
  suppliers,
  artifacts,
  spodOrders,
}: {
  repository: FulfillmentRepository
  orders: Pick<ProductionOrders, 'fulfillmentOrders' | 'shippingNotificationOrder'>
  suppliers: Pick<SupplierModule, 'find'>
  artifacts: ArtifactStore
  spodOrders: SpodOrderRepository
}) {
  /** Every view of `jobs` with its header and lines; a job without a header is logged and dropped. */
  const listed = async <V>(
    jobs: StoredJob[],
    view: (job: StoredJob, header: FulfillmentOrder, items: FulfillmentItemView[]) => V,
  ): Promise<V[]> => {
    if (jobs.length === 0) return []
    const headers = await orders.fulfillmentOrders(new Set(jobs.map((job) => job.orderId)))
    const items = await repository.items(jobs.map((job) => job.id))
    return jobs.flatMap((job) => {
      const header = headers.get(job.orderId)
      if (header === undefined) {
        logger.error({ jobId: job.id }, 'A production job names an order that no longer exists')
        return []
      }
      return [view(job, header, (items.get(job.id) ?? []).map(itemView))]
    })
  }

  /**
   * The one ship path. The customer's mail is rendered inside the ship transaction from the job's
   * own snapshot and the order module's answer, which is read first: a job whose order is gone is a
   * broken reference and fails loudly instead of shipping without a mail.
   */
  const ship = async (
    jobId: number,
    actor: ShipActor,
    supplierScope: number | null,
    shipment: Shipment,
  ): Promise<ShipWriteResult> => {
    const job = await repository.job(jobId, supplierScope)
    if (job === undefined) return 'notFound'
    const order = await orders.shippingNotificationOrder(job.orderId)
    if (order === null) {
      throw new Error(`Production job ${String(jobId)} names an order that does not exist`)
    }
    const carrier = shipment.carrier
    return repository.ship(jobId, actor, supplierScope, shipment, {
      orderId: job.orderId,
      render: (items) =>
        renderShippingNotification({
          recipient: order.recipientEmail,
          orderId: job.orderId,
          customerFirstName: order.customerFirstName,
          items: items.map(({ articleName, variantName, quantity }) => ({
            articleName,
            variantName,
            quantity,
          })),
          orderUrl: order.orderUrl,
          carrierName: carrier === null ? null : carrierDisplayName(carrier),
          trackingNumber: shipment.trackingNumber,
          trackingUrl: carrier === null ? null : trackingUrl(carrier, shipment.trackingNumber),
        }),
    })
  }

  /** The single answer of a committed ship: a missing header is a broken reference, not a drop. */
  const shipped = async <V>(
    result: ShipWriteResult,
    jobId: number,
    supplierScope: number | null,
    render: (jobs: StoredJob[]) => Promise<V[]>,
  ): Promise<ShipResult<V>> => {
    if (result !== 'shipped') return { kind: result }
    const job = await repository.job(jobId, supplierScope)
    const [view] = job === undefined ? [] : await render([job])
    if (view === undefined) throw new Error(`Shipped production job ${String(jobId)} has no view`)
    return { kind: 'shipped', job: view }
  }

  const adminViews = async (jobs: StoredJob[]) => {
    const names =
      jobs.length === 0
        ? new Map<number, SupplierSummary>()
        : await suppliers.find(new Set(jobs.map((job) => job.supplierId)))
    return listed(jobs, (job, header, items) =>
      adminJobView(job, header, items, names.get(job.supplierId)?.name ?? null),
    )
  }
  const supplierViews = (jobs: StoredJob[]) => listed(jobs, supplierJobView)

  /**
   * The job a webhook event is about: the partner's order id first, then this shop's own
   * reference, checked against the order and the channel of the job it claims.
   */
  const resolveWebhookJob = async (event: SpodWebhookEvent): Promise<number | undefined> => {
    if (event.externalReference !== null) {
      const jobId = await spodOrders.jobIdOfExternalReference(event.externalReference)
      if (jobId !== undefined) return jobId
    }
    const claimed =
      event.shopReference === null ? null : parseSpodOrderReference(event.shopReference)
    if (claimed === null) return undefined
    const job = await repository.job(claimed.jobId, null)
    return job?.orderId === claimed.orderId && job.fulfillmentChannel === 'SPOD'
      ? job.id
      : undefined
  }

  return {
    identity: async (supplierId: number) => {
      const supplier = (await suppliers.find([supplierId])).get(supplierId)
      if (supplier === undefined) {
        throw new Error(`Supplier ${String(supplierId)} has a login but no supplier row`)
      }
      return { supplierId: supplier.id, supplierName: supplier.name }
    },

    supplierJobs: async (supplierId: number, status: JobStatus) =>
      supplierViews(await repository.jobs(status, supplierId)),

    adminJobs: async (status: JobStatus, supplierId: number | null) =>
      adminViews(await repository.jobs(status, supplierId)),

    /**
     * The stored PDF, verified against its digest. A SPOD job has no document at all and answers
     * like an unknown id; the three conflicts are a document that is late, gone, or wrong.
     */
    artifact: async (jobId: number, supplierScope: number | null): Promise<ArtifactResult> => {
      const job = await repository.job(jobId, supplierScope)
      if (job === undefined || job.fulfillmentChannel !== 'SFTP') return { kind: 'notFound' }
      if (job.contentSha256 === null) return { kind: 'notGenerated' }
      const loaded = await artifacts.load(job.id, job.fileName, job.contentSha256)
      if (loaded.kind === 'loaded') {
        return { kind: 'loaded', fileName: job.fileName, bytes: loaded.bytes }
      }
      logger.error({ jobId, kind: loaded.kind }, 'A generated production artifact is unavailable')
      return { kind: loaded.kind }
    },

    shipAsSupplier: async (
      jobId: number,
      supplierId: number,
      actorUserId: number,
      shipment: Shipment,
    ) =>
      shipped(
        await ship(jobId, { userId: actorUserId }, supplierId, shipment),
        jobId,
        supplierId,
        supplierViews,
      ),

    shipAsAdmin: async (jobId: number, actorUserId: number, shipment: Shipment) =>
      shipped(await ship(jobId, { userId: actorUserId }, null, shipment), jobId, null, adminViews),

    /**
     * An admin's release of a quarantined SPOD job. `confirmation` must be the job's
     * `ORD-{orderId}-JOB-{jobId}` reference, typed by hand, or the answer is `invalid`; the write
     * itself refuses a job not in `OUTCOME_UNKNOWN` and an order id another job holds. Answers the
     * released job's admin view.
     */
    releaseSpodJob: async (
      jobId: number,
      release: SpodRelease & { confirmation: string },
      adminUserId: number,
    ): Promise<OperationResult<AdminJobView>> => {
      const job = await repository.job(jobId, null)
      if (job === undefined) return notFound
      if (release.confirmation !== spodOrderReference(job.orderId, jobId)) {
        return invalid({ confirmation: ['Confirmation must be the job reference'] })
      }
      const released = await spodOrders.release(jobId, release, adminUserId)
      if (released.kind !== 'ok') return released
      const releasedJob = await repository.job(jobId, null)
      const [view] = releasedJob === undefined ? [] : await adminViews([releasedJob])
      if (view === undefined)
        throw new Error(`Released production job ${String(jobId)} has no view`)
      return ok(view)
    },

    /**
     * One event the partner reported, applied to the job it names. Every outcome is silent: the
     * partner hears `202` either way. A shipment goes through the same guarded ship transaction a
     * human uses; a refused one is logged at error level, since nothing will redeliver it.
     */
    handleWebhook: async (event: SpodWebhookEvent): Promise<void> => {
      const jobId = await resolveWebhookJob(event)
      if (jobId === undefined) {
        logger.warn('A SPOD webhook event named no production job of this shop')
        return
      }
      if (event.kind === 'remoteState') {
        await spodOrders.recordRemoteState(jobId, event.state)
        return
      }
      const result = await ship(jobId, { channel: 'SPOD' }, null, event.shipment)
      if (result === 'shipped' || result === 'alreadyShipped') {
        logger.info({ jobId, result }, 'SPOD reported a shipment')
      } else {
        logger.error({ jobId, result }, 'SPOD reported a shipment this shop could not store')
      }
    },
  }
}

const itemView = (item: StoredJobItem): FulfillmentItemView => ({
  articleName: item.articleName,
  variantName: item.variantName,
  supplierArticleNumber: item.supplierArticleNumber,
  quantity: item.quantity,
})

const iso = (date: Date | null) => (date === null ? null : date.toISOString())

function supplierJobView(
  job: StoredJob,
  header: FulfillmentOrder,
  items: FulfillmentItemView[],
): SupplierJobView {
  // Field by field, never a spread: the order module's object must not widen a supplier answer.
  return {
    jobId: job.id,
    orderId: header.orderId,
    orderDate: header.orderDate,
    customerFirstName: header.customerFirstName,
    customerLastName: header.customerLastName,
    shippingStreet: header.shippingStreet,
    shippingHouseNumber: header.shippingHouseNumber,
    shippingPostalCode: header.shippingPostalCode,
    shippingCity: header.shippingCity,
    shippingCountry: header.shippingCountry,
    items,
    pdfAvailable: job.generatedAt !== null,
    shippedAt: iso(job.shippedAt),
    shippingCarrier: job.shippingCarrier,
    trackingNumber: job.trackingNumber,
  }
}

function adminJobView(
  job: StoredJob,
  header: FulfillmentOrder,
  items: FulfillmentItemView[],
  supplierName: string | null,
): AdminJobView {
  return {
    jobId: job.id,
    orderId: header.orderId,
    orderDate: header.orderDate,
    supplier: { id: job.supplierId, name: supplierName },
    customerFirstName: header.customerFirstName,
    customerLastName: header.customerLastName,
    shippingStreet: header.shippingStreet,
    shippingHouseNumber: header.shippingHouseNumber,
    shippingPostalCode: header.shippingPostalCode,
    shippingCity: header.shippingCity,
    shippingCountry: header.shippingCountry,
    items,
    fulfillmentChannel: job.fulfillmentChannel,
    pdfAvailable: job.generatedAt !== null,
    generationAttemptCount: job.generationAttemptCount,
    lastGenerationErrorCode: job.lastGenerationErrorCode,
    externalReference: job.externalReference,
    remoteState: job.remoteState,
    createState: job.createState,
    shippedAt: iso(job.shippedAt),
    shippedByUserId: job.shippedByUserId,
    shippedByChannel: job.shippedByChannel,
    shippingCarrier: job.shippingCarrier,
    shippingCarrierReported: job.shippingCarrierReported,
    trackingNumber: job.trackingNumber,
  }
}
