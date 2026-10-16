import { sql, type Kysely, type Transaction } from 'kysely'
import type { EmailFinding, OperationsAlertFinding, OperationsAlertKind } from '@/modules/email'
import type { OrderFinding } from '@/modules/order'
import type { ProductionFinding } from '@/modules/production'
import { read, write, type Tables } from '@/platform/db/database'
import './schema'

/** One finding as an owning module reports it; each module names its own kinds and subjects. */
export type Finding = OrderFinding | ProductionFinding | EmailFinding

/** How long a resolved finding stays in the resolved list. */
const RESOLVED_LIST_DAYS = 7

/**
 * Whether a finding is mailed. The two SPOD kinds are listed only, because the SPOD channel already
 * mails each of those state transitions; so is a stalled operations alert, because otherwise a
 * broken outbox would enqueue a new alert about the stalled alert on every scan.
 */
const mailed = (finding: Finding) =>
  finding.kind !== 'SPOD_QUARANTINED' &&
  finding.kind !== 'SPOD_REMOTE_ATTENTION' &&
  !(finding.kind === 'EMAIL_STALLED' && finding.emailKind === 'OPERATIONS_ALERT')

/** An open finding of a mailed kind that no alert named yet; `isNew` when this scan inserted it. */
export type UnalertedFinding = OperationsAlertFinding & { id: number; isNew: boolean }

/**
 * Step 4 of a scan, inside its transaction: alerts about the unalerted findings and answers whether
 * a mail was enqueued, which is what sets their `alerted_at`.
 */
export type Alert = (
  trx: Transaction<Tables>,
  findings: readonly UnalertedFinding[],
) => Promise<boolean>

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
     *
     * Then the open rows of the reported mailed findings that have no `alerted_at` yet go to
     * `alert`; when it enqueued a mail they get `alerted_at = now()`. A listed-only finding is never
     * selected and keeps `alerted_at` `NULL`. Without `alert` nothing is alerted.
     */
    record: (findings: readonly Finding[], alert: Alert = () => Promise.resolve(false)) =>
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

        const toAlert = findings.filter(mailed)
        if (toAlert.length === 0) return
        const unalerted = await trx
          .selectFrom('operations_findings')
          .select([
            'id',
            'kind',
            'subject_type as subjectType',
            'subject_id as subjectId',
            'order_id as orderId',
            'since',
            'attempt_count as attemptCount',
            'last_error_code as lastErrorCode',
            sql<boolean>`first_seen_at = now()`.as('isNew'),
          ])
          .where('resolved_at', 'is', null)
          .where('alerted_at', 'is', null)
          .where((eb) =>
            eb(
              eb.refTuple('kind', 'subject_type', 'subject_id'),
              'in',
              toAlert.map((finding) =>
                eb.tuple(finding.kind, finding.subject.type, finding.subject.id),
              ),
            ),
          )
          .orderBy('id')
          .$narrowType<{ kind: OperationsAlertKind }>()
          .execute()
        if (unalerted.length === 0 || !(await alert(trx, unalerted))) return
        await trx
          .updateTable('operations_findings')
          .set({ alerted_at: sql<Date>`now()` })
          .where(
            'id',
            'in',
            unalerted.map((finding) => finding.id),
          )
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
