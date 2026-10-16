import { beforeEach, describe, expect, test } from 'bun:test'
import type { SpodSyncReport } from '@/modules/article'
import { testDatabase } from '@test-support/database'
import { jsonOf, signedInHeaders, testApp } from '@test-support/http'
import { rejectionOf } from '@test-support/values'
import {
  productionTestKit,
  resetProduction,
  seedOrder,
  seedSftpDestination,
  seedSpodDestination,
  seedSupplier,
} from './test-fixtures'

const database = testDatabase()
const PATH = '/api/admin/production/destinations'

let supplierId = 0

beforeEach(async () => {
  await resetProduction(database.db)
  supplierId = await seedSupplier(database.db, 'Druckerei')
})

async function adminApp(options: Parameters<typeof productionTestKit>[1] = {}) {
  const kit = await productionTestKit(database.db, options)
  const headers = await signedInHeaders()
  const app = testApp(kit.module.routes)
  const call = (method: string, path: string, body?: unknown) =>
    app.request(path, {
      method,
      headers: { ...headers, 'Content-Type': 'application/json' },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    })
  return { kit, call }
}

const withoutPassword = {
  host: 'sftp.example.test',
  username: 'producer',
  hostKeyFingerprint: 'SHA256:abc',
  timeoutSeconds: 30,
}

const sftpBody = (overrides: Record<string, unknown> = {}) => ({
  supplierId,
  channel: 'SFTP',
  label: 'Hotfolder',
  notificationEmail: 'producer@example.test',
  sftp: { ...withoutPassword, password: 'hunter2' },
  ...overrides,
})

const spodBody = (overrides: Record<string, unknown> = {}) => ({
  supplierId,
  channel: 'SPOD',
  label: 'Spreadconnect',
  spod: { environment: 'STAGING', accessToken: 'spod-token', timeoutSeconds: 30 },
  ...overrides,
})

const secrets = () =>
  database.db
    .selectFrom('production_destinations as d')
    .leftJoin('production_destination_sftp as f', 'f.id', 'd.id')
    .leftJoin('production_destination_spod as s', 's.id', 'd.id')
    .select(['d.id', 'f.password', 's.access_token'])
    .orderBy('d.id')
    .execute()

describe('destination CRUD', () => {
  test('create, list, read, replace, disable, and delete an SFTP destination', async () => {
    const { call } = await adminApp()
    const created = await call('POST', PATH, sftpBody())
    expect(created.status).toBe(201)
    const destination = {
      id: 1,
      supplierId,
      channel: 'SFTP',
      label: 'Hotfolder',
      enabled: true,
      notificationEmail: 'producer@example.test',
      notificationName: null,
      sftp: {
        host: 'sftp.example.test',
        port: 22,
        username: 'producer',
        hostKeyFingerprint: 'SHA256:abc',
        remotePath: '/',
        timeoutSeconds: 30,
      },
      spod: null,
    }
    expect(created.headers.get('Location')).toBe(`${PATH}/1`)
    expect(await jsonOf(created)).toEqual(destination)
    expect(await jsonOf(await call('GET', PATH))).toEqual([destination])
    expect(await jsonOf(await call('GET', `${PATH}/1`))).toEqual(destination)

    // A replace without a password keeps the stored one; enabled: false is the off-switch.
    const replaced = await call(
      'PUT',
      `${PATH}/1`,
      sftpBody({ enabled: false, sftp: { ...withoutPassword, port: 2222 } }),
    )
    expect(replaced.status).toBe(200)
    expect(await jsonOf(replaced)).toMatchObject({ enabled: false, sftp: { port: 2222 } })
    expect(await secrets()).toEqual([{ id: 1, password: 'hunter2', access_token: null }])

    expect((await call('DELETE', `${PATH}/1`)).status).toBe(204)
    expect((await call('GET', `${PATH}/1`)).status).toBe(404)
    expect(await jsonOf(await call('DELETE', `${PATH}/1`))).toEqual({
      message: 'Production destination not found',
      errors: {},
    })
  })

  test('a SPOD destination never answers its token, and a new token replaces it', async () => {
    const { call } = await adminApp()
    const created = await jsonOf(await call('POST', PATH, spodBody()))
    expect(created).toMatchObject({
      channel: 'SPOD',
      sftp: null,
      spod: { environment: 'STAGING', timeoutSeconds: 30 },
    })
    expect(JSON.stringify(created)).not.toContain('spod-token')
    await call(
      'PUT',
      `${PATH}/1`,
      spodBody({ spod: { environment: 'PRODUCTION', accessToken: ' new ', timeoutSeconds: 5 } }),
    )
    expect(await secrets()).toEqual([{ id: 1, password: null, access_token: ' new ' }])
  })

  test('the write rules the schema cannot check are field errors', async () => {
    const { call } = await adminApp()
    expect(await jsonOf(await call('POST', PATH, sftpBody({ sftp: withoutPassword })))).toEqual({
      message: 'Validation failed',
      errors: { 'sftp.password': ['Password is required'] },
    })
    expect(
      await jsonOf(
        await call('POST', PATH, spodBody({ spod: { environment: 'STAGING', timeoutSeconds: 5 } })),
      ),
    ).toMatchObject({ errors: { 'spod.accessToken': ['AccessToken is required'] } })
    expect(await jsonOf(await call('POST', PATH, sftpBody({ supplierId: 999 })))).toMatchObject({
      errors: { supplierId: ['Supplier not found'] },
    })

    const { id } = (await (await call('POST', PATH, sftpBody())).json()) as { id: number }
    const path = `${PATH}/${String(id)}`
    const otherSupplier = await seedSupplier(database.db, 'Andere')
    expect(await jsonOf(await call('PUT', path, spodBody()))).toMatchObject({
      errors: { channel: ['Channel cannot be changed after creation'] },
    })
    expect(
      await jsonOf(await call('PUT', path, sftpBody({ supplierId: otherSupplier }))),
    ).toMatchObject({ errors: { supplierId: ['Supplier cannot be changed after creation'] } })
    expect((await call('PUT', `${PATH}/99`, sftpBody())).status).toBe(404)
  })

  test('a second enabled SPOD destination of a supplier is refused, also when two race', async () => {
    const { call } = await adminApp()
    const [first, second] = await Promise.all([
      call('POST', PATH, spodBody()),
      call('POST', PATH, spodBody()),
    ])
    expect([first.status, second.status].sort()).toEqual([201, 400])
    const refused = await jsonOf(first.status === 400 ? first : second)
    expect(refused).toEqual({
      message: 'Validation failed',
      errors: { channel: ['Supplier already has an enabled SPOD destination; disable it first'] },
    })
    expect(await jsonOf(await call('POST', PATH, spodBody()))).toEqual(refused)
    expect((await call('POST', PATH, spodBody({ enabled: false }))).status).toBe(201)
    expect((await secrets()).length).toBe(2)
  })

  test('a destination with delivery history cannot be deleted', async () => {
    const { call } = await adminApp()
    const destinationId = await seedSftpDestination(database.db, {
      supplierId,
      port: 22,
      fingerprint: 'SHA256:x',
    })
    const { id: requestId } = await database.db
      .insertInto('production_requests')
      .values({ order_id: await seedOrder(database.db) })
      .returning('id')
      .executeTakeFirstOrThrow()
    const { id: jobId } = await database.db
      .insertInto('production_jobs')
      .values({
        request_id: requestId,
        supplier_id: supplierId,
        fulfillment_channel: 'SFTP',
        file_name: 'ORD-1.pdf',
      })
      .returning('id')
      .executeTakeFirstOrThrow()
    await database.db
      .insertInto('production_deliveries')
      .values({ production_job_id: jobId, destination_id: destinationId })
      .execute()
    const refused = await call('DELETE', `${PATH}/${String(destinationId)}`)
    expect(refused.status).toBe(409)
    expect(await jsonOf(refused)).toEqual({
      message: 'Production destination is in use and cannot be deleted; disable it instead',
      errors: {},
    })
  })
})

