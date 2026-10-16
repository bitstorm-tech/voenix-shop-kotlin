import { createHash, randomBytes } from 'node:crypto'
import { mkdtemp } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { sql, type Kysely } from 'kysely'
import sharp from 'sharp'
import { Server, utils } from 'ssh2'
import {
  catalogKey,
  type ArticleVariantReference,
  type CatalogVariant,
  type SpodCatalogSource,
  type SpodProductRef,
  type SpodSyncResult,
} from '@/modules/article'
import { createEmailModule, type RenderedEmail } from '@/modules/email'
import type { SpodResult } from '@/modules/spod'
import { createSupplierModule } from '@/modules/supplier'
import type { Tables } from '@/platform/db/database'
import type { Settings } from '@/platform/settings'
import { createProductionWorker } from './delivery/worker'
import { createProductionModule } from './index'
import {
  createOrderLoader,
  type FulfillmentOrder,
  type ProductionData,
  type ProductionLine,
  type ProductionOrderReads,
  type ShippingNotificationOrder,
} from './orders'
import { createArtifactStore } from './pdf/artifact-store'
import { createSpodOrderRepository } from './spod/repository'
import type { SubmissionClient } from './spod/submitter'

/**
 * What the production tests share: the module wired to the real email outbox and supplier lookup
 * plus fakes for everything another module would answer (the order reads, the article catalog,
 * the SPOD client, the catalog sync, the supplier logins), an embedded SFTP server, and the rows a
 * test seeds with SQL because no route of this module writes them. Test-only.
 */

export const WEBHOOK_SECRET = 'spod-webhook-secret-with-32-characters!'
export const ALERT_EMAIL = 'ops@voenix.test'
export const SPOD_SETTINGS = { webhookSecret: WEBHOOK_SECRET, alertEmail: ALERT_EMAIL }

/** Empties every production table and the rows the tests seed next to them. */
export async function resetProduction(db: Kysely<Tables>): Promise<void> {
  await sql`TRUNCATE production_requests, production_destinations, email_jobs, orders, carts, users,
    suppliers RESTART IDENTITY CASCADE`.execute(db)
}

/**
 * An `orders` row, which `production_requests.order_id` references. Its content does not matter:
 * production reads the order through the fake order reads, never from this table.
 */
export async function seedOrder(db: Kysely<Tables>): Promise<number> {
  const token = randomBytes(8).toString('hex')
  const { rows } = await sql<{ id: number }>`WITH cart AS (
      INSERT INTO carts (guest_session_token, status) VALUES (${token}, 'CHECKED_OUT') RETURNING id
    )
    INSERT INTO orders (cart_id, guest_session_token, access_token, status,
      shipping_first_name, shipping_last_name, shipping_street, shipping_house_number,
      shipping_postal_code, shipping_city, shipping_country, billing_first_name, billing_last_name,
      billing_street, billing_house_number, billing_postal_code, billing_city, billing_country,
      email, subtotal_cents, shipping_cost_cents, discount_cents, total_cents)
    SELECT id, ${token}, ${token}, 'PAID', 'E', 'M', 'S', '1', '1', 'C', 'DE', 'E', 'M', 'S', '1',
      '1', 'C', 'DE', 'e@example.test', 0, 0, 0, 0 FROM cart
    RETURNING id`.execute(db)
  return rows[0]?.id ?? 0
}

export async function seedSupplier(db: Kysely<Tables>, name: string): Promise<number> {
  const { rows } = await sql<{ id: number }>`INSERT INTO suppliers (name) VALUES (${name})
    RETURNING id`.execute(db)
  return rows[0]?.id ?? 0
}

/** An SFTP destination row pair, written the way the admin route would. */
export async function seedSftpDestination(
  db: Kysely<Tables>,
  values: {
    supplierId: number
    port: number
    fingerprint: string
    notificationEmail?: string | null
    enabled?: boolean
  },
): Promise<number> {
  const { id } = await db
    .insertInto('production_destinations')
    .values({
      supplier_id: values.supplierId,
      channel: 'SFTP',
      label: 'Hotfolder',
      enabled: values.enabled ?? true,
      notification_email: values.notificationEmail ?? null,
      notification_name: null,
    })
    .returning('id')
    .executeTakeFirstOrThrow()
  await db
    .insertInto('production_destination_sftp')
    .values({
      id,
      host: '127.0.0.1',
      port: values.port,
      username: SFTP_USERNAME,
      password: SFTP_PASSWORD,
      host_key_fingerprint: values.fingerprint,
      remote_path: '/incoming',
      timeout_seconds: 5,
    })
    .execute()
  return id
}

