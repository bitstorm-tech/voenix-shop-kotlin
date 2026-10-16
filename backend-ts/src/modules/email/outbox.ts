import type { Transaction } from 'kysely'
import type { Tables } from '@/platform/db/database'
import type { QueuedEmailKind, RenderedEmail } from './templates/layout'
import './schema'

/**
 * Stores a rendered mail for the worker, inside the caller's transaction: if the business change
 * rolls back, so does its mail. Returns the job id.
 */
export async function enqueue(
  trx: Transaction<Tables>,
  email: RenderedEmail<QueuedEmailKind>,
): Promise<number> {
  const { id } = await trx
    .insertInto('email_jobs')
    .values({ payload: JSON.stringify(email) })
    .returning('id')
    .executeTakeFirstOrThrow()
  return id
}