describe('a deployment without SPOD settings', () => {
  test('refuses to store a SPOD destination, enabled or not', async () => {
    const { call } = await adminApp({ spodSettings: null })
    expect(await jsonOf(await call('POST', PATH, spodBody({ enabled: false })))).toEqual({
      message: 'Validation failed',
      errors: {
        channel: [
          'This deployment has no production.spod configuration, so no SPOD destination can be stored',
        ],
      },
    })
    expect((await call('POST', PATH, sftpBody())).status).toBe(201)
  })

  test('refuses to start once a SPOD destination exists', async () => {
    await seedSpodDestination(database.db, supplierId, false)
    expect(await rejectionOf(productionTestKit(database.db, { spodSettings: null }))).toBe(
      'A print-on-demand destination exists, so production.spod.webhookSecret and ' +
        'operations.alertEmail are required',
    )
    expect((await productionTestKit(database.db)).module.routes).toBeDefined()
  })
})

describe('POST …/{id}/sync-articles', () => {
  const report = { destinationId: 1, status: 'COMPLETED', created: [] } as unknown as SpodSyncReport

  test('a SPOD destination, also a disabled one, hands its source to the sync and answers the report', async () => {
    const { kit, call } = await adminApp({ syncResult: { kind: 'reported', report } })
    const destinationId = await seedSpodDestination(database.db, supplierId, false)
    const answer = await call('POST', `${PATH}/${String(destinationId)}/sync-articles`)
    expect(answer.status).toBe(200)
    expect(await jsonOf(answer)).toEqual(report)
    expect(kit.syncCalls).toEqual([
      {
        supplierId,
        access: {
          destinationId,
          environment: 'STAGING',
          accessToken: 'spod-token',
          timeoutSeconds: 5,
        },
      },
    ])
  })

  test('unknown, without a catalog, and already running', async () => {
    const { call } = await adminApp({ syncResult: { kind: 'busy' } })
    expect((await call('POST', `${PATH}/99/sync-articles`)).status).toBe(404)
    const sftpId = await seedSftpDestination(database.db, {
      supplierId,
      port: 22,
      fingerprint: 'x',
    })
    const withoutCatalog = await call('POST', `${PATH}/${String(sftpId)}/sync-articles`)
    expect(withoutCatalog.status).toBe(409)
    expect(await jsonOf(withoutCatalog)).toEqual({
      message: 'Only print-on-demand destinations have a SPOD catalog to sync',
      errors: {},
      code: 'CHANNEL_WITHOUT_CATALOG',
    })
    const spodId = await seedSpodDestination(database.db, supplierId)
    const running = await call('POST', `${PATH}/${String(spodId)}/sync-articles`)
    expect(running.status).toBe(409)
    expect(await jsonOf(running)).toMatchObject({ code: 'SYNC_RUNNING' })
  })
})
