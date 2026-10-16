import { sql, type Kysely, type Transaction } from 'kysely'
import { renderSpodOpsAlert, type EmailModule, type SpodOpsAlertReason } from '@/modules/email'
import { spodEnvironmentOfStoredValue, type SpodAccess } from '@/modules/spod'
import { read, write, type Tables } from '@/platform/db/database'
import { createLogger } from '@/platform/logging'
import type { ProductionOrders } from '../orders'
import '../schema'

/**
 * The submission codes no further scan heals: each waits for a person (an admin fixing master
 * data, an operator refunding an order the partner will not produce). Recording one of them
 * alerts the operator (SPOD fulfillment decision 7); every other code comes back on its own.
 */
export const PERMANENT_FAILURES: ReadonlySet<string> = new Set([
  'ORDER_CREATE_REJECTED',
  'PLACEMENT_UNAVAILABLE',
  'PHONE_MISSING',
  'ITEM_WITHOUT_SPOD_PRODUCT',
  'SPOD_MAPPING_CHANGED',
  'ITEM_SET_CHANGED',
])

/** One automatic re-create is allowed; the second ambiguity quarantines the job. */
export const MAX_AMBIGUOUS_CREATES = 2

/** The partner states the webhook writes; `CONFIRMED` is this shop's own. */
export type ReportedRemoteState = 'CANCELLED' | 'NEEDS_ACTION'

export type SpodOrderRepository = ReturnType<typeof createSpodOrderRepository>

const logger = createLogger('production-spod')

/**
 * The remote lifecycle of SPOD jobs: `production_spod_orders`, `production_spod_designs`, and the
 * supplier's destination read. Open/finished derives from the job's `prepared_at`.
 *
 * Every write that ends on an operator's desk enqueues the ops alert in its own transaction, and
 * only when it changed something: with the payload outbox (#248) there is no unique reference left
 * to deduplicate a mail, so "one alert per job state" lives in the guarded writes. A code a scan
 * records again, or a state the partner redelivers, changes no row and sends nothing.
 */
