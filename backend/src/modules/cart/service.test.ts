import { beforeEach, describe, expect, test } from 'bun:test'
import { readdir } from 'node:fs/promises'
import { join } from 'node:path'
import { sql } from 'kysely'
import { write } from '@/platform/db/database'
import { count, testDatabase } from '@test-support/database'
import { guestCookie, guestHeaders, jsonOf, signedInHeaders, testApp } from '@test-support/http'
import { pngBytes } from '@test-support/values'
import { createCartRepository, createPrintImageReads } from './repository'
import { createCartService } from './service'
import {
  cartModuleFor,
  MUG_REF,
  PROMPT_ID,
  resetCarts,
  seedOrderItem,
  seedPromotion,
  SPOD_REF,
  UNKNOWN_REF,
} from './test-fixtures'

const database = testDatabase()
let cart: Awaited<ReturnType<typeof cartModuleFor>>

beforeEach(async () => {
  await resetCarts(database.db)
  cart = await cartModuleFor(database.db)
})

const guest = async (token = 'guest-a') => {
  const headers = await guestHeaders()
  return { ...headers, Cookie: `${headers.Cookie ?? ''}; ${await guestCookie(token)}` }
}
const addItem = async (headers: Record<string, string>, body: Record<string, unknown> = {}) =>
  testApp(cart.routes).request('/api/cart/items', {
    method: 'POST',
    headers: { ...headers, 'content-type': 'application/json' },
    body: JSON.stringify({ ...MUG_REF, quantity: 1, ...body }),
  })
const cartOf = async (response: Response) =>
  (await jsonOf(response)) as { id: number; items: { quantity: number }[] }
const lines = () =>
  sql<{
    cartId: number
    position: number
    quantity: number
  }>`SELECT cart_id AS "cartId", position, quantity FROM cart_items ORDER BY cart_id, position`
    .execute(database.db)
    .then((result) => result.rows)

/** The service on its own, for what no route can provoke. */
const service = () =>
  createCartService({
    repository: createCartRepository(database.db, cart.promotionCodes),
    articleCatalog: cart.articleCatalog,
    promptCatalog: cart.promptCatalog,
    promotionCodes: cart.promotionCodes,
    orders: { orderItem: () => Promise.resolve(undefined) },
    privateStorage: cart.privateStorage,
  })
const pngUpload = async () => ({
  kind: 'received' as const,
  upload: { bytes: new Uint8Array(await pngBytes()), contentType: 'image/png' },
})

describe('adding lines', () => {
  test('an identical line merges and caps at 99; a different snapshot is a line of its own', async () => {
    const headers = await guest()
    await addItem(headers, { quantity: 60 })
    expect((await cartOf(await addItem(headers, { quantity: 60 }))).items).toMatchObject([
      { quantity: 99 },
    ])
    await addItem(headers, { promptId: PROMPT_ID })
    await addItem(headers, SPOD_REF)
    expect((await lines()).map(({ position, quantity }) => [position, quantity])).toEqual([
      [1, 99],
      [2, 1],
      [3, 1],
    ])
  })

  test('two concurrent first adds of one visitor make one cart, and identical lines one line', async () => {
    const headers = await guest()
    const responses = await Promise.all([addItem(headers), addItem(headers)])
    expect(responses.map((response) => response.status)).toEqual([200, 200])
    expect(await count(database.db, 'carts')).toBe(1)
    expect((await lines()).map(({ quantity }) => quantity)).toEqual([2])
  })

  test('concurrent adds of different lines queue up behind the cart row and get distinct positions', async () => {
    const headers = await guest()
    await addItem(headers)
    await Promise.all([
      addItem(headers, SPOD_REF),
      addItem(headers, { promptId: PROMPT_ID }),
      addItem(headers, { ...SPOD_REF, promptId: PROMPT_ID }),
    ])
    expect((await lines()).map(({ position }) => position)).toEqual([1, 2, 3, 4])
  })
})

