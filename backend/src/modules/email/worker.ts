import { sql, type Kysely, type UpdateObject } from 'kysely'
import { setTimeout as sleep } from 'node:timers/promises'
import { read, type Tables } from '@/platform/db/database'
import { createLogger, errorDetails } from '@/platform/logging'
import type { Settings } from '@/platform/settings'
import type { EmailDelivery } from './delivery/sweego'
import './schema'

const logger = createLogger('email-worker')

/** Maximum delivery attempts before a transiently failing mail is marked permanently failed. */
export const MAX_EMAIL_ATTEMPTS = 10

const MINUTE_MILLIS = 60_000
const MAX_BACKOFF_MINUTES = 60

/**
 * Whether a provider error code is permanent: any HTTP `4xx` except `408` (Request Timeout) and
 * `429` (Too Many Requests). Retrying a permanent refusal (such as `PROVIDER_HTTP_400` for invalid
 * recipient syntax) will never succeed.
 */
export function isPermanentEmailError(code: string): boolean {
  const match = /^PROVIDER_HTTP_(4\d\d)$/.exec(code)
  if (match?.[1] === undefined) return false
  const status = Number(match[1])
  return status !== 408 && status !== 429
}

/** Exponential backoff in milliseconds for a failed transient attempt (`1m, 2m, 4m, …, 60m`). */
export function retryDelayMillis(attempt: number): number {
  const minutes = Math.min(MAX_BACKOFF_MINUTES, 2 ** Math.max(0, attempt - 1))
  return minutes * MINUTE_MILLIS
}

/**
 * The outbox worker: every `pollIntervalMinutes` it sends every due open job, oldest first. Each
 * attempt counts in `attempt_count`. A transient failure stores its safe code and schedules
 * exponential backoff in `next_attempt_at` up to `MAX_EMAIL_ATTEMPTS`; a permanent `4xx` refusal
 * (other than `408`/`429`) or reaching `MAX_EMAIL_ATTEMPTS` marks the job `failed_permanently_at`
 * so it stops hitting Sweego. Disabled email leaves every job untouched. Delivery is at least
 * once: a crash between Sweego's acceptance and the `sent_at` update sends the job again on the
 * next start.
 */
export function createEmailWorker({
  db,
  settings,
  delivery,
}: {
  db: Kysely<Tables>
  settings: Settings['email']
  delivery: EmailDelivery
}) {
  /** Only touches the job while it is still open (neither sent nor permanently failed). */
  const updateOpenJob = async (id: number, values: UpdateObject<Tables, 'email_jobs'>) => {
    const result = await db
      .updateTable('email_jobs')
      .set(values)
      .where('id', '=', id)
      .where('sent_at', 'is', null)
      .where('failed_permanently_at', 'is', null)
      .executeTakeFirst()
    return result.numUpdatedRows > 0n
  }

  /** One scan at `now`; stops between jobs once `signal` is aborted. */
  const runOnce = async (signal?: AbortSignal, now: Date = new Date()): Promise<void> => {
    if (!settings.enabled) return
    const jobs = await read(db, (trx) =>
      trx
        .selectFrom('email_jobs')
        .select(['id', 'payload', 'attempt_count'])
        .where('sent_at', 'is', null)
        .where('failed_permanently_at', 'is', null)
        .where((eb) => eb.or([eb('next_attempt_at', 'is', null), eb('next_attempt_at', '<=', now)]))
        .orderBy('id')
        .execute(),
    )
    for (const job of jobs) {
      if (signal?.aborted === true) return
      // The increment is SQL, so two racing scans never write the same counter value.
      const started = await updateOpenJob(job.id, { attempt_count: sql`attempt_count + 1` })
      if (!started) continue
      const attempt = job.attempt_count + 1
      const context = { jobId: job.id, kind: job.payload.kind, attempt }
      const result = await delivery.deliver(job.payload, `voenix-email-${String(job.id)}`)
      if (result.kind === 'accepted') {
        if (
          await updateOpenJob(job.id, {
            sent_at: sql<Date>`now()`,
            last_error_code: null,
            next_attempt_at: null,
          })
        ) {
          logger.info(context, 'Email job transmitted')
        }
      } else if (isPermanentEmailError(result.code) || attempt >= MAX_EMAIL_ATTEMPTS) {
        if (
          await updateOpenJob(job.id, {
            last_error_code: result.code,
            next_attempt_at: null,
            failed_permanently_at: now,
          })
        ) {
          logger.error(
            { ...context, code: result.code },
            'Email job failed permanently and will not be retried',
          )
        }
      } else {
        const nextAttemptAt = new Date(now.getTime() + retryDelayMillis(attempt))
        if (
          await updateOpenJob(job.id, {
            last_error_code: result.code,
            next_attempt_at: nextAttemptAt,
          })
        ) {
          logger.warn(
            { ...context, code: result.code, nextAttemptAt: nextAttemptAt.toISOString() },
            'Email job remains open after a transient failure',
          )
        }
      }
    }
  }

  let controller: AbortController | null = null
  let loop: Promise<void> = Promise.resolve()

  return {
    runOnce,

    /** Starts scanning now and then every interval, until `stop`. */
    start: () => {
      if (controller !== null) throw new Error('The email worker is already started')
      // Disabled email is silent everywhere else, so this one line explains the missing mails.
      if (!settings.enabled) logger.warn('Email is disabled (EMAIL_ENABLED=false); no mail is sent')
      const { signal } = (controller = new AbortController())
      loop = (async () => {
        while (!signal.aborted) {
          try {
            await runOnce(signal)
          } catch (error) {
            // Never the message: it could quote a stored mail.
            logger.error({ error: errorDetails(error) }, 'Email worker scan failed')
          }
          await sleep(settings.pollIntervalMinutes * 60_000, undefined, { signal }).catch(
            () => undefined,
          )
        }
      })()
    },

    /** Stops the loop and resolves once the scan in progress has finished its current job. */
    stop: async () => {
      controller?.abort()
      await loop
    },
  }
}
