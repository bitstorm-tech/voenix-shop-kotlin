import { afterAll, beforeAll, beforeEach, describe, expect, test } from 'bun:test'
import { readFile, writeFile } from 'node:fs/promises'
import { createServer, type Server as NetServer } from 'node:net'
import { join } from 'node:path'
import { write } from '@/platform/db/database'
import { testDatabase } from '@test-support/database'
import { rejectionOf } from '@test-support/values'
import {
  line,
  productionData,
  productionTestKit,
  queuedEmails,
  resetProduction,
  seedOrder,
  seedSftpDestination,
  seedSpodDestination,
  seedSupplier,
  SFTP_PASSWORD,
  SFTP_USERNAME,
  startSftpServer,
  writeImage,
} from '../test-fixtures'
import { sha256Hex } from '../pdf/renderer'
import { createDeliveryStage } from './deliverer'
import { requestProduction } from './requests'
import { deliverOverSftp, type SftpDestination } from './sftp'

const database = testDatabase()
let server: Awaited<ReturnType<typeof startSftpServer>>

beforeAll(async () => {
  server = await startSftpServer()
})
afterAll(async () => {
  await server.close()
})

let kit: Awaited<ReturnType<typeof productionTestKit>>
let image = ''
let supplierA = 0
let supplierB = 0

beforeEach(async () => {
  await resetProduction(database.db)
  server.files.clear()
  kit = await productionTestKit(database.db)
  image = await writeImage(kit.root, 'print.png')
  supplierA = await seedSupplier(database.db, 'Tassenwerk')
  supplierB = await seedSupplier(database.db, 'Becherhaus')
  kit.catalog.set({ articleId: 1, variantId: 1 }, { supplierId: supplierA })
  kit.catalog.set({ articleId: 2, variantId: 2 }, { supplierId: supplierB })
})

/** A paid order of three lines (two for supplier A, one for B), requested for production. */
async function requestOrder(lines = defaultLines()) {
  const orderId = await seedOrder(database.db)
  kit.orders.add(productionData(orderId, lines))
  await write(database.db, (trx) => requestProduction(trx, orderId))
  return orderId
}

const defaultLines = () => [
  line({ imagePath: image, quantity: 2 }),
  line({ articleId: 2, variantId: 2, imagePath: image, articleName: 'Becher', variantName: 'Rot' }),
  line({ imagePath: image, variantName: 'Schwarz', supplierArticleNumber: ' ' }),
]

const jobs = () =>
  database.db
    .selectFrom('production_jobs')
    .select([
      'id',
      'supplier_id as supplierId',
      'fulfillment_channel as channel',
      'content_sha256 as sha256',
      'generation_attempt_count as attempts',
      'prepared_at as preparedAt',
    ])
    .orderBy('id')
    .execute()

const deliveries = () =>
  database.db
    .selectFrom('production_deliveries')
    .select([
      'destination_id as destinationId',
      'attempt_count as attempts',
      'last_error_code as code',
      'delivered_at as deliveredAt',
    ])
    .orderBy('id')
    .execute()

const requestState = () =>
  database.db
    .selectFrom('production_requests')
    .select(['attempt_count as attempts', 'last_error_code as code', 'processed_at as processedAt'])
    .executeTakeFirstOrThrow()

describe('requestProduction', () => {
  test('one row per order: repeated and concurrent calls answer the same id', async () => {
    const orderId = await seedOrder(database.db)
    const first = await write(database.db, (trx) => requestProduction(trx, orderId))
    const [second, third] = await Promise.all([
      write(database.db, (trx) => requestProduction(trx, orderId)),
      write(database.db, (trx) => requestProduction(trx, orderId)),
    ])
    expect([second, third]).toEqual([first, first])
    expect(
      await database.db.selectFrom('production_requests').select('order_id').execute(),
    ).toEqual([{ order_id: orderId }])
  })

  test('it joins the caller transaction', async () => {
    const orderId = await seedOrder(database.db)
    expect(
      await rejectionOf(
        write(database.db, async (trx) => {
          await requestProduction(trx, orderId)
          throw new Error('the paid transition rolls back')
        }),
      ),
    ).toBe('the paid transition rolls back')
    expect(await database.db.selectFrom('production_requests').select('id').execute()).toEqual([])
  })
})