describe('print images', () => {
  test('the guest image resolver answers the owner only; a signed-in upload is not the token’s', async () => {
    const service_ = service()
    const guestUpload = await service_.uploadPrintImage(
      { guestToken: 'guest-a', userId: null },
      await pngUpload(),
    )
    const signedInUpload = await service_.uploadPrintImage(
      { guestToken: 'guest-a', userId: 7 },
      await pngUpload(),
    )
    if (guestUpload.kind !== 'ok' || signedInUpload.kind !== 'ok') throw new Error('Upload failed')
    const resolve = (imageId: number, guestToken: string | null, userId: number | null) =>
      cart.guestImageResolver({ imageId, guestToken, userId })

    const guestImage = guestUpload.value.id
    expect(await resolve(guestImage, 'guest-a', null)).toMatch(/^[0-9a-f-]{36}\.webp$/)
    expect(await resolve(guestImage, 'guest-a', 8)).not.toBeNull()
    expect(await resolve(guestImage, 'guest-b', null)).toBeNull()
    expect(await resolve(guestImage, null, null)).toBeNull()
    expect(await resolve(999, 'guest-a', null)).toBeNull()

    // The browser kept its token after customer 7 signed out: the upload is no longer reachable.
    const signedInImage = signedInUpload.value.id
    expect(await resolve(signedInImage, 'guest-a', null)).toBeNull()
    expect(await resolve(signedInImage, null, 7)).not.toBeNull()
    expect(await resolve(signedInImage, 'guest-a', 8)).toBeNull()
    expect(
      (await addItem(await guest(), { imageId: signedInImage })).status,
      'the add applies the same rule',
    ).toBe(400)
  })

  test('the order module’s print image read answers file names by id, without an ownership rule', async () => {
    const service_ = service()
    const guestUpload = await service_.uploadPrintImage(
      { guestToken: 'guest-a', userId: null },
      await pngUpload(),
    )
    const signedInUpload = await service_.uploadPrintImage(
      { guestToken: null, userId: 7 },
      await pngUpload(),
    )
    if (guestUpload.kind !== 'ok' || signedInUpload.kind !== 'ok') throw new Error('Upload failed')
    const reads = createPrintImageReads(database.db)

    const filenames = await reads.filenames([guestUpload.value.id, signedInUpload.value.id, 999])

    expect([...filenames.keys()].sort()).toEqual(
      [guestUpload.value.id, signedInUpload.value.id].sort(),
    )
    for (const filename of filenames.values()) expect(filename).toMatch(/^[0-9a-f-]{36}\.webp$/)
    expect((await reads.filenames([])).size).toBe(0)
  })

  test('an upload whose row cannot be written deletes its file again', async () => {
    // Neither identity violates ck_print_images_owner after the file is stored.
    const outcome = await service()
      .uploadPrintImage({ guestToken: null, userId: null }, await pngUpload())
      .then(
        () => 'resolved',
        () => 'rejected',
      )
    expect(outcome).toBe('rejected')
    const files = await readdir(join(cart.root, 'private'), {
      recursive: true,
      withFileTypes: true,
    })
    expect(files.filter((entry) => entry.isFile())).toEqual([])
  })
})

