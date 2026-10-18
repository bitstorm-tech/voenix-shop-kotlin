import type { Kysely, NotNull } from 'kysely'
import { read, type Tables } from '@/platform/db/database'
import './schema'

/**
 * A row of the pipeline that stands still: a request that does not split, an SFTP job without its
 * document, a delivery that does not arrive, a SPOD job that is not submitted, quarantined, or
 * flagged by the partner, and a prepared job nobody ships. The operations worker records them.
 */
export interface ProductionFinding {
  kind:
    | 'PRODUCTION_REQUEST_STALLED'
    | 'JOB_GENERATION_STALLED'
    | 'DELIVERY_STALLED'
    | 'SPOD_SUBMISSION_STALLED'
    | 'SPOD_QUARANTINED'
    | 'SPOD_REMOTE_ATTENTION'
    | 'SHIPMENT_OVERDUE'
  subject: { type: 'REQUEST' | 'JOB' | 'DELIVERY'; id: number }
  orderId: number
  since: Date
  attemptCount: number | null
  lastErrorCode: string | null
}

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR
/** A row whose stage failed this often is stalled, however young it is. */
const STALLED_ATTEMPTS = 3

/**
 * The production module's findings at `now`, one read transaction over its own tables. The SQL
 * narrows to the open rows (an unprocessed request, an unshipped or quarantined job, an undelivered
 * delivery of a generated job); the thresholds below decide which of them stand still, in the words
 * of the kinds table in the Operations package guide.
 */
export function productionFindings(db: Kysely<Tables>, now: Date): Promise<ProductionFinding[]> {
  const olderThan = (at: Date, millis: number) => at.getTime() < now.getTime() - millis
  return read(db, async (trx) => {
    const findings: ProductionFinding[] = []

    const requests = await trx
      .selectFrom('production_requests')
      .select(['id', 'order_id', 'attempt_count', 'last_error_code', 'created_at'])
      .where('processed_at', 'is', null)
      .orderBy('id')
      .execute()
    for (const request of requests) {
      if (olderThan(request.created_at, 15 * MINUTE) || request.attempt_count >= STALLED_ATTEMPTS) {
        findings.push({
          kind: 'PRODUCTION_REQUEST_STALLED',
          subject: { type: 'REQUEST', id: request.id },
          orderId: request.order_id,
          since: request.created_at,
          attemptCount: request.attempt_count,
          lastErrorCode: request.last_error_code,
        })
      }
    }

    const jobs = await trx
      .selectFrom('production_jobs as j')
      .innerJoin('production_requests as r', 'r.id', 'j.request_id')
      .leftJoin('production_spod_orders as o', 'o.production_job_id', 'j.id')
      .select([
        'j.id',
        'r.order_id',
        'j.fulfillment_channel',
        'j.generation_attempt_count',
        'j.last_generation_error_code',
        'j.created_at',
        'j.generated_at',
        'j.prepared_at',
        'j.shipped_at',
        'o.create_state',
        'o.remote_state',
        'o.attempt_count as spod_attempt_count',
        'o.last_error_code as spod_error_code',
        'o.created_at as spod_created_at',
      ])
      .where((eb) =>
        eb.or([eb('j.shipped_at', 'is', null), eb('o.create_state', '=', 'OUTCOME_UNKNOWN')]),
      )
      .orderBy('j.id')
      .execute()
    for (const job of jobs) {
      const jobFinding = (
        kind: ProductionFinding['kind'],
        since: Date,
        attemptCount: number | null,
        lastErrorCode: string | null,
      ) => {
        findings.push({
          kind,
          subject: { type: 'JOB', id: job.id },
          orderId: job.order_id,
          since,
          attemptCount,
          lastErrorCode,
        })
      }
      // A SPOD job's attempts are its remote order's; the counter is 0 before the first attempt.
      const spodAttempts = job.spod_attempt_count ?? 0
      if (
        job.fulfillment_channel === 'SFTP' &&
        job.generated_at === null &&
        (olderThan(job.created_at, 15 * MINUTE) || job.generation_attempt_count >= STALLED_ATTEMPTS)
      ) {
        jobFinding(
          'JOB_GENERATION_STALLED',
          job.created_at,
          job.generation_attempt_count,
          job.last_generation_error_code,
        )
      }
      if (
        job.fulfillment_channel === 'SPOD' &&
        job.prepared_at === null &&
        job.create_state !== 'OUTCOME_UNKNOWN' &&
        (olderThan(job.created_at, HOUR) || spodAttempts >= STALLED_ATTEMPTS)
      ) {
        jobFinding('SPOD_SUBMISSION_STALLED', job.created_at, spodAttempts, job.spod_error_code)
      }
      if (job.create_state === 'OUTCOME_UNKNOWN') {
        jobFinding('SPOD_QUARANTINED', job.created_at, spodAttempts, job.spod_error_code)
      }
      if (
        job.spod_created_at !== null &&
        (job.remote_state === 'NEEDS_ACTION' || job.remote_state === 'CANCELLED') &&
        job.shipped_at === null
      ) {
        // The state change has no timestamp of its own; the remote order's row is the oldest fact.
        jobFinding('SPOD_REMOTE_ATTENTION', job.spod_created_at, null, null)
      }
      if (
        job.prepared_at !== null &&
        olderThan(job.prepared_at, 7 * DAY) &&
        job.shipped_at === null
      ) {
        jobFinding('SHIPMENT_OVERDUE', job.prepared_at, null, null)
      }
    }

    // A delivery cannot start before its job's document exists, so only deliveries of generated
    // jobs are looked at: a stalled generation is one finding, not one per destination.
    const deliveries = await trx
      .selectFrom('production_deliveries as v')
      .innerJoin('production_jobs as j', 'j.id', 'v.production_job_id')
      .innerJoin('production_requests as r', 'r.id', 'j.request_id')
      .select(['v.id', 'r.order_id', 'v.attempt_count', 'v.last_error_code', 'j.generated_at'])
      .where('v.delivered_at', 'is', null)
      .where('j.generated_at', 'is not', null)
      .$narrowType<{ generated_at: NotNull }>()
      .orderBy('v.id')
      .execute()
    for (const delivery of deliveries) {
      if (olderThan(delivery.generated_at, HOUR) || delivery.attempt_count >= STALLED_ATTEMPTS) {
        findings.push({
          kind: 'DELIVERY_STALLED',
          subject: { type: 'DELIVERY', id: delivery.id },
          orderId: delivery.order_id,
          since: delivery.generated_at,
          attemptCount: delivery.attempt_count,
          lastErrorCode: delivery.last_error_code,
        })
      }
    }
    return findings
  })
}
