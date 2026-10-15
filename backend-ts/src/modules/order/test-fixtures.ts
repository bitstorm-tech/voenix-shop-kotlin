import { mkdtemp } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { sql, type Kysely } from 'kysely'
import sharp from 'sharp'
import { catalogKey, type ArticleVariantReference, type CatalogVariant } from '@/modules/article'
import { createEmailModule } from '@/modules/email'
import { createImageModule, type ImageStorage } from '@/modules/image'
import { createPromotionModule } from '@/modules/promotion'
import { createAuth } from '@/platform/auth/auth'
import { authCookies, GUEST_COOKIE } from '@/platform/auth/cookies'
import { write, type Tables } from '@/platform/db/database'
import type { Settings } from '@/platform/settings'
import { createOrderModule } from './index'
import type { MolliePayment, MolliePayments } from './payment/mollie'
import type { OrderAddress, PayableOrder, PlaceOrderInput } from './repository'
import type { PlaceOrderResult } from './service'

/**
 * What the order tests share: the module wired to the real promotion codes, email outbox, and
 * private image storage (in a temporary root), a fake article catalog and a Mollie stub, and the
 * rows a test seeds with SQL because no route of this module writes them. Test-only.
 */

export const WEBHOOK_SECRET = 'webhook-secret-0123456789'
export const MOLLIE_SETTINGS = {
  apiKey: 'test_mollie_key',
  redirectUrl: 'https://voenix.test/checkout/success',
  webhookUrl: `https://voenix.test/api/payments/webhook/${WEBHOOK_SECRET}`,
  webhookSecret: WEBHOOK_SECRET,
} satisfies Settings['mollie']
export const FRONTEND_BASE_URL = 'https://shop.voenix.test'
// The secret `testAuth` of test-support/http.ts seals with, so its sessions and these guest
// cookies are read by the same keys.
const TEST_SESSION_SECRET = 'test-session-secret-with-at-least-32-bytes'
const guestCookies = authCookies(TEST_SESSION_SECRET).guest
const { guestTokens } = createAuth({ sessionSecret: TEST_SESSION_SECRET })

export const MUG: CatalogVariant = {
  articleType: 'MUG',
  articleName: 'Tasse Klassik',
  variantName: 'Weiß/Blau',
  purchasable: true,
  grossSalesPriceCents: 1490,
  supplierId: 3,
  supplierArticleNumber: 'MUG-001',
  printTemplateWidthMm: 200,
  printTemplateHeightMm: 80,
  documentFormatWidthMm: 239,
  documentFormatHeightMm: 99,
  documentFormatMarginBottomMm: 5,
  outsideColorCode: '#ffffff',
  insideColorCode: '#0000ff',
  spodProduct: null,
  spodHotspot: null,
}

export const SPOD: CatalogVariant = {
  ...MUG,
  articleType: 'SPOD',
  articleName: 'Shirt Organic',
  variantName: 'Black / M',
  supplierArticleNumber: null,
  printTemplateWidthMm: null,
  printTemplateHeightMm: null,
  documentFormatWidthMm: null,
  documentFormatHeightMm: null,
  documentFormatMarginBottomMm: null,
  outsideColorCode: null,
  insideColorCode: null,
  spodProduct: { productTypeId: 812, appearanceId: 2, sizeId: 3 },
  spodHotspot: 'MEDIUM_FRONT',
}

/** The catalog the tests place against: article 1/variant 1 is a mug, 2/5 a SPOD shirt. */
export const catalog = new Map<string, CatalogVariant>([
  [catalogKey({ articleId: 1, variantId: 1 }), MUG],
  [catalogKey({ articleId: 2, variantId: 5 }), SPOD],
])
const articleCatalog = {
  find: (references: Iterable<ArticleVariantReference>) => {
    const found = new Map<string, CatalogVariant>()
    for (const reference of references) {
      const variant = catalog.get(catalogKey(reference))
      if (variant !== undefined) found.set(catalogKey(reference), variant)
    }
    return Promise.resolve(found)
  },
}

