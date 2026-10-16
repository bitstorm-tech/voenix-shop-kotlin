import { sql, type Kysely } from 'kysely'
import type { EmailFinding } from '@/modules/email'
import type { OrderFinding } from '@/modules/order'
import type { ProductionFinding } from '@/modules/production'
import { read, write, type Tables } from '@/platform/db/database'
import './schema'

/** One finding as an owning module reports it; each module names its own kinds and subjects. */
export type Finding = OrderFinding | ProductionFinding | EmailFinding

/** How long a resolved finding stays in the resolved list. */
const RESOLVED_LIST_DAYS = 7

export type FindingRepository = ReturnType<typeof createFindingRepository>

export function createFindingRepository(db: Kysely<Tables>) {
  return {
    /**
     * Records what one scan reported, in one transaction. Every reported finding is upserted on
     * `ux_operations_findings_open` (an `ON CONFLICT` on a partial unique index names the index's
     * `WHERE` as well): a new one is inserted, an open one gets `last_seen_at`, `attempt_count`,
     * and `last_error_code` refreshed and keeps `first_seen_at`. `now()` is the transaction's start
     * time, so after the upsert every reported row has `last_seen_at = now()`, and every open row
     * with an older `last_seen_at` is one this scan did not report: it is resolved. A resolved row
     * is never touched again; a finding that reappears is a new row.
     */
    record: (findings: readonly Finding[]) =>
      write(db, async (trx) => {
        if (findings.length > 0) {
          await trx
            .insertInto('operations_findings')
            .values(
              findings.map((finding) => ({
                kind: finding.kind,
                subject_type: finding.subject.type,
                subject_id: finding.subject.id,
                order_id: finding.orderId,
                since: finding.since,
                attempt_count: finding.attemptCount,
                last_error_code: finding.lastErrorCode,
              })),
            )
            .onConflict((conflict) =>
              conflict
                .columns(['kind', 'subject_type', 'subject_id'])
                .where('resolved_at', 'is', null)
                .doUpdateSet((eb) => ({
                  last_seen_at: sql<Date>`now()`,
                  attempt_count: eb.ref('excluded.attempt_count'),
                  last_error_code: eb.ref('excluded.last_error_code'),
                })),
            )
            .execute()
        }
        await trx
          .updateTable('operations_findings')
          .set({ resolved_at: sql<Date>`now()` })
          .where('resolved_at', 'is', null)
          .where('last_seen_at', '<', sql<Date>`now()`)
          .execute()
      }),

    /**
     * The open findings, or with `resolved` those resolved within the last seven days, newest
     * `first_seen_at` first.
     */
    list: (resolved: boolean) =>
      read(db, (trx) =>
        trx
          .selectFrom('operations_findings')
          .select([
            'kind',
            'subject_type',
            'subject_id',
            'order_id',
            'since',
            'attempt_count',
            'last_error_code',
            'first_seen_at',
            'alerted_at',
            'resolved_at',
          ])
          .$call((query) =>
            resolved
              ? query.where(
                  'resolved_at',
                  '>=',
                  sql<Date>`now() - make_interval(days => ${RESOLVED_LIST_DAYS})`,
                )
              : query.where('resolved_at', 'is', null),
          )
          .orderBy('first_seen_at', 'desc')
          .orderBy('id', 'desc')
          .execute(),
      ),
  }
}
