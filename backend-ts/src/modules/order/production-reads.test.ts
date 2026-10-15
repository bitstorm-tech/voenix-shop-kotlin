import { beforeEach, describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { write } from '@/platform/db/database'
import { testDatabase } from '@test-support/database'
import {
  FRONTEND_BASE_URL,
  orderModuleFor,
  placeInput,
  resetOrders,
  seedCart,
  seedPrintImage,
} from './test-fixtures'

const database = testDatabase()

beforeEach(() => resetOrders(database.db))

/** A mug line with a print image and a SPOD line, placed just before midnight in Berlin. */
async function placedOrder() {
  const module = await orderModuleFor(database.db)
  const image = await seedPrintImage(database.db, module.privateStorage)
  const cartId = await seedCart(database.db)
  const input = placeInput(cartId, { subtotalCents: 3980 + 2900 })
  await write(database.db, (trx) =>
    module.orders.place(trx, {
      ...input,
      lines: [
        {
          articleId: 2,
          variantId: 5,
          quantity: 1,
          priceCents: 2900,
          promptPriceCents: 0,
          promptId: null,
          printImageId: null,
        },
        ...input.lines.map((line) => ({ ...line, printImageId: image.id })),
      ],
    }),
  )
  await sql`UPDATE orders SET created_at = '2026-07-29T22:30:00Z'`.execute(database.db)
  const imagePath = (await module.privateStorage.originalPaths([image.filename])).get(
    image.filename,
  )
  if (imagePath === undefined) throw new Error('The test image was not stored')
  return { orders: module.orders, imagePath }
}

describe('productionData', () => {
  test('answers the snapshot of every line in its position, with the print image path', async () => {
    const { orders, imagePath } = await placedOrder()
    expect(imagePath).toStartWith('/')

    expect(await orders.productionData(1)).toEqual({
      orderId: 1,
      orderDate: '2026-07-30',
      customerEmail: 'kunde@example.com',
      customerPhone: '+4917623123456',
      shippingFirstName: 'Erika',
      shippingLastName: 'Musterfrau',
      shippingStreet: 'Lieferweg',
      shippingHouseNumber: '5',
      shippingPostalCode: '20095',
      shippingCity: 'Hamburg',
      shippingCountry: 'DE',
      lines: [
        {
          articleId: 2,
          variantId: 5,
          articleName: 'Shirt Organic',
          supplierArticleNumber: null,
          variantName: 'Black / M',
          quantity: 1,
          imagePath: null,
          printTemplateWidthMm: null,
          printTemplateHeightMm: null,
          documentFormatWidthMm: null,
          documentFormatHeightMm: null,
          documentFormatMarginBottomMm: null,
          spodHotspot: 'MEDIUM_FRONT',
        },
        {
          articleId: 1,
          variantId: 1,
          articleName: 'Tasse Klassik',
          supplierArticleNumber: 'MUG-001',
          variantName: 'Weiß/Blau',
          quantity: 2,
          imagePath,
          printTemplateWidthMm: 200,
          printTemplateHeightMm: 80,
          documentFormatWidthMm: 239,
          documentFormatHeightMm: 99,
          documentFormatMarginBottomMm: 5,
          spodHotspot: null,
        },
      ],
    })
    expect(await orders.productionData(99)).toBeNull()
  })

  test('an image file that is gone leaves the path null instead of losing the line', async () => {
    const { orders } = await placedOrder()
    await sql`UPDATE print_images SET filename = 'gone.webp'`.execute(database.db)
    const data = await orders.productionData(1)
    expect(data?.lines.map((line) => line.imagePath)).toEqual([null, null])
  })
})

describe('fulfillmentOrders', () => {
  test('answers exactly the supplier header per known id, in one batch', async () => {
    const { orders } = await placedOrder()
    const found = await orders.fulfillmentOrders([1, 99])
    expect([...found]).toEqual([
      [
        1,
        {
          orderId: 1,
          orderDate: '2026-07-30',
          customerFirstName: 'Erika',
          customerLastName: 'Musterfrau',
          shippingStreet: 'Lieferweg',
          shippingHouseNumber: '5',
          shippingPostalCode: '20095',
          shippingCity: 'Hamburg',
          shippingCountry: 'DE',
        },
      ],
    ])
    expect((await orders.fulfillmentOrders([])).size).toBe(0)
  })
})

describe('shippingNotificationOrder', () => {
  test('answers the recipient and the finished permanent link', async () => {
    const { orders } = await placedOrder()
    const { access_token } = await database.db
      .selectFrom('orders')
      .select('access_token')
      .executeTakeFirstOrThrow()
    expect(await orders.shippingNotificationOrder(1)).toEqual({
      recipientEmail: 'kunde@example.com',
      customerFirstName: 'Erika',
      orderUrl: `${FRONTEND_BASE_URL}/order/${access_token}`,
    })
    expect(await orders.shippingNotificationOrder(99)).toBeNull()
  })
})
