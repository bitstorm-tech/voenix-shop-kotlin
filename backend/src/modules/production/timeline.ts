import { sql, type Kysely } from 'kysely'
import { read, type Tables } from '@/platform/db/database'
import { carrierDisplayName, carrierOf, trackingUrl } from './fulfillment/carrier'
import './schema'

/**
 * The shipping progress of one paid order across its production jobs:
 * - `'OPEN'`: a production request exists, not yet split or no job shipped yet;
 * - `'PARTIALLY_SHIPPED'`: at least one job is shipped and at least one is still open;
 * - `'SHIPPED'`: every job of the request is shipped.
 * An order without a production request (unpaid or cancelled) has no shipping status (`null`).
 */
export type OrderShippingStatus = 'OPEN' | 'PARTIALLY_SHIPPED' | 'SHIPPED'

/**
 * One shipped parcel as a customer sees it: when it left, the shop's own display name for the
 * carrier (never raw partner text), the tracking number, and the tracking link built from this
 * shop's own carrier templates (decision J2 of issue #119).
 */
export interface CustomerShipment {
  shippedAt: string
  carrierName: string | null
  trackingNumber: string | null
  trackingUrl: string | null
}

/** Shipping summary and shipped parcels of one order for the customer-facing order endpoints. */
export interface OrderCustomerShipping {
  shippingStatus: OrderShippingStatus
  shipments: CustomerShipment[]
}

/**
 * The production section of an order's timeline, one read transaction over production's own
 * tables: the request (one per order, `null` before the order was paid), and every job with its
 * deliveries and, on a SPOD job, its remote order. Facts with their own timestamp column are
 * answered from it; nothing here repeats an order event.
 */
export function productionOfOrder(db: Kysely<Tables>, orderId: number) {
  return read(db, async (trx) => {
    const request = await trx
      .selectFrom('production_requests')
      .select([
        'id',
        'attempt_count as attemptCount',
        'last_error_code as lastErrorCode',
        'created_at as createdAt',
        'processed_at as processedAt',
      ])
      .where('order_id', '=', orderId)
      .executeTakeFirst()
    if (request === undefined) return { request: null, jobs: [] }
    const jobs = await trx
      .selectFrom('production_jobs')
      .select([
        'id as jobId',
        'supplier_id as supplierId',
        'fulfillment_channel as fulfillmentChannel',
        'generation_attempt_count as generationAttemptCount',
        'last_generation_error_code as lastGenerationErrorCode',
        'generated_at as generatedAt',
        'prepared_at as preparedAt',
        'shipped_at as shippedAt',
        'shipped_by_user_id as shippedByUserId',
        'shipped_by_channel as shippedByChannel',
        'shipping_carrier as shippingCarrier',
        'tracking_number as trackingNumber',
      ])
      .where('request_id', '=', request.id)
      .orderBy('id')
      .execute()
    const jobIds = jobs.map((job) => job.jobId)
    const deliveries =
      jobIds.length === 0
        ? []
        : await trx
            .selectFrom('production_deliveries')
            .select([
              'production_job_id as jobId',
              'id as deliveryId',
              'destination_id as destinationId',
              'attempt_count as attemptCount',
              'last_error_code as lastErrorCode',
              'delivered_at as deliveredAt',
            ])
            .where('production_job_id', 'in', jobIds)
            .orderBy('id')
            .execute()
    const spodOrders =
      jobIds.length === 0
        ? []
        : await trx
            .selectFrom('production_spod_orders')
            .select([
              'production_job_id as jobId',
              'external_reference as externalReference',
              'create_state as createState',
              'create_ambiguous_count as createAmbiguousCount',
              'attempt_count as attemptCount',
              'last_error_code as lastErrorCode',
              'confirmed_at as confirmedAt',
              'remote_state as remoteState',
            ])
            .where('production_job_id', 'in', jobIds)
            .execute()
    const deliveriesOf = new Map<number, Omit<(typeof deliveries)[number], 'jobId'>[]>()
    for (const { jobId, ...delivery } of deliveries) {
      deliveriesOf.set(jobId, [...(deliveriesOf.get(jobId) ?? []), delivery])
    }
    const spodOf = new Map(spodOrders.map(({ jobId, ...spod }) => [jobId, spod]))
    return {
      request,
      jobs: jobs.map((job) => ({
        ...job,
        deliveries: deliveriesOf.get(job.jobId) ?? [],
        spod: spodOf.get(job.jobId) ?? null,
      })),
    }
  })
}

