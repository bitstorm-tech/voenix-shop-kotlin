import { beforeEach, describe, expect, test } from 'bun:test'
import { write } from '@/platform/db/database'
import { testDatabase } from '@test-support/database'
import { guestHeaders, jsonOf, setCookies, signedInHeaders, testApp } from '@test-support/http'
import {
  cartModuleFor,
  count,
  guestCookie,
  MUG,
  MUG_REF,
  pngBytes,
  PROMPT_ID,
  resetCarts,
  seedOrderItem,
  seedPromotion,
} from './test-fixtures'

const database = testDatabase()
let cart: Awaited<ReturnType<typeof cartModuleFor>>
const app = () => testApp(cart.routes)

beforeEach(async () => {
  await resetCarts(database.db)
  cart = await cartModuleFor(database.db)
})

/** A guest browser that fetched a CSRF token and carries the guest cookie of `token`. */
const guest = async (token = 'guest-a') => withGuestCookie(await guestHeaders(), token)
/** Customer `userId`, signed in, in a browser that also carries the guest cookie of `token`. */
const customer = async (userId: number, token = 'guest-a') =>
  withGuestCookie(await signedInHeaders({ userId: String(userId), roles: ['CUSTOMER'] }), token)
const withGuestCookie = async (headers: Record<string, string>, token: string) => ({
  ...headers,
  Cookie: `${headers.Cookie ?? ''}; ${await guestCookie(token)}`,
})

const send = (method: string, path: string, headers: Record<string, string>, body?: unknown) =>
  app().request(
    path,
    body === undefined
      ? { method, headers }
      : {
          method,
          headers: { ...headers, 'content-type': 'application/json' },
          body: JSON.stringify(body),
        },
  )
const getCart = (headers: Record<string, string>) => send('GET', '/api/cart', headers)
const addItem = (headers: Record<string, string>, body: Record<string, unknown> = {}) =>
  send('POST', '/api/cart/items', headers, { ...MUG_REF, quantity: 1, ...body })
const applyCode = (headers: Record<string, string>, promotionCode: string) =>
  send('POST', '/api/cart/promotion', headers, { promotionCode })
const reorder = (headers: Record<string, string>, orderItemId: number | string) =>
  send('POST', `/api/cart/order-items/${String(orderItemId)}`, headers)

const upload = async (headers: Record<string, string>, bytes?: Uint8Array) => {
  const form = new FormData()
  if (bytes !== undefined)
    form.append('file', new File([bytes], 'print.png', { type: 'image/png' }))
  return app().request('/api/cart/images', { method: 'POST', headers, body: form })
}
const uploadedImageId = async (headers: Record<string, string>) => {
  const response = await upload(headers, new Uint8Array(await pngBytes()))
  expect(response.status).toBe(201)
  return ((await jsonOf(response)) as { id: number }).id
}

interface CartBody {
  id: number | null
  items: { id: number; quantity: number; price: number; imageId: number | null }[]
  subtotal: number
  discountAmount: number
  total: number
  appliedPromotion: unknown
}
const bodyOf = async (response: Response) => (await jsonOf(response)) as CartBody
const itemsOf = async (response: Response) => (await jsonOf(response)) as { items: unknown }

describe('GET /api/cart', () => {
  test('a visitor without cookies sees the empty cart, uncached, and gets no guest cookie', async () => {
    const response = await app().request('/api/cart')
    expect(response.status).toBe(200)
    expect(response.headers.get('Cache-Control')).toBe('no-store')
    expect(setCookies(response, 'voenix.guest')).toEqual([])
    expect(await jsonOf(response)).toEqual({
      id: null,
      items: [],
      subtotal: 0,
      shippingCost: 0,
      discountAmount: 0,
      total: 0,
      totalItems: 0,
      appliedPromotion: null,
    })
  })
})