export async function seedSpodDestination(
  db: Kysely<Tables>,
  supplierId: number,
  enabled = true,
): Promise<number> {
  const { id } = await db
    .insertInto('production_destinations')
    .values({ supplier_id: supplierId, channel: 'SPOD', label: 'Spreadconnect', enabled })
    .returning('id')
    .executeTakeFirstOrThrow()
  await db
    .insertInto('production_destination_spod')
    .values({ id, environment: 'STAGING', access_token: 'spod-token', timeout_seconds: 5 })
    .execute()
  return id
}

/** Every queued mail, oldest first. */
export async function queuedEmails(db: Kysely<Tables>): Promise<RenderedEmail[]> {
  const rows = await db.selectFrom('email_jobs').select('payload').orderBy('id').execute()
  return rows.map((row) => row.payload)
}

/** A single-colour image written to `directory`, so no image bytes are checked in. */
export async function writeImage(
  directory: string,
  name: string,
  format: 'png' | 'webp' = 'png',
  width = 200,
  height = 100,
): Promise<string> {
  const path = join(directory, name)
  const image = sharp({ create: { width, height, channels: 3, background: '#d02020' } })
  await (format === 'png' ? image.png() : image.webp()).toFile(path)
  return path
}

/** One order line with mug defaults; a test overrides what it is about. */
export function line(overrides: Partial<ProductionLine> = {}): ProductionLine {
  return {
    articleId: 1,
    variantId: 1,
    articleName: 'Zaubertasse',
    supplierArticleNumber: 'SUP-001',
    variantName: 'Weiß',
    quantity: 1,
    imagePath: null,
    printTemplateWidthMm: null,
    printTemplateHeightMm: null,
    documentFormatWidthMm: null,
    documentFormatHeightMm: null,
    documentFormatMarginBottomMm: null,
    spodHotspot: null,
    ...overrides,
  }
}

export function productionData(orderId: number, lines: ProductionLine[]): ProductionData {
  return {
    orderId,
    orderDate: '2026-07-16',
    customerEmail: 'erika@example.com',
    customerPhone: '+49 30 123456',
    shippingFirstName: 'Erika',
    shippingLastName: 'Mustermann',
    shippingStreet: 'Heidestraße',
    shippingHouseNumber: '17',
    shippingPostalCode: '51147',
    shippingCity: 'Köln',
    shippingCountry: 'Deutschland',
    lines,
  }
}

/**
 * The order module as production sees it: the three reads answered from orders a test adds.
 * `calls` counts the batched header reads, so a test can pin the batching.
 */
export function fakeOrders() {
  const orders = new Map<number, ProductionData>()
  const calls = { fulfillmentOrders: 0 }
  const reads: ProductionOrderReads = {
    productionData: (orderId) => Promise.resolve(orders.get(orderId) ?? null),
    fulfillmentOrders: (orderIds) => {
      calls.fulfillmentOrders++
      const headers = new Map<number, FulfillmentOrder>()
      for (const orderId of orderIds) {
        const order = orders.get(orderId)
        if (order === undefined) continue
        headers.set(orderId, {
          orderId,
          orderDate: order.orderDate,
          customerFirstName: order.shippingFirstName,
          customerLastName: order.shippingLastName,
          shippingStreet: order.shippingStreet,
          shippingHouseNumber: order.shippingHouseNumber,
          shippingPostalCode: order.shippingPostalCode,
          shippingCity: order.shippingCity,
          shippingCountry: order.shippingCountry,
        })
      }
      return Promise.resolve(headers)
    },
    shippingNotificationOrder: (orderId) => {
      const order = orders.get(orderId)
      const answer: ShippingNotificationOrder | null =
        order === undefined
          ? null
          : {
              recipientEmail: order.customerEmail,
              customerFirstName: order.shippingFirstName,
              orderUrl: `https://shop.test/orders/${String(orderId)}?token=secret`,
            }
      return Promise.resolve(answer)
    },
  }
  return { reads, calls, add: (order: ProductionData) => orders.set(order.orderId, order) }
}

