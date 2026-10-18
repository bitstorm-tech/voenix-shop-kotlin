import { beforeEach, describe, expect, test } from 'bun:test'
import type { Insertable } from 'kysely'
import { testDatabase } from '@test-support/database'
import type { ProductionFinding } from './findings'
import type { ProductionJobsTable, ProductionSpodOrdersTable } from './schema'
import {
  productionTestKit,
  resetProduction,
  seedOrder,
  seedSftpDestination,
  seedSupplier,
} from './test-fixtures'

const database = testDatabase()

const NOW = new Date('2026-10-16T12:00:00.000Z')
const minutesAgo = (minutes: number) => new Date(NOW.getTime() - minutes * 60_000)
const DAY_MINUTES = 24 * 60

let supplierId = 0
let destinationId = 0

beforeEach(async () => {
  await resetProduction(database.db)
  supplierId = await seedSupplier(database.db, 'Druckerei')
  destinationId = await seedSftpDestination(database.db, {
    supplierId,
    port: 22,
    fingerprint: 'SHA256:unused',
  })
})

/** A production request of a fresh order; processed unless the test says otherwise. */
async function seedRequest(
  values: {
    createdAt?: Date
    processedAt?: Date | null
    attemptCount?: number
    lastErrorCode?: string | null
  } = {},
) {
  const orderId = await seedOrder(database.db)
  const { id } = await database.db
    .insertInto('production_requests')
    .values({
      order_id: orderId,
      created_at: values.createdAt ?? minutesAgo(DAY_MINUTES * 30),
      processed_at: values.processedAt === undefined ? minutesAgo(1) : values.processedAt,
      attempt_count: values.attemptCount ?? 1,
      last_error_code: values.lastErrorCode ?? null,
    })
    .returning('id')
    .executeTakeFirstOrThrow()
  return { requestId: id, orderId }
}

/**
 * A job of its own processed request; a job whose `generated_at` is set carries the digest the
 * table demands next to it. A SPOD job gets its remote order row when `spod` is given.
 */
async function seedJob(
  values: Partial<Insertable<ProductionJobsTable>>,
  spod?: Partial<Insertable<ProductionSpodOrdersTable>>,
) {
  const { requestId, orderId } = await seedRequest()
  const { id } = await database.db
    .insertInto('production_jobs')
    .values({
      request_id: requestId,
      supplier_id: supplierId,
      fulfillment_channel: 'SFTP',
      file_name: 'job.pdf',
      content_sha256: values.generated_at == null ? null : 'digest',
      ...values,
    })
    .returning('id')
    .executeTakeFirstOrThrow()
  if (spod !== undefined) {
    await database.db
      .insertInto('production_spod_orders')
      .values({ production_job_id: id, ...spod })
      .execute()
  }
  return { jobId: id, orderId }
}

async function seedDelivery(jobId: number, attemptCount: number, deliveredAt: Date | null = null) {
  const { id } = await database.db
    .insertInto('production_deliveries')
    .values({
      production_job_id: jobId,
      destination_id: destinationId,
      attempt_count: attemptCount,
      last_error_code: attemptCount > 0 ? 'SFTP_CONNECT_FAILED' : null,
      delivered_at: deliveredAt,
    })
    .returning('id')
    .executeTakeFirstOrThrow()
  return id
}

const findingsAt = async (now: Date) => {
  const kit = await productionTestKit(database.db)
  const byKindAndId = (a: ProductionFinding, b: ProductionFinding) =>
    a.kind.localeCompare(b.kind) || a.subject.id - b.subject.id
  return (await kit.module.findings(now)).sort(byKindAndId)
}

