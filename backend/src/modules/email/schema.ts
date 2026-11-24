import type { Generated, JSONColumnType } from 'kysely'
import type { RenderedEmail } from './templates/layout'

/**
 * The `email_jobs` table (V5, reshaped by V32, V33, and V41): one rendered mail waiting for
 * delivery. A job is open while `sent_at` and `failed_permanently_at` are both `NULL`;
 * `attempt_count`, `last_error_code`, and `next_attempt_at` describe the retry state. `order_id`
 * names the order a mail is about, when the enqueuing module passed one; it has no foreign key.
 */
export interface EmailJobsTable {
  id: Generated<number>
  payload: JSONColumnType<RenderedEmail>
  order_id: number | null
  attempt_count: Generated<number>
  last_error_code: string | null
  next_attempt_at: Date | null
  failed_permanently_at: Date | null
  created_at: Generated<Date>
  sent_at: Date | null
}

declare module '@/platform/db/database' {
  interface Tables {
    email_jobs: EmailJobsTable
  }
}