describe('the cart journey', () => {
  test('upload, add, read, update, and remove take a guest cart through its life', async () => {
    const headers = await guest()
    const imageId = await uploadedImageId(headers)

    const added = await addItem(headers, { quantity: 2, promptId: PROMPT_ID, imageId })
    expect(added.status).toBe(200)
    const body = await bodyOf(added)
    const itemId = body.items[0]?.id ?? 0
    expect(body as unknown).toEqual({
      id: body.id,
      items: [
        {
          id: itemId,
          articleId: 1,
          variantId: 1,
          articleType: 'MUG',
          articleName: 'Classic mug',
          variantName: 'White',
          outsideColorCode: '#ffffff',
          insideColorCode: '#ff0000',
          available: true,
          price: 1490,
          quantity: 2,
          imageId,
          promptId: PROMPT_ID,
          promptPrice: 500,
        },
      ],
      // 2 × (1490 + 500) = 3980, below the free-shipping threshold.
      subtotal: 3980,
      shippingCost: 490,
      discountAmount: 0,
      total: 4470,
      totalItems: 2,
      appliedPromotion: null,
    })
    expect(await jsonOf(await getCart(headers))).toEqual(body)

    const updated = await send('PATCH', `/api/cart/items/${String(itemId)}`, headers, {
      quantity: 1,
    })
    expect(updated.status).toBe(200)
    expect((await bodyOf(updated)).subtotal).toBe(1990)

    const removed = await send('DELETE', `/api/cart/items/${String(itemId)}`, headers)
    expect(removed.status).toBe(200)
    expect(await bodyOf(removed)).toMatchObject({ id: body.id, items: [], total: 0 })
    // The cart survives its last line, so the customer keeps their promotion.
    expect(await count(database.db, 'carts')).toBe(1)
  })

  test('the first mutation hands out the guest cookie the cart is stored under', async () => {
    const headers = await guestHeaders()
    const response = await addItem(headers)
    expect(response.status).toBe(200)
    expect(setCookies(response, 'voenix.guest')).toHaveLength(1)
  })

  test('a signed-in customer has a cart of their own, apart from the guest cart of the same browser', async () => {
    await addItem(await guest('shared-browser'), { quantity: 3 })
    const added = await addItem(await customer(7, 'shared-browser'))
    expect(added.status).toBe(200)
    const own = await bodyOf(added)
    expect(own.items.map((item) => item.quantity)).toEqual([1])
    expect((await bodyOf(await getCart(await customer(7, 'other-browser')))).id).toBe(own.id)
    expect((await bodyOf(await getCart(await customer(8, 'shared-browser')))).id).toBeNull()
    const guestCart = await bodyOf(await getCart(await guest('shared-browser')))
    expect(guestCart.items.map((item) => item.quantity)).toEqual([3])
  })
})

describe('POST /api/cart/images', () => {
  test('a missing file part and an unreadable image are 400 on the file field', async () => {
    const missing = await upload(await guest())
    expect(missing.status).toBe(400)
    expect(await jsonOf(missing)).toEqual({
      message: 'Validation failed',
      errors: { file: ['An image file is required'] },
    })
    const unreadable = await upload(await guest(), new Uint8Array(16))
    expect(unreadable.status).toBe(400)
    expect(Object.keys(((await jsonOf(unreadable)) as { errors: object }).errors)).toEqual(['file'])
    expect(await count(database.db, 'print_images')).toBe(0)
  })
})

describe('POST /api/cart/items', () => {
  test('a variant that cannot be bought, an unusable prompt, and a foreign image are 400 and write nothing', async () => {
    const headers = await guest()
    cart.variants.set('1:1', { ...MUG, purchasable: false })
    expect(await jsonOf(await addItem(headers))).toEqual({
      message: 'Validation failed',
      errors: { variantId: ['The article variant cannot be bought'] },
    })
    expect((await addItem(headers, { variantId: 6 })).status).toBe(400)
    cart.variants.set('1:1', MUG)

    expect(await jsonOf(await addItem(headers, { promptId: 99 }))).toEqual({
      message: 'Validation failed',
      errors: { promptId: ['The prompt cannot be used'] },
    })

    const foreignImage = await uploadedImageId(await guest('guest-b'))
    expect(await jsonOf(await addItem(headers, { imageId: foreignImage }))).toEqual({
      message: 'Validation failed',
      errors: { imageId: ['The image cannot be used'] },
    })
    expect(await count(database.db, 'carts')).toBe(0)
  })

  test('the price snapshot of a line stays when the catalog price changes', async () => {
    const headers = await guest()
    await addItem(headers)
    cart.variants.set('1:1', { ...MUG, grossSalesPriceCents: 1990 })
    const body = await bodyOf(await getCart(headers))
    expect(body.items.map((item) => item.price)).toEqual([1490])
    // A line at the new price is a line of its own.
    const added = await bodyOf(await addItem(headers))
    expect(added.items.map((item) => item.price)).toEqual([1490, 1990])
  })
})