export const ADDRESS: OrderAddress = {
  firstName: 'Erika',
  lastName: 'Musterfrau',
  street: 'Lieferweg',
  houseNumber: '5',
  postalCode: '20095',
  city: 'Hamburg',
  country: 'DE',
}

/** A mug order of two at 14.90 € plus a 5 € prompt, 4.90 € shipping: 44.70 €. */
export function placeInput(
  cartId: number,
  overrides: Partial<PlaceOrderInput> = {},
): PlaceOrderInput {
  return {
    cartId,
    userId: null,
    guestToken: 'guest-a',
    promotionId: null,
    shippingAddress: ADDRESS,
    billingAddress: null,
    email: 'kunde@example.com',
    phone: '+4917623123456',
    subtotalCents: 3980,
    shippingCostCents: 490,
    discountCents: 0,
    lines: [
      {
        articleId: 1,
        variantId: 1,
        quantity: 2,
        priceCents: 1490,
        promptPriceCents: 500,
        promptId: null,
        printImageId: null,
      },
    ],
    ...overrides,
  }
}

/**
 * A Mollie that records its calls. `create` answers an `OPEN` payment for the order's total
 * (or `null` while `refuse` is set) after yielding, so two concurrent starts really overlap;
 * `find` answers what `reported` holds, which a test edits to play Mollie's side.
 */
export function stubMollie() {
  const reported = new Map<string, MolliePayment>()
  const calls = { create: 0, find: 0, cancel: [] as string[] }
  const state = { refuse: false }
  const mollie: MolliePayments = {
    create: async (order) => {
      calls.create += 1
      await Bun.sleep(50)
      if (state.refuse) return null
      const id = `tr_${String(order.orderId)}_${String(calls.create)}`
      const payment: MolliePayment = {
        id,
        status: 'OPEN',
        amountCents: order.totalCents,
        checkoutUrl: `https://mollie.test/checkout/${id}`,
      }
      reported.set(id, payment)
      return payment
    },
    find: (id) => {
      calls.find += 1
      return Promise.resolve(reported.get(id) ?? null)
    },
    cancel: (id) => {
      calls.cancel.push(id)
      return Promise.resolve(true)
    },
  }
  /** Plays Mollie's side: the payment `id` now has `status` (and optionally another amount). */
  const report = (id: string, change: Partial<MolliePayment>) => {
    const payment = reported.get(id)
    if (payment === undefined) throw new Error(`No stub payment ${id}`)
    reported.set(id, { ...payment, ...change })
  }
  return { mollie, calls, state, report }
}

/** The module under test plus its private image storage; `mollie` defaults to a fresh stub. */
export async function orderModuleFor(db: Kysely<Tables>, mollie = stubMollie().mollie) {
  const root = await mkdtemp(join(tmpdir(), 'order-test-'))
  const images = createImageModule({
    settings: {
      publicRoot: join(root, 'public'),
      privateRoot: join(root, 'private'),
      cacheRoot: join(root, 'cache'),
    },
    guestTokens,
  })
  const email = createEmailModule({
    db,
    settings: {
      enabled: false,
      pollIntervalMinutes: 5,
      apiKey: '',
      fromEmail: '',
      fromName: 'Voenix Shop',
    },
    fetch: () => Promise.reject(new Error('The order tests send no mail')),
  })
  const module = createOrderModule({
    db,
    settings: { mollie: MOLLIE_SETTINGS, frontendBaseUrl: FRONTEND_BASE_URL },
    articleCatalog,
    promotionCodes: createPromotionModule({ db }).promotionCodes,
    email,
    images,
    guestTokens,
    mollie,
  })
  return { ...module, privateStorage: images.privateStorage }
}

/** The `Cookie` header of a visitor whose guest token is `token`. */
export async function guestCookie(token: string): Promise<string> {
  return `${GUEST_COOKIE}=${await guestCookies.seal(token)}`
}