describe('production.findings', () => {
  test('a request that does not split: older than 15 minutes or three attempts', async () => {
    const old = await seedRequest({ createdAt: minutesAgo(16), processedAt: null, attemptCount: 0 })
    const failing = await seedRequest({
      createdAt: minutesAgo(1),
      processedAt: null,
      attemptCount: 3,
      lastErrorCode: 'ITEM_WITHOUT_SUPPLIER',
    })
    await seedRequest({ createdAt: minutesAgo(15), processedAt: null, attemptCount: 2 })
    await seedRequest({ createdAt: minutesAgo(60), processedAt: minutesAgo(50) })

    expect(await findingsAt(NOW)).toEqual([
      {
        kind: 'PRODUCTION_REQUEST_STALLED',
        subject: { type: 'REQUEST', id: old.requestId },
        orderId: old.orderId,
        since: minutesAgo(16),
        attemptCount: 0,
        lastErrorCode: null,
      },
      {
        kind: 'PRODUCTION_REQUEST_STALLED',
        subject: { type: 'REQUEST', id: failing.requestId },
        orderId: failing.orderId,
        since: minutesAgo(1),
        attemptCount: 3,
        lastErrorCode: 'ITEM_WITHOUT_SUPPLIER',
      },
    ])
  })

  test('an SFTP job without its document: older than 15 minutes or three attempts', async () => {
    const old = await seedJob({ created_at: minutesAgo(16) })
    const failing = await seedJob({
      created_at: minutesAgo(1),
      generation_attempt_count: 3,
      last_generation_error_code: 'MISSING_IMAGE',
    })
    await seedJob({ created_at: minutesAgo(15), generation_attempt_count: 2 })
    // A SPOD job never has a document; it is not a generation finding.
    await seedJob({ created_at: minutesAgo(30), fulfillment_channel: 'SPOD' })

    expect(await findingsAt(NOW)).toEqual([
      {
        kind: 'JOB_GENERATION_STALLED',
        subject: { type: 'JOB', id: old.jobId },
        orderId: old.orderId,
        since: minutesAgo(16),
        attemptCount: 0,
        lastErrorCode: null,
      },
      {
        kind: 'JOB_GENERATION_STALLED',
        subject: { type: 'JOB', id: failing.jobId },
        orderId: failing.orderId,
        since: minutesAgo(1),
        attemptCount: 3,
        lastErrorCode: 'MISSING_IMAGE',
      },
    ])
  })

  test('a delivery that does not arrive: an hour after the document or three attempts', async () => {
    const generated = (minutes: number) => ({
      created_at: minutesAgo(minutes + 1),
      generated_at: minutesAgo(minutes),
      prepared_at: minutesAgo(minutes),
      generation_attempt_count: 1,
    })
    const old = await seedJob(generated(61))
    const oldDelivery = await seedDelivery(old.jobId, 0)
    const failing = await seedJob(generated(1))
    const failingDelivery = await seedDelivery(failing.jobId, 3)
    await seedDelivery((await seedJob(generated(60))).jobId, 2)
    await seedDelivery((await seedJob(generated(120))).jobId, 1, minutesAgo(100))
    // A stalled generation is one finding, not one per delivery as well.
    const ungenerated = await seedJob({ created_at: minutesAgo(5), generation_attempt_count: 3 })
    await seedDelivery(ungenerated.jobId, 5)

    expect(await findingsAt(NOW)).toEqual([
      {
        kind: 'DELIVERY_STALLED',
        subject: { type: 'DELIVERY', id: oldDelivery },
        orderId: old.orderId,
        since: minutesAgo(61),
        attemptCount: 0,
        lastErrorCode: null,
      },
      {
        kind: 'DELIVERY_STALLED',
        subject: { type: 'DELIVERY', id: failingDelivery },
        orderId: failing.orderId,
        since: minutesAgo(1),
        attemptCount: 3,
        lastErrorCode: 'SFTP_CONNECT_FAILED',
      },
      {
        kind: 'JOB_GENERATION_STALLED',
        subject: { type: 'JOB', id: ungenerated.jobId },
        orderId: ungenerated.orderId,
        since: minutesAgo(5),
        attemptCount: 3,
        lastErrorCode: null,
      },
    ])
  })

  test('a SPOD job: not submitted, quarantined, or flagged by the partner', async () => {
    const spod = (minutes: number) => ({
      fulfillment_channel: 'SPOD',
      created_at: minutesAgo(minutes),
    })
    const unsubmitted = await seedJob(spod(61))
    const failing = await seedJob(spod(1), {
      attempt_count: 3,
      last_error_code: 'PLACEMENT_UNAVAILABLE',
    })
    await seedJob(spod(60), { attempt_count: 2 })
    const quarantined = await seedJob(spod(600), {
      create_state: 'OUTCOME_UNKNOWN',
      create_ambiguous_count: 2,
      attempt_count: 2,
      last_error_code: 'CREATE_TIMEOUT',
    })
    const created = {
      create_state: 'CREATED',
      external_reference: 'remote',
      created_at: minutesAgo(50),
    }
    const prepared = { ...spod(55), prepared_at: minutesAgo(40) }
    const needsAction = await seedJob(prepared, {
      ...created,
      external_reference: 'remote-1',
      remote_state: 'NEEDS_ACTION',
    })
    const cancelled = await seedJob(spod(55), {
      ...created,
      external_reference: 'remote-2',
      remote_state: 'CANCELLED',
    })
    await seedJob(prepared, {
      ...created,
      external_reference: 'remote-3',
      remote_state: 'CONFIRMED',
      confirmed_at: minutesAgo(40),
    })
    await seedJob(
      {
        ...prepared,
        shipped_at: minutesAgo(10),
        shipped_by_channel: 'SPOD',
      },
      { ...created, external_reference: 'remote-4', remote_state: 'CANCELLED' },
    )

    expect(await findingsAt(NOW)).toEqual([
      {
        kind: 'SPOD_QUARANTINED',
        subject: { type: 'JOB', id: quarantined.jobId },
        orderId: quarantined.orderId,
        since: minutesAgo(600),
        attemptCount: 2,
        lastErrorCode: 'CREATE_TIMEOUT',
      },
      {
        kind: 'SPOD_REMOTE_ATTENTION',
        subject: { type: 'JOB', id: needsAction.jobId },
        orderId: needsAction.orderId,
        since: minutesAgo(50),
        attemptCount: null,
        lastErrorCode: null,
      },
      {
        kind: 'SPOD_REMOTE_ATTENTION',
        subject: { type: 'JOB', id: cancelled.jobId },
        orderId: cancelled.orderId,
        since: minutesAgo(50),
        attemptCount: null,
        lastErrorCode: null,
      },
      {
        kind: 'SPOD_SUBMISSION_STALLED',
        subject: { type: 'JOB', id: unsubmitted.jobId },
        orderId: unsubmitted.orderId,
        since: minutesAgo(61),
        attemptCount: 0,
        lastErrorCode: null,
      },
      {
        kind: 'SPOD_SUBMISSION_STALLED',
        subject: { type: 'JOB', id: failing.jobId },
        orderId: failing.orderId,
        since: minutesAgo(1),
        attemptCount: 3,
        lastErrorCode: 'PLACEMENT_UNAVAILABLE',
      },
    ])
  })

  test('a prepared job nobody ships for seven days', async () => {
    const prepared = (minutes: number) => ({
      created_at: minutesAgo(minutes + 1),
      generated_at: minutesAgo(minutes),
      prepared_at: minutesAgo(minutes),
    })
    const overdue = await seedJob(prepared(7 * DAY_MINUTES + 1))
    await seedJob(prepared(7 * DAY_MINUTES))
    await seedJob({ ...prepared(8 * DAY_MINUTES), shipped_at: minutesAgo(10) })

    expect(await findingsAt(NOW)).toEqual([
      {
        kind: 'SHIPMENT_OVERDUE',
        subject: { type: 'JOB', id: overdue.jobId },
        orderId: overdue.orderId,
        since: minutesAgo(7 * DAY_MINUTES + 1),
        attemptCount: null,
        lastErrorCode: null,
      },
    ])
  })
})