/**
 * Today's catalog as production reads it: the supplier, the partner ids, the variant name. A lookup
 * that includes a `break`-marked variant rejects, as a catalog whose database is unreachable does.
 */
export function fakeCatalog() {
  const variants = new Map<string, CatalogVariant>()
  const broken = new Set<string>()
  return {
    find: (references: Iterable<ArticleVariantReference>) =>
      [...references].some((reference) => broken.has(catalogKey(reference)))
        ? Promise.reject(new Error('The article catalog is unreachable'))
        : Promise.resolve(
            new Map(
              [...references].flatMap((reference) => {
                const variant = variants.get(catalogKey(reference))
                return variant === undefined ? [] : [[catalogKey(reference), variant] as const]
              }),
            ),
          ),
    break: (reference: ArticleVariantReference) => broken.add(catalogKey(reference)),
    set: (
      reference: ArticleVariantReference,
      values: { supplierId: number | null; variantName?: string; spodProduct?: SpodProductRef },
    ) => {
      variants.set(catalogKey(reference), {
        articleType: values.spodProduct === undefined ? 'MUG' : 'SPOD',
        articleName: 'Article',
        variantName: values.variantName ?? 'Weiß',
        purchasable: true,
        grossSalesPriceCents: 1999,
        supplierId: values.supplierId,
        supplierArticleNumber: null,
        printTemplateWidthMm: null,
        printTemplateHeightMm: null,
        documentFormatWidthMm: null,
        documentFormatHeightMm: null,
        documentFormatMarginBottomMm: null,
        outsideColorCode: null,
        insideColorCode: null,
        spodProduct: values.spodProduct ?? null,
        spodHotspot: null,
      })
    },
  }
}

/** A SPOD client whose every call fails the test unless the test scripts it. */
export function stubSpodClient(overrides: Partial<SubmissionClient> = {}): SubmissionClient {
  const unexpected = <T>(): Promise<SpodResult<T>> =>
    Promise.reject(new Error('No partner call is expected in this test'))
  return {
    uploadDesign: () => unexpected(),
    createOrder: () => unexpected(),
    getOrder: () => unexpected(),
    confirmOrder: () => unexpected(),
    ...overrides,
  }
}

/** The supplier logins of a test: user id → supplier id. */
export function supplierLogins() {
  const logins = new Map<number, number>()
  return {
    accounts: { supplierIdOf: (userId: number) => Promise.resolve(logins.get(userId) ?? null) },
    bind: (userId: number, supplierId: number) => logins.set(userId, supplierId),
  }
}

/**
 * The production module and, built from the same parts, the worker a test drives scan by scan.
 * `settings.spod` defaults to configured; pass `null` for a deployment without SPOD settings.
 */
export async function productionTestKit(
  db: Kysely<Tables>,
  {
    spodSettings = SPOD_SETTINGS,
    client = stubSpodClient(),
    syncResult = { kind: 'busy' },
  }: {
    spodSettings?: Settings['production']['spod']
    client?: SubmissionClient
    syncResult?: SpodSyncResult
  } = {},
) {
  const root = await mkdtemp(join(tmpdir(), 'production-test-'))
  const settings = { artifactRoot: join(root, 'artifacts'), spod: spodSettings }
  const orders = fakeOrders()
  const catalog = fakeCatalog()
  const logins = supplierLogins()
  const syncCalls: SpodCatalogSource[] = []
  const email = createEmailModule({
    db,
    settings: {
      enabled: false,
      pollIntervalMinutes: 5,
      apiKey: '',
      fromEmail: '',
      fromName: 'Voenix Shop',
    },
    fetch: () => Promise.reject(new Error('No mail is sent in this test')),
  })
  const suppliers = createSupplierModule({
    db,
    countries: { find: () => Promise.resolve(new Map<number, never>()) },
  })
  const module = await createProductionModule({
    db,
    settings,
    email,
    suppliers,
    articleCatalog: catalog,
    spodCatalogSync: {
      sync: (source) => {
        syncCalls.push(source)
        return Promise.resolve(syncResult)
      },
    },
    spod: client,
    accounts: logins.accounts,
    orders: orders.reads,
  })
  const artifacts = await createArtifactStore(settings.artifactRoot)
  const worker = createProductionWorker({
    db,
    loadOrder: createOrderLoader(orders.reads, catalog),
    artifacts,
    outbox: email.outbox,
    orders: orders.reads,
    spodOrders: createSpodOrderRepository({
      db,
      outbox: email.outbox,
      alertEmail: spodSettings?.alertEmail ?? null,
    }),
    spod: client,
  })
  return { module, worker, orders, catalog, logins, syncCalls, artifacts, root }
}