/** Empties every table the order tests write. */
export async function resetOrders(db: Kysely<Tables>): Promise<void> {
  await sql`TRUNCATE orders, payments, production_requests, email_jobs, promotion_redemptions,
    promotion_reservations, promotions, print_images, carts, users RESTART IDENTITY CASCADE`.execute(
    db,
  )
}

/**
 * A cart row, which an order references; a guest cart by default. It is stored checked out, so
 * one visitor may have several (only one *active* cart per owner is allowed).
 */
export async function seedCart(
  db: Kysely<Tables>,
  owner: { guestToken: string } | { userId: number } = { guestToken: 'guest-a' },
): Promise<number> {
  const guestToken = 'guestToken' in owner ? owner.guestToken : null
  const userId = 'userId' in owner ? owner.userId : null
  const { rows } = await sql<{
    id: number
  }>`INSERT INTO carts (guest_session_token, user_id, status)
    VALUES (${guestToken}, ${userId}, 'CHECKED_OUT') RETURNING id`.execute(db)
  return rows[0]?.id ?? 0
}

export async function seedUser(db: Kysely<Tables>, email: string): Promise<number> {
  const { rows } = await sql<{ id: number }>`INSERT INTO users (email, password_hash)
    VALUES (${email}, 'v1$1$salt$hash') RETURNING id`.execute(db)
  return rows[0]?.id ?? 0
}

/** An active 10 % coupon, optionally limited to `usageLimitTotal` redemptions. */
export async function seedPromotion(
  db: Kysely<Tables>,
  usageLimitTotal: number | null = null,
): Promise<number> {
  const { rows } = await sql<{ id: number }>`INSERT INTO promotions (name, discount_type,
      discount_value, coupon_code, coupon_code_normalized, usage_limit_total, is_active)
    VALUES ('Sale', 'PERCENTAGE', 10.00, 'Sale10', 'SALE10', ${usageLimitTotal}, TRUE)
    RETURNING id`.execute(db)
  return rows[0]?.id ?? 0
}

/** Reserves the coupon for the cart the way checkout TX1 does. */
export async function reserve(
  db: Kysely<Tables>,
  promotionId: number,
  cartId: number,
): Promise<void> {
  const codes = createPromotionModule({ db }).promotionCodes
  await write(db, (trx) => codes.reserve(trx, promotionId, cartId, null))
}

/** A stored print image: the file in the private storage and its `print_images` row. */
export async function seedPrintImage(
  db: Kysely<Tables>,
  storage: ImageStorage,
): Promise<{ id: number; filename: string }> {
  const bytes = await sharp({
    create: { width: 4, height: 4, channels: 3, background: '#ff0000' },
  })
    .png()
    .toBuffer()
  const stored = await storage.store({ bytes, contentType: 'image/png' })
  if (stored.kind !== 'ok') throw new Error('The test image was refused')
  const { filename } = stored.value
  const { rows } = await sql<{
    id: number
  }>`INSERT INTO print_images (filename, guest_session_token)
    VALUES (${filename}, 'guest-a') RETURNING id`.execute(db)
  return { id: rows[0]?.id ?? 0, filename }
}

/** The number of rows in one table. */
export async function count(db: Kysely<Tables>, table: string): Promise<number> {
  const { rows } = await sql<{ count: number }>`SELECT count(*)::int AS count FROM ${sql.table(
    table,
  )}`.execute(db)
  return rows[0]?.count ?? 0
}

/** The order of a placement that must have succeeded. */
export function placedOrder(result: PlaceOrderResult): PayableOrder {
  if (result.kind !== 'placed') throw new Error(`The test order was not placed: ${result.kind}`)
  return result.order
}

/** The message a promise rejected with, or `'resolved'`. */
export const rejectionOf = (promise: Promise<unknown>): Promise<string> =>
  promise.then(
    () => 'resolved',
    (error: unknown) => (error instanceof Error ? error.message : String(error)),
  )
