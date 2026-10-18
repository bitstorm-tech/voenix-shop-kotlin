import type { Generated, JSONColumnType } from 'kysely'
import type { RenderedEmail } from './templates/layout'

/**
 * The `email_jobs` table (V5, reshaped by V32 and V33): one rendered mail waiting for delivery. A
 * job is open while `sent_at` is `NULL`; `attempt_count` and `last_error_code` describe the
 * attempts. `order_id` names the order a mail is about, when the enqueuing module passed one; it
 * has no foreign key.
 */
export interface EmailJobsTable {
  id: Generated<number>
  payload: JSONColumnType<RenderedEmail>
  order_id: number | null
  attempt_count: Generated<number>
  last_error_code: string | null
  created_at: Generated<Date>
  sent_at: Date | null
}

declare module '@/platform/db/database' {
  interface Tables {
    email_jobs: EmailJobsTable
  }
}