export function createSpodOrderRepository({
  db,
  outbox,
  orders,
  alertEmail,
}: {
  db: Kysely<Tables>
  outbox: EmailModule['outbox']
  orders: Pick<ProductionOrders, 'recordEvent'>
  /** `null` in a deployment without SPOD settings, which then has no one to alert. */
  alertEmail: string | null
}) {
  const alert = async (trx: Transaction<Tables>, jobId: number, reason: SpodOpsAlertReason) => {
    if (alertEmail === null) {
      logger.error({ jobId, reason }, 'A SPOD job needs an operator, but no alert address is set')
      return
    }
    const row = await trx
      .selectFrom('production_spod_orders as o')
      .innerJoin('production_jobs as j', 'j.id', 'o.production_job_id')
      .innerJoin('production_requests as r', 'r.id', 'j.request_id')
      .select(['r.order_id', 'o.external_reference'])
      .where('o.production_job_id', '=', jobId)
      .executeTakeFirstOrThrow()
    await outbox.enqueue(
      trx,
      renderSpodOpsAlert({
        recipient: alertEmail,
        jobId,
        orderId: row.order_id,
        reason,
        externalReference: row.external_reference,
      }),
    )
  }

  return {
    /**
     * The SPOD jobs still to be prepared, oldest first. A job quarantined as `OUTCOME_UNKNOWN` is
     * left out (no automatic call until a human looked), and so is one the partner cancelled (it
     * will never leave that state). `NEEDS_ACTION` stays: someone resolves it in the backoffice.
     */
    openJobs: () =>
      read(db, (trx) =>
        trx
          .selectFrom('production_jobs as j')
          .innerJoin('production_requests as r', 'r.id', 'j.request_id')
          .leftJoin('production_spod_orders as o', 'o.production_job_id', 'j.id')
          .select([
            'j.id',
            'r.order_id as orderId',
            'j.supplier_id as supplierId',
            'o.external_reference as spodOrderId',
            'o.attempt_count as attemptCount',
          ])
          .where('j.fulfillment_channel', '=', 'SPOD')
          .where('j.prepared_at', 'is', null)
          .where((eb) =>
            eb.or([
              eb('o.create_state', 'is', null),
              eb('o.create_state', '!=', 'OUTCOME_UNKNOWN'),
            ]),
          )
          .where((eb) =>
            eb.or([eb('o.remote_state', 'is', null), eb('o.remote_state', '!=', 'CANCELLED')]),
          )
          .orderBy('j.id')
          .execute(),
      ),

    /** Creates the job's order row on its first attempt and counts the attempt. */
    startAttempt: (jobId: number) =>
      write(db, async (trx) => {
        await trx
          .insertInto('production_spod_orders')
          .values({ production_job_id: jobId })
          .onConflict((conflict) => conflict.column('production_job_id').doNothing())
          .execute()
        await trx
          .updateTable('production_spod_orders')
          .set({ attempt_count: sql`attempt_count + 1` })
          .where('production_job_id', '=', jobId)
          .execute()
      }),

    /**
     * Records the bounded code of a failed attempt. A permanent code alerts the operator, once:
     * the update only touches the row when the code differs from the stored one, so a code every
     * scan records again sends no second mail.
     */
    recordFailure: (jobId: number, code: string) =>
      write(db, async (trx) => {
        const result = await trx
          .updateTable('production_spod_orders')
          .set({ last_error_code: code })
          .where('production_job_id', '=', jobId)
          .where('last_error_code', 'is distinct from', code)
          .executeTakeFirst()
        if (result.numUpdatedRows > 0n && PERMANENT_FAILURES.has(code)) {
          await alert(trx, jobId, 'SUBMISSION_BLOCKED')
        }
      }),

    /** The design ids already uploaded for this job, by item position. */
    designs: async (jobId: number): Promise<Map<number, string>> => {
      const rows = await read(db, (trx) =>
        trx
          .selectFrom('production_spod_designs')
          .select(['position', 'design_id'])
          .where('production_job_id', '=', jobId)
          .execute(),
      )
      return new Map(rows.map((row) => [row.position, row.design_id]))
    },

    /** Records the design of each position, so a re-scan skips the uploads it already did. */
    recordDesigns: async (jobId: number, designIds: Map<number, string>) => {
      if (designIds.size === 0) return
      await db
        .insertInto('production_spod_designs')
        .values(
          [...designIds].map(([position, designId]) => ({
            production_job_id: jobId,
            position,
            design_id: designId,
          })),
        )
        .onConflict((conflict) => conflict.columns(['production_job_id', 'position']).doNothing())
        .execute()
    },

    /** The variant names the job was split with, by position: the snapshot, not today's. */
    itemVariantNames: async (jobId: number): Promise<Map<number, string>> => {
      const rows = await read(db, (trx) =>
        trx
          .selectFrom('production_job_items')
          .select(['position', 'variant_name'])
          .where('production_job_id', '=', jobId)
          .execute(),
      )
      return new Map(rows.map((row) => [row.position, row.variant_name]))
    },

    /**
     * Persists the partner's order id in a transaction of its own, before the confirmation is even
     * attempted: if that id is lost, the order is lost. Guarded on `PENDING`, so a second creation
     * can never overwrite the id of an order that is already ours.
     */
    recordCreatedOrder: async (jobId: number, spodOrderId: string): Promise<boolean> => {
      const result = await db
        .updateTable('production_spod_orders')
        .set({ external_reference: spodOrderId, create_state: 'CREATED', last_error_code: null })
        .where('production_job_id', '=', jobId)
        .where('create_state', '=', 'PENDING')
        .executeTakeFirst()
      return result.numUpdatedRows > 0n
    },

    /**
     * Counts one creation whose outcome nobody knows; the second quarantines the job, and the same
     * commit enqueues the alert that reports it. Answers the new count.
     */
    recordAmbiguousCreate: (jobId: number, code: string) =>
      write(db, async (trx) => {
        const { count } = await trx
          .updateTable('production_spod_orders')
          .set({
            create_ambiguous_count: sql`create_ambiguous_count + 1`,
            last_error_code: code,
            create_state: sql`CASE WHEN create_ambiguous_count + 1 >= ${MAX_AMBIGUOUS_CREATES}
              THEN 'OUTCOME_UNKNOWN' ELSE create_state END`,
          })
          .where('production_job_id', '=', jobId)
          .returning('create_ambiguous_count as count')
          .executeTakeFirstOrThrow()
        if (count >= MAX_AMBIGUOUS_CREATES) await alert(trx, jobId, 'OUTCOME_UNKNOWN')
        return count
      }),

    /**
     * Writes the state the partner reported, records `SPOD_REMOTE_STATE_CHANGED` in the order's
     * history, and alerts the operator, only when the state changed: a redelivered event writes
     * and sends nothing, a cancellation after a needs-action sends a second alert.
     */
    recordRemoteState: (jobId: number, state: ReportedRemoteState) =>
      write(db, async (trx) => {
        const changed = await trx
          .updateTable('production_spod_orders')
          .set({ remote_state: state })
          .where('production_job_id', '=', jobId)
          .where('remote_state', 'is distinct from', state)
          .returning((eb) =>
            eb
              .selectFrom('production_jobs as j')
              .innerJoin('production_requests as r', 'r.id', 'j.request_id')
              .select('r.order_id')
              .whereRef('j.id', '=', 'production_spod_orders.production_job_id')
              .as('orderId'),
          )
          .executeTakeFirst()
        if (changed?.orderId == null) return
        await orders.recordEvent(trx, changed.orderId, {
          kind: 'SPOD_REMOTE_STATE_CHANGED',
          actorType: 'CHANNEL',
          details: { jobId, state },
        })
        await alert(trx, jobId, state)
      }),

    /** The job behind the partner's order id; a unique index read. */
    jobIdOfExternalReference: async (externalReference: string): Promise<number | undefined> => {
      const row = await read(db, (trx) =>
        trx
          .selectFrom('production_spod_orders')
          .select('production_job_id')
          .where('external_reference', '=', externalReference)
          .executeTakeFirst(),
      )
      return row?.production_job_id
    },

    /** The remote order is confirmed and the job ready to ship: one commit. */
    completeConfirmation: (jobId: number) =>
      write(db, async (trx) => {
        await trx
          .updateTable('production_spod_orders')
          .set({ confirmed_at: sql<Date>`now()`, remote_state: 'CONFIRMED', last_error_code: null })
          .where('production_job_id', '=', jobId)
          .execute()
        await trx
          .updateTable('production_jobs')
          .set({ prepared_at: sql<Date>`now()` })
          .where('id', '=', jobId)
          .where('prepared_at', 'is', null)
          .execute()
      }),

    /**
     * The supplier's SPOD destination with its access token. At most one is enabled (a partial
     * unique index); only disabled ones is a different fix than none at all.
     */
    destination: async (
      supplierId: number,
    ): Promise<{ kind: 'found'; access: SpodAccess } | { kind: 'missing' | 'disabled' }> => {
      const rows = await read(db, (trx) =>
        trx
          .selectFrom('production_destinations as d')
          .innerJoin('production_destination_spod as s', 's.id', 'd.id')
          .select(['d.id', 'd.enabled', 's.environment', 's.access_token', 's.timeout_seconds'])
          .where('d.supplier_id', '=', supplierId)
          .where('d.channel', '=', 'SPOD')
          .orderBy('d.id')
          .execute(),
      )
      const enabled = rows.find((row) => row.enabled)
      if (rows.length === 0) return { kind: 'missing' }
      if (enabled === undefined) return { kind: 'disabled' }
      return {
        kind: 'found',
        access: {
          destinationId: enabled.id,
          environment: spodEnvironmentOfStoredValue(enabled.environment),
          accessToken: enabled.access_token,
          timeoutSeconds: enabled.timeout_seconds,
        },
      }
    },
  }
}