export const SFTP_USERNAME = 'producer'
export const SFTP_PASSWORD = 'secret-word'

/**
 * An in-process SFTP server (ssh2's `Server`) holding the uploaded files in memory: password
 * authentication, a freshly generated ed25519 host key whose `SHA256:` fingerprint tests pin (or
 * deliberately mismatch), and a count of password checks that proves whether a credential was
 * ever presented. It answers the five requests an upload makes; with `silentSessions` it
 * authenticates and then never answers the session request, a server stuck before the subsystem.
 */
export async function startSftpServer({ silentSessions = false } = {}) {
  const keys = utils.generateKeyPairSync('ed25519')
  const parsed = utils.parseKey(keys.private)
  if (parsed instanceof Error) throw parsed
  const fingerprint = `SHA256:${createHash('sha256')
    .update(parsed.getPublicSSH())
    .digest('base64')
    .replace(/=+$/, '')}`
  const files = new Map<string, Buffer>()
  let passwordChecks = 0
  const server = new Server({ hostKeys: [keys.private] }, (connection) => {
    connection
      .on('authentication', (context) => {
        if (context.method !== 'password') {
          context.reject(['password'])
          return
        }
        passwordChecks++
        if (context.username === SFTP_USERNAME && context.password === SFTP_PASSWORD) {
          context.accept()
        } else {
          context.reject(['password'])
        }
      })
      .on('ready', () => {
        connection.on('session', (acceptSession) => {
          if (silentSessions) return
          acceptSession().on('sftp', (acceptSftp) => {
            const sftp = acceptSftp()
            const handles = new Map<string, string>()
            let next = 0
            sftp
              .on('OPEN', (request, path) => {
                const handle = Buffer.from(String(next++))
                handles.set(handle.toString(), path)
                files.set(path, Buffer.alloc(0))
                sftp.handle(request, handle)
              })
              .on('WRITE', (request, handle, offset, data) => {
                const path = handles.get(handle.toString()) ?? ''
                const current = files.get(path) ?? Buffer.alloc(0)
                const grown = Buffer.alloc(Math.max(current.length, offset + data.length))
                current.copy(grown)
                data.copy(grown, offset)
                files.set(path, grown)
                sftp.status(request, 0)
              })
              .on('CLOSE', (request, handle) => {
                handles.delete(handle.toString())
                sftp.status(request, 0)
              })
              .on('REMOVE', (request, path) => {
                sftp.status(request, files.delete(path) ? 0 : 2)
              })
              .on('RENAME', (request, from, to) => {
                const data = files.get(from)
                if (data === undefined) {
                  sftp.status(request, 2)
                  return
                }
                files.delete(from)
                files.set(to, data)
                sftp.status(request, 0)
              })
          })
        })
      })
      .on('error', () => undefined)
  })
  await new Promise<void>((resolve) => {
    server.listen(0, '127.0.0.1', resolve)
  })
  const address = server.address()
  const port = typeof address === 'object' && address !== null ? address.port : 0
  return {
    port,
    fingerprint,
    files,
    passwordChecks: () => passwordChecks,
    close: () =>
      new Promise<void>((resolve) => {
        server.close(() => {
          resolve()
        })
      }),
  }
}