/**
 * The shipping status of every requested order in `orderIds` that has a production request; orders
 * without a request are omitted from the returned map. One batch query for the admin order list.
 */
export async function shippingStatusesOfOrders(
  db: Kysely<Tables>,
  orderIds: readonly number[],
): Promise<Map<number, OrderShippingStatus>> {
  if (orderIds.length === 0) return new Map()
  const rows = await read(db, (trx) =>
    trx
      .selectFrom('production_requests as r')
      .leftJoin('production_jobs as j', 'j.request_id', 'r.id')
      .select([
        'r.order_id as orderId',
        sql<number>`count(j.id)::int`.as('jobCount'),
        sql<number>`count(j.shipped_at)::int`.as('shippedCount'),
      ])
      .where('r.order_id', 'in', [...orderIds])
      .groupBy('r.order_id')
      .execute(),
  )
  const statuses = new Map<number, OrderShippingStatus>()
  for (const row of rows) {
    const status: OrderShippingStatus =
      row.jobCount > 0 && row.shippedCount === row.jobCount
        ? 'SHIPPED'
        : row.shippedCount > 0
          ? 'PARTIALLY_SHIPPED'
          : 'OPEN'
    statuses.set(row.orderId, status)
  }
  return statuses
}

/**
 * The shipping status and shipped parcels of every requested order in `orderIds` that has a
 * production request; orders without a request are omitted from the map. One batch query for the
 * customer-facing order reads; exposes no supplier id, job id, or raw partner carrier text.
 */
export async function customerShippingOfOrders(
  db: Kysely<Tables>,
  orderIds: readonly number[],
): Promise<Map<number, OrderCustomerShipping>> {
  if (orderIds.length === 0) return new Map()
  const rows = await read(db, (trx) =>
    trx
      .selectFrom('production_requests as r')
      .leftJoin('production_jobs as j', 'j.request_id', 'r.id')
      .select([
        'r.order_id as orderId',
        'j.id as jobId',
        'j.shipped_at as shippedAt',
        'j.shipping_carrier as shippingCarrier',
        'j.tracking_number as trackingNumber',
      ])
      .where('r.order_id', 'in', [...orderIds])
      .orderBy('j.shipped_at', 'asc')
      .orderBy('j.id', 'asc')
      .execute(),
  )
  const grouped = new Map<number, { jobCount: number; shipments: CustomerShipment[] }>()
  for (const row of rows) {
    const entry = grouped.get(row.orderId) ?? { jobCount: 0, shipments: [] }
    if (row.jobId !== null) {
      entry.jobCount += 1
      if (row.shippedAt !== null) {
        const carrier = carrierOf(row.shippingCarrier)
        entry.shipments.push({
          shippedAt: row.shippedAt.toISOString(),
          carrierName: carrier === null ? null : carrierDisplayName(carrier),
          trackingNumber: row.trackingNumber,
          trackingUrl: carrier === null ? null : trackingUrl(carrier, row.trackingNumber),
        })
      }
    }
    grouped.set(row.orderId, entry)
  }
  const result = new Map<number, OrderCustomerShipping>()
  for (const [orderId, { jobCount, shipments }] of grouped) {
    const shippingStatus: OrderShippingStatus =
      jobCount > 0 && shipments.length === jobCount
        ? 'SHIPPED'
        : shipments.length > 0
          ? 'PARTIALLY_SHIPPED'
          : 'OPEN'
    result.set(orderId, { shippingStatus, shipments })
  }
  return result
}

/**
 * How many paid orders are not fully shipped: a request whose jobs are not split yet, or one with a
 * job that has no `shipped_at`. "Fully shipped" is derived from the jobs, never stored.
 */
export async function unshippedOrderCount(db: Kysely<Tables>): Promise<number> {
  const row = await read(db, (trx) =>
    trx
      .selectFrom('production_requests as request')
      .select((eb) => eb.fn.countAll<number>().as('count'))
      .where((eb) => {
        const jobs = eb
          .selectFrom('production_jobs as job')
          .select('job.id')
          .whereRef('job.request_id', '=', 'request.id')
        return eb.or([eb.not(eb.exists(jobs)), eb.exists(jobs.where('job.shipped_at', 'is', null))])
      })
      .executeTakeFirstOrThrow(),
  )
  return row.count
}
