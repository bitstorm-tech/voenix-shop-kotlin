import type { Generated } from 'kysely'

/**
 * The `operations_findings` table (V33): one row per finding the operations worker saw. A finding
 * is open while `resolved_at` is `NULL`; the partial unique index `ux_operations_findings_open`
 * allows one open row per `(kind, subject_type, subject_id)`. `order_id` has no foreign key.
 */
export interface OperationsFindingsTable {
  id: Generated<number>
  kind: string
  subject_type: string
  subject_id: number
  order_id: number | null
  since: Date
  attempt_count: number | null
  last_error_code: string | null
  first_seen_at: Generated<Date>
  last_seen_at: Generated<Date>
  alerted_at: Date | null
  resolved_at: Date | null
}

/**
 * The `operations_last_scan` table (V35): at most one row (`id` is always 1), the time the last
 * scan recorded its findings.
 */
export interface OperationsLastScanTable {
  id: Generated<number>
  finished_at: Date
}

declare module '@/platform/db/database' {
  interface Tables {
    operations_findings: OperationsFindingsTable
    operations_last_scan: OperationsLastScanTable
  }
}
