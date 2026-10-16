import type { Kysely } from 'kysely'
import { read, type Tables } from '@/platform/db/database'
import './schema'

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
