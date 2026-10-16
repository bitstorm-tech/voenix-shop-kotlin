import { sql, type Kysely, type UpdateObject } from 'kysely'
import { setTimeout as sleep } from 'node:timers/promises'
import { read, type Tables } from '@/platform/db/database'
import { createLogger, errorClass } from '@/platform/logging'
import type { Settings } from '@/platform/settings'
import type { EmailDelivery } from './delivery/sweego'
import './schema'

const logger = createLogger('email-worker')

/**
 * The outbox worker: every `pollIntervalMinutes` it sends every open job, oldest first. Each
 * attempt counts in `attempt_count`; a failure stores its safe code and leaves the job open for the
 * next scan, with no maximum and no terminal state. Disabled email leaves every job untouched.
 * Delivery is at least once: a crash between Sweego's acceptance and the `sent_at` update sends the
 * job again on the next start.
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
  /** Only touches the job while it is still open; answers whether it did. */
  const updateOpenJob = async (id: number, values: UpdateObject<Tables, 'email_jobs'>) => {
    const result = await db
      .updateTable('email_jobs')
      .set(values)
      .where('id', '=', id)
      .where('sent_at', 'is', null)
      .executeTakeFirst()
    return result.numUpdatedRows > 0n
  }

  /** One scan; stops between jobs once `signal` is aborted. */
  const runOnce = async (signal?: AbortSignal): Promise<void> => {
    if (!settings.enabled) return
    const jobs = await read(db, (trx) =>
      trx
        .selectFrom('email_jobs')
        .select(['id', 'payload', 'attempt_count'])
        .where('sent_at', 'is', null)
        .orderBy('id')
        .execute(),
    )
    for (const job of jobs) {
      if (signal?.aborted === true) return
      // The increment is SQL, so two racing scans never write the same counter value.
      const started = await updateOpenJob(job.id, { attempt_count: sql`attempt_count + 1` })
      if (!started) continue
      const context = { jobId: job.id, kind: job.payload.kind, attempt: job.attempt_count + 1 }
      const result = await delivery.deliver(job.payload, `voenix-email-${String(job.id)}`)
      if (result.kind === 'accepted') {
        if (await updateOpenJob(job.id, { sent_at: sql<Date>`now()`, last_error_code: null })) {
          logger.info(context, 'Email job transmitted')
        }
      } else if (await updateOpenJob(job.id, { last_error_code: result.code })) {
        logger.warn({ ...context, code: result.code }, 'Email job remains open after a failure')
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
            // The class only: a message could quote a stored mail.
            logger.error({ errorClass: errorClass(error) }, 'Email worker scan failed')
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