describe('PATCH and DELETE /api/cart/items/{itemId}', () => {
  test('a line of another cart, an unknown line, and a malformed id are not found', async () => {
    const other = await bodyOf(await addItem(await guest('guest-b')))
    const foreignId = String(other.items[0]?.id)
    const headers = await guest()
    expect(await jsonOf(await send('DELETE', `/api/cart/items/${foreignId}`, headers))).toEqual({
      message: 'Cart not found',
      errors: {},
    })
    await addItem(headers)
    const update = await send('PATCH', `/api/cart/items/${foreignId}`, headers, { quantity: 5 })
    expect(update.status).toBe(404)
    expect((await send('DELETE', '/api/cart/items/999999', headers)).status).toBe(404)
    const malformed = await send('DELETE', '/api/cart/items/not-a-number', headers)
    expect(malformed.status).toBe(404)
    expect(await jsonOf(malformed)).toEqual({ message: 'Cart item not found', errors: {} })
    expect((await bodyOf(await getCart(await guest('guest-b')))).items).toEqual(other.items)
  })
})

describe('POST and DELETE /api/cart/promotion', () => {
  test('a code is applied, rendered with its discount, and removed again', async () => {
    const promotionId = await seedPromotion(database.db)
    const headers = await guest()
    await addItem(headers)

    const applied = await applyCode(headers, ' save10 ')
    expect(applied.status).toBe(200)
    expect(await bodyOf(applied)).toMatchObject({
      appliedPromotion: {
        id: promotionId,
        name: 'Summer',
        promotionCode: 'SAVE10',
        discountType: 'PERCENTAGE',
        discountValue: 10,
      },
      // 1490 + 490 shipping = 1980; ten percent of that is 198.
      discountAmount: 198,
      total: 1782,
    })

    const removed = await send('DELETE', '/api/cart/promotion', headers)
    expect(removed.status).toBe(200)
    expect(await bodyOf(removed)).toMatchObject({ appliedPromotion: null, discountAmount: 0 })
  })

  test('removing the code releases the reservation a checkout left on the cart', async () => {
    const promotionId = await seedPromotion(database.db, { usageLimitTotal: 1 })
    const headers = await guest()
    await addItem(headers)
    const cartId = (await bodyOf(await applyCode(headers, 'SAVE10'))).id ?? 0
    await write(database.db, (trx) => cart.promotionCodes.reserve(trx, promotionId, cartId, null))
    expect(await count(database.db, 'promotion_reservations')).toBe(1)

    expect((await send('DELETE', '/api/cart/promotion', headers)).status).toBe(200)
    expect(await count(database.db, 'promotion_reservations')).toBe(0)
  })

  test('every rejection answers its status and code, and keeps the code already applied', async () => {
    const headers = await guest()
    expect(await jsonOf(await applyCode(headers, 'SAVE10'))).toEqual({
      message: 'Cart not found',
      errors: {},
    })
    await seedPromotion(database.db)
    await seedPromotion(database.db, { code: 'OFF', active: false })
    await seedPromotion(database.db, { code: 'OLD', endsAt: new Date('2020-01-01T00:00:00Z') })
    await seedPromotion(database.db, { code: 'MEMBERS', usageLimitPerUser: 1 })
    const scarce = await seedPromotion(database.db, { code: 'LAST', usageLimitTotal: 1 })
    const otherCart = (await bodyOf(await addItem(await guest('guest-b')))).id ?? 0
    await write(database.db, (trx) => cart.promotionCodes.reserve(trx, scarce, otherCart, null))
    await addItem(headers)
    await applyCode(headers, 'SAVE10')

    for (const [code, status, expected] of [
      ['UNKNOWN', 400, 'PROMOTION_INVALID_CODE'],
      ['OFF', 400, 'PROMOTION_INACTIVE'],
      ['OLD', 400, 'PROMOTION_EXPIRED'],
      ['MEMBERS', 403, 'PROMOTION_LOGIN_REQUIRED'],
      ['LAST', 409, 'PROMOTION_TOTAL_EXHAUSTED'],
    ] as const) {
      const response = await applyCode(headers, code)
      expect(response.status).toBe(status)
      expect(await jsonOf(response)).toMatchObject({ code: expected, errors: {} })
    }
    const kept = await bodyOf(await getCart(headers))
    expect(kept.appliedPromotion).toMatchObject({ promotionCode: 'SAVE10' })
    // A signed-in customer may use the per-user code.
    await addItem(await customer(7))
    expect((await applyCode(await customer(7), 'MEMBERS')).status).toBe(200)
  })

  test('removing a code without a cart is not found', async () => {
    expect((await send('DELETE', '/api/cart/promotion', await guest())).status).toBe(404)
  })
})

