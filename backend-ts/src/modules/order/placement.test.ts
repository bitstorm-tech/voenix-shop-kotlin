import { beforeEach, describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { write } from '@/platform/db/database'
import { count, seedUser, testDatabase } from '@test-support/database'
import { rejectionOf } from '@test-support/values'
import {
  ADDRESS,
  FRONTEND_BASE_URL,
  orderModuleFor,
  placedOrder,
  placeInput,
  resetOrders,
  seedCart,
  seedPrintImage,
} from './test-fixtures'
import type { PlaceOrderInput } from './repository'

const database = testDatabase()
const module = () => orderModuleFor(database.db)
const place = async (input: PlaceOrderInput) => {
  const { orders } = await module()
  return write(database.db, (trx) => orders.place(trx, input))
}

beforeEach(() => resetOrders(database.db))

describe('orders.place', () => {
  test('snapshots every line, falls back to the shipping address, and enqueues the mail', async () => {
    const cartId = await seedCart(database.db)
    const { privateStorage } = await module()
    const image = await seedPrintImage(database.db, privateStorage)
    const input = placeInput(cartId, {
      subtotalCents: 3980 + 2900,
      lines: [
        ...placeInput(cartId).lines.map((line) => ({ ...line, printImageId: image.id })),
        {
          articleId: 2,
          variantId: 5,
          quantity: 1,
          priceCents: 2900,
          promptPriceCents: 0,
          promptId: null,
          printImageId: null,
        },
      ],
    })

    const placed = await place(input)

    expect(placed).toEqual({
      kind: 'placed',
      order: {
        orderId: 1,
        totalCents: 7370,
        email: 'kunde@example.com',
        phone: '+4917623123456',
        shippingAddress: ADDRESS,
        billingAddress: ADDRESS,
      },
    })
    const order = await database.db.selectFrom('orders').selectAll().executeTakeFirstOrThrow()
    expect(order).toMatchObject({
      cart_id: cartId,
      guest_session_token: 'guest-a',
      user_id: null,
      status: 'PENDING',
      billing_street: 'Lieferweg',
      subtotal_cents: 6880,
      shipping_cost_cents: 490,
      discount_cents: 0,
      total_cents: 7370,
    })
    expect(order.access_token).toMatch(/^[A-Za-z0-9_-]{43}$/)
    const items = await database.db
      .selectFrom('order_items')
      .selectAll()
      .orderBy('position')
      .execute()
    expect(items.map((item) => ({ ...item, id: 0, created_at: null }))).toEqual([
      {
        id: 0,
        order_id: 1,
        position: 1,
        article_id: 1,
        variant_id: 1,
        article_type: 'MUG',
        article_name: 'Tasse Klassik',
        variant_name: 'Weiß/Blau',
        supplier_article_number: 'MUG-001',
        print_template_width_mm: 200,
        print_template_height_mm: 80,
        document_format_width_mm: 239,
        document_format_height_mm: 99,
        document_format_margin_bottom_mm: 5,
        spod_hotspot: null,
        quantity: 2,
        price_cents: 1490,
        prompt_price_cents: 500,
        prompt_id: null,
        print_image_id: image.id,
        created_at: null,
      },
      {
        id: 0,
        order_id: 1,
        position: 2,
        article_id: 2,
        variant_id: 5,
        article_type: 'SPOD',
        article_name: 'Shirt Organic',
        variant_name: 'Black / M',
        supplier_article_number: null,
        print_template_width_mm: null,
        print_template_height_mm: null,
        document_format_width_mm: null,
        document_format_height_mm: null,
        document_format_margin_bottom_mm: null,
        spod_hotspot: 'MEDIUM_FRONT',
        quantity: 1,
        price_cents: 2900,
        prompt_price_cents: 0,
        prompt_id: null,
        print_image_id: null,
        created_at: null,
      },
    ])

    const mails = await database.db.selectFrom('email_jobs').select('payload').execute()
    expect(mails).toHaveLength(1)
    const mail = mails[0]?.payload as {
      kind: string
      recipient: string
      subject: string
      text: string
    }
    expect(mail.kind).toBe('ORDER_CONFIRMATION')
    expect(mail.recipient).toBe('kunde@example.com')
    expect(mail.subject).toBe('Bestellbestätigung #1')
    expect(mail.text).toContain(`${FRONTEND_BASE_URL}/order/${order.access_token}`)
    expect(mail.text).toContain('Shirt Organic')
  })

  test('a cart with a live order answers that stored order and writes nothing', async () => {
    const cartId = await seedCart(database.db)
    const first = await place(placeInput(cartId))
    const second = await place(
      placeInput(cartId, { email: 'other@example.com', shippingCostCents: 0 }),
    )
    expect(second).toEqual({ kind: 'alreadyPlaced', order: placedOrder(first) })
    expect(await count(database.db, 'orders')).toBe(1)
    expect(await count(database.db, 'email_jobs')).toBe(1)
  })

  test('a cancelled order frees the cart for a new placement', async () => {
    const cartId = await seedCart(database.db)
    await place(placeInput(cartId))
    expect(await (await module()).orders.cancel(1)).toBe('cancelled')
    expect((await place(placeInput(cartId))).kind).toBe('placed')
    expect(await count(database.db, 'orders')).toBe(2)
  })

  test('a line the catalog does not know refuses the whole placement', async () => {
    const cartId = await seedCart(database.db)
    const input = placeInput(cartId)
    const lines = [...input.lines, { ...input.lines[0], articleId: 9, variantId: 9 }]
    expect(await place({ ...input, lines } as PlaceOrderInput)).toEqual({ kind: 'unknownArticle' })
    expect(await count(database.db, 'orders')).toBe(0)
    expect(await count(database.db, 'email_jobs')).toBe(0)
  })

  test('the order and its mail are one commit: a failing enqueue or a rollback leaves neither', async () => {
    const cartId = await seedCart(database.db)
    const { orders } = await module()
    expect(
      await rejectionOf(
        write(database.db, async (trx) => {
          await orders.place(trx, placeInput(cartId))
          throw new Error('the checkout transaction fails afterwards')
        }),
      ),
    ).toBe('the checkout transaction fails afterwards')

    await sql`CREATE FUNCTION refuse_mail() RETURNS trigger LANGUAGE plpgsql AS
      $$ BEGIN RAISE EXCEPTION 'mail refused'; END $$`.execute(database.db)
    await sql`CREATE TRIGGER refuse_mail BEFORE INSERT ON email_jobs
      FOR EACH ROW EXECUTE FUNCTION refuse_mail()`.execute(database.db)
    try {
      expect(
        await rejectionOf(write(database.db, (trx) => orders.place(trx, placeInput(cartId)))),
      ).toBe('mail refused')
    } finally {
      await sql`DROP TRIGGER refuse_mail ON email_jobs`.execute(database.db)
      await sql`DROP FUNCTION refuse_mail()`.execute(database.db)
    }
    expect(await count(database.db, 'orders')).toBe(0)
    expect(await count(database.db, 'order_items')).toBe(0)
    expect(await count(database.db, 'email_jobs')).toBe(0)
  })
})

describe('orders.payable', () => {
  test('answers the stored order for its owner and the reason it cannot be paid otherwise', async () => {
    const { orders } = await module()
    const userId = await seedUser(database.db, 'kunde@example.com')
    const pending = await place(placeInput(await seedCart(database.db)))
    await place(placeInput(await seedCart(database.db)))
    await place(placeInput(await seedCart(database.db)))
    await place(
      placeInput(await seedCart(database.db), { shippingCostCents: 0, discountCents: 3980 }),
    )
    // An account order, placed with that browser's guest cookie as well.
    await place(placeInput(await seedCart(database.db, { userId }), { userId }))
    await write(database.db, (trx) => orders.confirmFree(trx, 2))
    await orders.cancel(3)

    expect(await orders.payable(1, null, 'guest-a')).toEqual({
      kind: 'payable',
      order: placedOrder(pending),
    })
    expect(await orders.payable(2, null, 'guest-a')).toEqual({ kind: 'alreadyPaid' })
    expect(await orders.payable(3, null, 'guest-a')).toEqual({ kind: 'cancelled' })
    expect(await orders.payable(4, null, 'guest-a')).toEqual({ kind: 'free' })
    expect(await orders.payable(1, null, 'guest-b')).toEqual({ kind: 'notFound' })
    expect(await orders.payable(99, null, 'guest-a')).toEqual({ kind: 'notFound' })
    expect(await orders.payable(1, null, null)).toEqual({ kind: 'notFound' })
    // The guest cookie a signed-in checkout left behind does not open the account's order.
    expect(await orders.payable(5, null, 'guest-a')).toEqual({ kind: 'notFound' })
    expect((await orders.payable(5, userId, null)).kind).toBe('payable')
  })
})

describe('orders.orderItem', () => {
  test('answers the reorder references to the owner only', async () => {
    const { orders } = await module()
    await place(placeInput(await seedCart(database.db)))
    expect(await orders.orderItem(1, null, 'guest-a')).toEqual({
      articleId: 1,
      variantId: 1,
      promptId: null,
      printImageId: null,
    })
    expect(await orders.orderItem(1, null, 'guest-b')).toBeUndefined()
    expect(await orders.orderItem(1, 7, null)).toBeUndefined()
    expect(await orders.orderItem(99, null, 'guest-a')).toBeUndefined()
  })
})
