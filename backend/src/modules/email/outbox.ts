import { sql, type Kysely, type Transaction } from 'kysely'
import { read, type Tables } from '@/platform/db/database'
import type { QueuedEmailKind, RenderedEmail } from './templates/layout'
import './schema'

/**
 * Stores a rendered mail for the worker, inside the caller's transaction: if the business change
 * rolls back, so does its mail. `orderId` attributes the mail to the order it is about, so the
 * order's history can list it. Returns the job id.
 */
export async function enqueue(
  trx: Transaction<Tables>,
  email: RenderedEmail<QueuedEmailKind>,
  { orderId = null }: { orderId?: number | null } = {},
): Promise<number> {
  const { id } = await trx
    .insertInto('email_jobs')
    .values({ payload: JSON.stringify(email), order_id: orderId })
    .returning('id')
    .executeTakeFirstOrThrow()
  return id
}

/** A mail the outbox has not sent although it should have by now (see `stalledEmails`). */
export interface EmailFinding {
  kind: 'EMAIL_STALLED'
  subject: { type: 'EMAIL'; id: number }
  orderId: number | null
  since: Date
  attemptCount: number
  lastErrorCode: string | null
  /** The kind of the stalled mail: a stalled operations alert is listed, not mailed again. */
  emailKind: QueuedEmailKind
}

/** A mail older than this and still unsent is stalled, whatever its attempts say. */
const STALLED_AFTER_MILLIS = 60 * 60 * 1000
/** A mail that failed this often is stalled, however young it is. */
const STALLED_ATTEMPTS = 3

/**
 * Every unsent mail that is older than an hour or has failed three times, as one finding each,
 * oldest first. The operations worker calls it on every scan.
 */
export async function stalledEmails(db: Kysely<Tables>, now: Date): Promise<EmailFinding[]> {
  const rows = await read(db, (trx) =>
    trx
      .selectFrom('email_jobs')
      .select([
        'id',
        'order_id',
        'created_at',
        'attempt_count',
        'last_error_code',
        sql<QueuedEmailKind>`payload->>'kind'`.as('kind'),
      ])
      .where('sent_at', 'is', null)
      .where((eb) =>
        eb.or([
          eb('created_at', '<', new Date(now.getTime() - STALLED_AFTER_MILLIS)),
          eb('attempt_count', '>=', STALLED_ATTEMPTS),
        ]),
      )
      .orderBy('id')
      .execute(),
  )
  return rows.map((row) => ({
    kind: 'EMAIL_STALLED',
    subject: { type: 'EMAIL', id: row.id },
    orderId: row.order_id,
    since: row.created_at,
    attemptCount: row.attempt_count,
    lastErrorCode: row.last_error_code,
    emailKind: row.kind,
  }))
}

/** The mails attributed to one order, oldest first, without their content. */
export function emailsOfOrder(db: Kysely<Tables>, orderId: number) {
  return read(db, (trx) =>
    trx
      .selectFrom('email_jobs')
      .select([
        'id as emailJobId',
        sql<QueuedEmailKind>`payload->>'kind'`.as('kind'),
        sql<string>`payload->>'recipient'`.as('recipient'),
        'attempt_count as attemptCount',
        'last_error_code as lastErrorCode',
        'created_at as createdAt',
        'sent_at as sentAt',
      ])
      .where('order_id', '=', orderId)
      .orderBy('id')
      .execute(),
  )
}