describe('the production worker', () => {
  test('processes a paid order end to end into the embedded SFTP server, once', async () => {
    const destinationId = await seedSftpDestination(database.db, {
      supplierId: supplierA,
      port: server.port,
      fingerprint: server.fingerprint,
      notificationEmail: 'producer@example.test',
    })
    const orderId = await requestOrder()

    await kit.worker.runOnce()

    expect(await requestState()).toMatchObject({ attempts: 1, code: null })
    expect((await requestState()).processedAt).not.toBeNull()
    const [jobA, jobB] = await jobs()
    expect([jobA?.supplierId, jobA?.channel, jobB?.supplierId, jobB?.channel]).toEqual([
      supplierA,
      'SFTP',
      supplierB,
      'SFTP',
    ])
    expect(
      await database.db
        .selectFrom('production_job_items')
        .select([
          'production_job_id as jobId',
          'position',
          'variant_name as variant',
          'supplier_article_number as number',
          'quantity',
        ])
        .orderBy('production_job_id')
        .orderBy('position')
        .execute(),
    ).toEqual([
      { jobId: jobA?.id ?? 0, position: 1, variant: 'Weiß', number: 'SUP-001', quantity: 2 },
      { jobId: jobA?.id ?? 0, position: 2, variant: 'Schwarz', number: null, quantity: 1 },
      { jobId: jobB?.id ?? 0, position: 1, variant: 'Rot', number: 'SUP-001', quantity: 1 },
    ])

    const artifact = await readFile(
      join(kit.root, 'artifacts', String(jobA?.id), `ORD-${String(orderId)}.pdf`),
    )
    expect(jobA?.sha256).toBe(sha256Hex(artifact))
    expect(jobA?.preparedAt).not.toBeNull()
    expect([...server.files.keys()]).toEqual([`/incoming/ORD-${String(orderId)}.pdf`])
    expect(
      sha256Hex(server.files.get(`/incoming/ORD-${String(orderId)}.pdf`) ?? new Uint8Array()),
    ).toBe(jobA?.sha256 ?? '')
    expect(await deliveries()).toMatchObject([{ destinationId, attempts: 1, code: null }])
    const mails = await queuedEmails(database.db)
    expect(mails.map((mail) => [mail.kind, mail.recipient])).toEqual([
      ['PRODUCER_PDF_NOTIFICATION', 'producer@example.test'],
    ])
    expect(mails[0]?.text).toContain('Anzahl Artikel: 3')
    expect(mails[0]?.text).toContain('16.07.2026')

    // A second scan changes nothing, not even with a changed image under the same path.
    await writeImage(kit.root, 'print.png', 'png', 50, 50)
    await kit.worker.runOnce()
    expect(await jobs()).toEqual([jobA, jobB].filter((job) => job !== undefined))
    expect(await deliveries()).toMatchObject([{ attempts: 1 }])
    expect((await queuedEmails(database.db)).length).toBe(1)
    expect(
      sha256Hex(
        await readFile(join(kit.root, 'artifacts', String(jobA?.id), `ORD-${String(orderId)}.pdf`)),
      ),
    ).toBe(jobA?.sha256 ?? '')
  })

  test('a SPOD supplier gets a SPOD job without deliveries that the generation never touches', async () => {
    await seedSpodDestination(database.db, supplierB)
    await seedSftpDestination(database.db, { supplierId: supplierB, port: 1, fingerprint: 'x' })
    await requestOrder()
    await kit.worker.runOnce()
    const [jobA, jobB] = await jobs()
    expect([jobA?.channel, jobB?.channel]).toEqual(['SFTP', 'SPOD'])
    expect(jobB).toMatchObject({ sha256: null, attempts: 0 })
    expect(await deliveries()).toEqual([])
  })

  test('source problems keep the request open with a bounded code until they heal', async () => {
    kit.catalog.set({ articleId: 2, variantId: 2 }, { supplierId: null })
    await requestOrder()
    await kit.worker.runOnce()
    expect(await requestState()).toMatchObject({
      attempts: 1,
      code: 'ITEM_WITHOUT_SUPPLIER',
      processedAt: null,
    })
    expect(await jobs()).toEqual([])

    kit.catalog.set({ articleId: 2, variantId: 2 }, { supplierId: supplierB })
    await kit.worker.runOnce()
    expect(await requestState()).toMatchObject({ attempts: 2, code: null })
    expect((await jobs()).length).toBe(2)

    const unknown = await seedOrder(database.db)
    await write(database.db, (trx) => requestProduction(trx, unknown))
    await kit.worker.runOnce()
    expect(
      await database.db
        .selectFrom('production_requests')
        .select('last_error_code')
        .where('order_id', '=', unknown)
        .executeTakeFirstOrThrow(),
    ).toEqual({ last_error_code: 'SOURCE_NOT_FOUND' })
  })

  test('a generation failure records its code and the job recovers', async () => {
    await requestOrder([line({ imagePath: join(kit.root, 'not-yet.png') })])
    await kit.worker.runOnce()
    expect(
      await database.db
        .selectFrom('production_jobs')
        .select(['generation_attempt_count as attempts', 'last_generation_error_code as code'])
        .execute(),
    ).toEqual([{ attempts: 1, code: 'MISSING_IMAGE' }])
    await writeFile(join(kit.root, 'not-yet.png'), await readFile(image))
    await kit.worker.runOnce()
    expect((await jobs())[0]).toMatchObject({ attempts: 2 })
    expect((await jobs())[0]?.sha256).not.toBeNull()
  })

  test('a failed and a disabled destination stay open with their codes and recover', async () => {
    const destinationId = await seedSftpDestination(database.db, {
      supplierId: supplierA,
      port: 1,
      fingerprint: server.fingerprint,
    })
    await requestOrder([line({ imagePath: image })])
    await kit.worker.runOnce()
    expect(await deliveries()).toMatchObject([{ attempts: 1, code: 'CONNECTION_FAILED' }])

    const destinations = database.db.updateTable('production_destinations')
    await destinations.set({ enabled: false }).where('id', '=', destinationId).execute()
    await kit.worker.runOnce()
    expect(await deliveries()).toMatchObject([{ attempts: 2, code: 'DESTINATION_DISABLED' }])

    await destinations.set({ enabled: true }).where('id', '=', destinationId).execute()
    await database.db
      .updateTable('production_destination_sftp')
      .set({ port: server.port })
      .where('id', '=', destinationId)
      .execute()
    await kit.worker.runOnce()
    expect(await deliveries()).toMatchObject([{ attempts: 3, code: null }])
    expect((await deliveries())[0]?.deliveredAt).not.toBeNull()
  })

  test('a failing notification enqueue rolls delivered_at back, and the next scan recovers', async () => {
    await seedSftpDestination(database.db, {
      supplierId: supplierA,
      port: server.port,
      fingerprint: server.fingerprint,
      notificationEmail: 'producer@example.test',
    })
    await requestOrder([line({ imagePath: image })])
    await kit.worker.runOnce()
    await database.db.updateTable('production_deliveries').set({ delivered_at: null }).execute()
    await database.db.deleteFrom('email_jobs').execute()

    const failing = createDeliveryStage({
      db: database.db,
      artifacts: kit.artifacts,
      outbox: { enqueue: () => Promise.reject(new Error('the outbox is down')) },
      orders: kit.orders.reads,
    })
    expect(await rejectionOf(failing())).toBe('the outbox is down')
    expect(await deliveries()).toMatchObject([{ deliveredAt: null }])

    await kit.worker.runOnce()
    expect((await deliveries())[0]?.deliveredAt).not.toBeNull()
    expect((await queuedEmails(database.db)).length).toBe(1)
  })
})

