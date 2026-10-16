import { beforeEach, describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { testDatabase } from '@test-support/database'
import { jsonOf, signedInHeaders, testApp } from '@test-support/http'
import { createFindingRepository, type Finding } from './repository'
import { operationsRoutes } from './routes'

const database = testDatabase()
const PATH = '/api/admin/operations/findings'

const SINCE = new Date('2026-10-16T10:00:00.000Z')

const finding = (kind: Finding['kind'], id: number): Finding =>
  kind === 'DELIVERY_STALLED'
    ? {
        kind,
        subject: { type: 'DELIVERY', id },
        orderId: 42,
        since: SINCE,
        attemptCount: 3,
        lastErrorCode: 'SFTP_CONNECT_FAILED',
      }
    : {
        kind: 'SHIPMENT_OVERDUE',
        subject: { type: 'JOB', id },
        orderId: 43,
        since: SINCE,
        attemptCount: null,
        lastErrorCode: null,
      }

const app = () => testApp(operationsRoutes(createFindingRepository(database.db)))
const get = async (path: string, roles = ['ADMIN']) =>
  app().request(path, { headers: await signedInHeaders({ roles }) })

beforeEach(async () => {
  await sql`TRUNCATE operations_findings RESTART IDENTITY`.execute(database.db)
})

describe('GET /api/admin/operations/findings', () => {
  test('is for admins only and never cached', async () => {
    expect((await app().request(PATH)).status).toBe(401)
    const customer = await get(PATH, ['USER'])
    expect(customer.status).toBe(403)
    const admin = await get(PATH)
    expect(admin.status).toBe(200)
    expect(admin.headers.get('Cache-Control')).toBe('no-store')
    expect(await jsonOf(admin)).toEqual([])
  })

  test('lists the open findings, newest first seen first', async () => {
    const repository = createFindingRepository(database.db)
    await repository.record([finding('DELIVERY_STALLED', 2)])
    await repository.record([finding('DELIVERY_STALLED', 2), finding('SHIPMENT_OVERDUE', 3)])

    expect(await jsonOf(await get(PATH))).toEqual([
      {
        kind: 'SHIPMENT_OVERDUE',
        subject: { type: 'JOB', id: 3 },
        orderId: 43,
        since: SINCE.toISOString(),
        attemptCount: null,
        lastErrorCode: null,
        firstSeenAt: expect.any(String) as unknown,
        alertedAt: null,
      },
      {
        kind: 'DELIVERY_STALLED',
        subject: { type: 'DELIVERY', id: 2 },
        orderId: 42,
        since: SINCE.toISOString(),
        attemptCount: 3,
        lastErrorCode: 'SFTP_CONNECT_FAILED',
        firstSeenAt: expect.any(String) as unknown,
        alertedAt: null,
      },
    ])
  })

  test('with resolved=true lists the findings resolved within seven days, with resolvedAt', async () => {
    const repository = createFindingRepository(database.db)
    await repository.record([finding('DELIVERY_STALLED', 2), finding('SHIPMENT_OVERDUE', 3)])
    await repository.record([finding('SHIPMENT_OVERDUE', 3)])
    await repository.record([finding('DELIVERY_STALLED', 4)])
    // Resolved eight days ago: out of the list.
    await database.db
      .updateTable('operations_findings')
      .set({ resolved_at: sql<Date>`now() - interval '8 days'` })
      .where('subject_id', '=', 2)
      .execute()

    expect(await jsonOf(await get(`${PATH}?resolved=true`))).toEqual([
      {
        kind: 'SHIPMENT_OVERDUE',
        subject: { type: 'JOB', id: 3 },
        orderId: 43,
        since: SINCE.toISOString(),
        attemptCount: null,
        lastErrorCode: null,
        firstSeenAt: expect.any(String) as unknown,
        alertedAt: null,
        resolvedAt: expect.any(String) as unknown,
      },
    ])
    expect(await jsonOf(await get(PATH))).toMatchObject([{ subject: { type: 'DELIVERY', id: 4 } }])
  })
})