describe('checkoutCarts', () => {
  test('activeCart answers the priced snapshot a checkout places from', async () => {
    expect(await cart.checkoutCarts.activeCart('guest-a', null)).toBeNull()
    const headers = await guest()
    await addItem(headers, { quantity: 2, promptId: PROMPT_ID })
    const cartId = (await cartOf(await addItem(headers, { ...SPOD_REF, quantity: 2 }))).id
    // A line whose reference the catalog no longer answers.
    await sql`INSERT INTO cart_items (cart_id, article_id, variant_id, quantity, price_cents, position)
      VALUES (${cartId}, 1, 6, 1, 100, 3)`.execute(database.db)
    const promotionId = await seedPromotion(database.db)
    await sql`UPDATE carts SET promotion_id = ${promotionId}`.execute(database.db)

    expect(await cart.checkoutCarts.activeCart('guest-a', null)).toEqual({
      cartId,
      userId: null,
      guestToken: 'guest-a',
      promotionId,
      lines: [
        {
          ...MUG_REF,
          quantity: 2,
          priceCents: 1490,
          promptPriceCents: 500,
          promptId: PROMPT_ID,
          printImageId: null,
          articleType: 'MUG',
        },
        {
          ...SPOD_REF,
          quantity: 2,
          priceCents: 2500,
          promptPriceCents: 0,
          promptId: null,
          printImageId: null,
          articleType: 'SPOD',
        },
        {
          ...UNKNOWN_REF,
          quantity: 1,
          priceCents: 100,
          promptPriceCents: 0,
          promptId: null,
          printImageId: null,
          articleType: null,
        },
      ],
      // 2 × 1990 + 2 × 2500 + 100 = 9080, shipped free; ten percent off.
      subtotalCents: 9080,
      shippingCostCents: 0,
      discountCents: 908,
      totalCents: 8172,
    })
  })

  test('activeCart finds a signed-in customer’s cart by user id, and an emptied cart is a snapshot without lines', async () => {
    const headers = await signedInHeaders({ userId: 7, roles: ['CUSTOMER'] })
    const added = await cartOf(await addItem(headers))
    await sql`DELETE FROM cart_items`.execute(database.db)
    expect(await cart.checkoutCarts.activeCart('any-token', 7)).toMatchObject({
      cartId: added.id,
      userId: 7,
      lines: [],
      totalCents: 0,
    })
    expect(await cart.checkoutCarts.activeCart('any-token', null)).toBeNull()
  })

  test('lockForCheckout and markCheckedOut close the cart once; the next add starts a fresh cart', async () => {
    const headers = await guest()
    const { id } = await cartOf(await addItem(headers))
    const { lockForCheckout, markCheckedOut } = cart.checkoutCarts
    expect(
      await write(database.db, async (trx) => [
        await lockForCheckout(trx, id),
        await markCheckedOut(trx, id),
        await markCheckedOut(trx, id),
        await lockForCheckout(trx, id),
        await lockForCheckout(trx, 999),
        await markCheckedOut(trx, 999),
      ]),
    ).toEqual([true, true, false, false, false, false])
    expect(await cart.checkoutCarts.activeCart('guest-a', null)).toBeNull()
    expect((await cartOf(await addItem(headers))).id).not.toBe(id)
  })

  test('an add racing a checkout waits for the lock and lands in a fresh cart', async () => {
    const headers = await guest()
    const { id } = await cartOf(await addItem(headers))
    let racing: Promise<Response> | undefined
    await write(database.db, async (trx) => {
      expect(await cart.checkoutCarts.lockForCheckout(trx, id)).toBe(true)
      racing = addItem(headers, SPOD_REF)
      await Bun.sleep(200)
      await cart.checkoutCarts.markCheckedOut(trx, id)
    })
    const fresh = await cartOf(await (racing ?? Promise.reject(new Error('No racing add'))))
    expect(fresh.id).not.toBe(id)
    expect(fresh.items).toMatchObject([{ quantity: 1, ...SPOD_REF }])
    expect((await lines()).map(({ cartId }) => cartId)).toEqual([id, fresh.id])
  })
})

describe('printImageRegistry', () => {
  /** A print image row of guest `guest-a` for `filename`, answering its id. */
  const seedPrintImage = async (filename: string) => {
    const { rows } = await sql<{ id: number }>`INSERT INTO print_images
        (filename, guest_session_token) VALUES (${filename}, 'guest-a') RETURNING id`.execute(
      database.db,
    )
    return rows[0]?.id ?? 0
  }

  test('inUse answers the images a cart line or an ordered line points at', async () => {
    const inCart = await seedPrintImage('in-cart.webp')
    const ordered = await seedPrintImage('ordered.webp')
    await seedPrintImage('unused.webp')
    await addItem(await guest(), { imageId: inCart })
    await seedOrderItem(
      database.db,
      { guestToken: 'guest-a', userId: null },
      { ...MUG_REF, promptId: null, printImageId: ordered },
    )

    expect([...(await cart.printImageRegistry.inUse())].sort()).toEqual([
      'in-cart.webp',
      'ordered.webp',
    ])
  })

  test('release deletes an unused registration and refuses one a line points at', async () => {
    const inCart = await seedPrintImage('in-cart.webp')
    const ordered = await seedPrintImage('ordered.webp')
    await seedPrintImage('unused.webp')
    await addItem(await guest(), { imageId: inCart })
    await seedOrderItem(
      database.db,
      { guestToken: 'guest-a', userId: null },
      { ...MUG_REF, promptId: null, printImageId: ordered },
    )

    expect(await cart.printImageRegistry.release('unused.webp')).toBe(true)
    expect(await cart.printImageRegistry.release('never-registered.webp')).toBe(true)
    expect(await cart.printImageRegistry.release('in-cart.webp')).toBe(false)
    expect(await cart.printImageRegistry.release('ordered.webp')).toBe(false)
    expect(await count(database.db, 'print_images')).toBe(2)
  })
})