describe('deliverOverSftp', () => {
  const destination = (overrides: Partial<SftpDestination> = {}): SftpDestination => ({
    id: 1,
    host: '127.0.0.1',
    port: server.port,
    username: SFTP_USERNAME,
    password: SFTP_PASSWORD,
    hostKeyFingerprint: server.fingerprint,
    remotePath: '/out/',
    timeoutSeconds: 5,
    ...overrides,
  })
  const bytes = new TextEncoder().encode('%PDF-1.7 artifact')

  beforeEach(() => {
    server.files.clear()
  })

  test('uploads under the final name, replacing an earlier file and a stale temp file', async () => {
    server.files.set('/out/ORD-7.pdf', Buffer.from('old'))
    server.files.set('/out/ORD-7.pdf.part', Buffer.from('stale'))
    expect(await deliverOverSftp(destination(), 'ORD-7.pdf', bytes)).toBeNull()
    expect([...server.files]).toEqual([['/out/ORD-7.pdf', Buffer.from(bytes)]])
  })

  test('a host key that does not match the pin is refused before any credential is sent', async () => {
    const checks = server.passwordChecks()
    for (const fingerprint of ['SHA256:AAAA', '', 'MD5:' + server.fingerprint.slice(7)]) {
      expect(
        await deliverOverSftp(destination({ hostKeyFingerprint: fingerprint }), 'ORD-7.pdf', bytes),
      ).toBe('HOST_KEY_REJECTED')
    }
    expect(server.passwordChecks()).toBe(checks)
    expect(
      await deliverOverSftp(
        destination({ hostKeyFingerprint: server.fingerprint.slice(7) }),
        'ORD-7.pdf',
        bytes,
      ),
    ).toBeNull()
  })

  test('a wrong password, a closed port, and a silent server are bounded codes', async () => {
    expect(await deliverOverSftp(destination({ password: 'wrong' }), 'ORD-7.pdf', bytes)).toBe(
      'AUTH_FAILED',
    )
    expect(server.files.size).toBe(0)

    const closed = createServer()
    await new Promise<void>((resolve) => closed.listen(0, '127.0.0.1', resolve))
    const closedPort = portOf(closed)
    await new Promise<void>((resolve) =>
      closed.close(() => {
        resolve()
      }),
    )
    expect(await deliverOverSftp(destination({ port: closedPort }), 'ORD-7.pdf', bytes)).toBe(
      'CONNECTION_FAILED',
    )

    const silent = createServer(() => undefined)
    await new Promise<void>((resolve) => silent.listen(0, '127.0.0.1', resolve))
    expect(
      await deliverOverSftp(
        destination({ port: portOf(silent), timeoutSeconds: 1 }),
        'ORD-7.pdf',
        bytes,
      ),
    ).toBe('CONNECTION_FAILED')
    silent.close()
  })
})

function portOf(netServer: NetServer): number {
  const address = netServer.address()
  return typeof address === 'object' && address !== null ? address.port : 0
}