describe('POST /api/cart/order-items/{orderItemId}', () => {
  test('rebuilds an ordered line at today’s price, and a repetition merges into it', async () => {
    const headers = await guest()
    const imageId = await uploadedImageId(headers)
    const orderItemId = await seedOrderItem(
      database.db,
      { guestToken: 'guest-a', userId: null },
      { ...MUG_REF, promptId: PROMPT_ID, printImageId: imageId },
    )
    cart.variants.set('1:1', { ...MUG, grossSalesPriceCents: 1990 })

    const reordered = await reorder(headers, orderItemId)
    expect(reordered.status).toBe(200)
    expect((await itemsOf(reordered)).items).toMatchObject([
      { ...MUG_REF, price: 1990, quantity: 1, promptId: PROMPT_ID, promptPrice: 500, imageId },
    ])
    const again = await bodyOf(await reorder(headers, orderItemId))
    expect(again.items.map((item) => item.quantity)).toEqual([2])
  })

  test('a foreign or malformed order item is not found and leaves no cart behind', async () => {
    const imageId = await uploadedImageId(await guest('guest-b'))
    const foreign = await seedOrderItem(
      database.db,
      { guestToken: 'guest-b', userId: null },
      { ...MUG_REF, promptId: null, printImageId: imageId },
    )
    for (const id of [foreign, 'not-a-number']) {
      const response = await reorder(await guest(), id)
      expect(response.status).toBe(404)
      expect(await jsonOf(response)).toEqual({ message: 'Order item not found', errors: {} })
    }
    // Only the checked-out cart of the seeded order.
    expect(await count(database.db, 'carts')).toBe(1)
  })

  test('an ordered line without a usable print image is 409 ORDER_IMAGE_UNAVAILABLE', async () => {
    const headers = await guest()
    const imageId = await uploadedImageId(headers)
    const owner = { guestToken: 'guest-a', userId: null }
    const withoutImage = await seedOrderItem(database.db, owner, {
      ...MUG_REF,
      promptId: null,
      printImageId: null,
    })
    const withImage = await seedOrderItem(database.db, owner, {
      ...MUG_REF,
      promptId: null,
      printImageId: imageId,
    })
    const filename = await cart.guestImageResolver({ imageId, guestToken: 'guest-a', userId: null })
    await cart.privateStorage.delete(filename ?? '')

    for (const id of [withoutImage, withImage]) {
      const response = await reorder(headers, id)
      expect(response.status).toBe(409)
      expect(await jsonOf(response)).toEqual({
        message: 'The image of this order item is no longer available',
        errors: {},
        code: 'ORDER_IMAGE_UNAVAILABLE',
      })
    }
    expect(await count(database.db, 'cart_items')).toBe(0)
  })
})
