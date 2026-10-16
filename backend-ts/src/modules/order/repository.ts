import { sql, type ExpressionBuilder, type Kysely, type Transaction } from 'kysely'
import type { ArticleType, CatalogVariant } from '@/modules/article'
import type { FulfillmentOrder } from '@/modules/production'
import { read, type Tables } from '@/platform/db/database'
import type { PaymentStatus } from './payment/mollie'
import type { OrderStatus, OrdersTable } from './schema'
import './schema'

/** One postal address as an order stores it; street and house number stay two fields. */
export interface OrderAddress {
  firstName: string
  lastName: string
  street: string
  houseNumber: string
  postalCode: string
  city: string
  country: string
}

/**
 * A placed order as a payment is built from it: the amount, the customer, the two stored
 * addresses, and the cart it came from (which a successful payment start closes), nothing else.
 * Both a placement and the retry read answer this shape, built from the stored columns either way.
 * `billingAddress` is always present: "same address" was resolved into the billing columns at
 * placement.
 */
export interface PayableOrder {
  orderId: number
  cartId: number
  totalCents: number
  email: string
  phone: string | null
  shippingAddress: OrderAddress
  billingAddress: OrderAddress
}

/** One line of an order answer, exactly as it was ordered. */
export interface OrderLineView {
  orderItemId: number
  articleId: number
  variantId: number
  articleType: ArticleType
  articleName: string
  variantName: string
  quantity: number
  price: number
  promptPrice: number
  imageId: number | null
}

/**
 * The one representation the history, the detail read, and the token lookup answer. Every amount
 * is stored, never recalculated; `paymentStatus` is the one value from another table, `null` when
 * the order has no payment at all (a free order, or one whose payment was never started).
 */
export interface OrderView {
  orderId: number
  createdAt: string
  status: OrderStatus
  paymentStatus: PaymentStatus | null
  subtotal: number
  shippingCost: number
  discountAmount: number
  total: number
  items: OrderLineView[]
}

type OrderRecord = Omit<OrderView, 'paymentStatus'>

/** The references a reorder builds a new cart line from; no price, no quantity. */
export interface OrderItemReference {
  articleId: number
  variantId: number
  promptId: number | null
  printImageId: number | null
}

/** The whole stored order the two worker reads (production, shipping mail) are built from. */
export interface StoredOrder extends PayableOrder {
  /** The bearer credential behind the permanent link; never log it, never answer it. */
  accessToken: string
  orderDate: string
  lines: StoredLine[]
}

export interface StoredLine {
  articleId: number
  variantId: number
  articleName: string
  variantName: string
  supplierArticleNumber: string | null
  quantity: number
  /** What the customer paid for one piece: the variant's price plus the prompt's, in cents. */
  unitPriceCents: number
  printImageId: number | null
  printTemplateWidthMm: number | null
  printTemplateHeightMm: number | null
  documentFormatWidthMm: number | null
  documentFormatHeightMm: number | null
  documentFormatMarginBottomMm: number | null
  spodHotspot: string | null
}

/** One line to place: what was chosen and the prices the customer was quoted in the cart. */
export interface PlaceOrderLine {
  articleId: number
  variantId: number
  quantity: number
  priceCents: number
  promptPriceCents: number
  promptId: number | null
  printImageId: number | null
}

/**
 * Everything one placement needs, already decided and validated by the checkout. The order
 * stores `total = subtotal + shipping - discount`, never a passed-in total; `billingAddress: null`
 * means "same as shipping".
 */
export interface PlaceOrderInput {
  cartId: number
  userId: number | null
  guestToken: string | null
  promotionId: number | null
  shippingAddress: OrderAddress
  billingAddress: OrderAddress | null
  email: string
  phone: string | null
  subtotalCents: number
  shippingCostCents: number
  discountCents: number
  lines: PlaceOrderLine[]
}

export type OrderRepository = ReturnType<typeof createOrderRepository>

const payableColumns = [
  'id',
  'cart_id',
  'total_cents',
  'email',
  'phone',
  'shipping_first_name',
  'shipping_last_name',
  'shipping_street',
  'shipping_house_number',
  'shipping_postal_code',
  'shipping_city',
  'shipping_country',
  'billing_first_name',
  'billing_last_name',
  'billing_street',
  'billing_house_number',
  'billing_postal_code',
  'billing_city',
  'billing_country',
] as const

const recordColumns = [
  'id',
  'created_at',
  'status',
  'subtotal_cents',
  'shipping_cost_cents',
  'discount_cents',
  'total_cents',
] as const

export function createOrderRepository(db: Kysely<Tables>) {
  return {
    /** The caller's orders, newest first; ties break on the id. */
    history: (userId: number | null, guestToken: string | null) =>
      read(db, async (trx) => {
        const rows = await trx
          .selectFrom('orders')
          .select(recordColumns)
          .where(readable(userId, guestToken))
          .orderBy('created_at', 'desc')
          .orderBy('id', 'desc')
          .execute()
        return withLines(trx, rows)
      }),

    /** One order of the caller; `undefined` for an unknown *and* a foreign id. */
    order: (orderId: number, userId: number | null, guestToken: string | null) =>
      read(db, async (trx) => {
        const rows = await trx
          .selectFrom('orders')
          .select(recordColumns)
          .where('id', '=', orderId)
          .where(readable(userId, guestToken))
          .execute()
        return (await withLines(trx, rows))[0]
      }),

    /** The one order a token opens. The token is the credential, so no ownership rule applies. */
    orderByToken: (token: string) =>
      read(db, async (trx) => {
        const rows = await trx
          .selectFrom('orders')
          .select(recordColumns)
          .where('access_token', '=', token)
          .execute()
        return (await withLines(trx, rows))[0]
      }),

    /** One order of the caller with its status, for the retry-payment journey. */
    payable: (orderId: number, userId: number | null, guestToken: string | null) =>
      read(db, (trx) =>
        trx
          .selectFrom('orders')
          .select([...payableColumns, 'status'])
          .where('id', '=', orderId)
          .where(readable(userId, guestToken))
          .executeTakeFirst(),
      ),

    /** The reorder references of one ordered line, or `undefined` when unknown or foreign. */
    orderItem: (
      orderItemId: number,
      userId: number | null,
      guestToken: string | null,
    ): Promise<OrderItemReference | undefined> =>
      read(db, (trx) =>
        trx
          .selectFrom('order_items')
          .innerJoin('orders', 'orders.id', 'order_items.order_id')
          .select([
            'order_items.article_id as articleId',
            'order_items.variant_id as variantId',
            'order_items.prompt_id as promptId',
            'order_items.print_image_id as printImageId',
          ])
          .where('order_items.id', '=', orderItemId)
          .where(readable(userId, guestToken))
          .executeTakeFirst(),
      ),

    /**
     * The whole stored order without an ownership rule: its callers are the production worker and
     * the shipping mail, never a customer, and no route reaches it. A line carries its print image
     * id only; the file name is the cart module's to answer (see `PrintImageReads`).
     */
    storedOrder: (orderId: number): Promise<StoredOrder | undefined> =>
      read(db, async (trx) => {
        const row = await trx
          .selectFrom('orders')
          .select([...payableColumns, 'access_token', 'created_at'])
          .where('id', '=', orderId)
          .executeTakeFirst()
        if (row === undefined) return undefined
        const lines = await trx
          .selectFrom('order_items')
          .select([
            'article_id as articleId',
            'variant_id as variantId',
            'article_name as articleName',
            'variant_name as variantName',
            'supplier_article_number as supplierArticleNumber',
            'quantity',
            (eb) => eb('price_cents', '+', eb.ref('prompt_price_cents')).as('unitPriceCents'),
            'print_image_id as printImageId',
            'print_template_width_mm as printTemplateWidthMm',
            'print_template_height_mm as printTemplateHeightMm',
            'document_format_width_mm as documentFormatWidthMm',
            'document_format_height_mm as documentFormatHeightMm',
            'document_format_margin_bottom_mm as documentFormatMarginBottomMm',
            'spod_hotspot as spodHotspot',
          ])
          .where('order_id', '=', orderId)
          .orderBy('position')
          .execute()
        return {
          ...toPayableOrder(row),
          accessToken: row.access_token,
          orderDate: berlinDate(row.created_at),
          lines,
        }
      }),

    /** Exactly the nine fields a supplier may see, for a whole page of orders in one statement. */
    fulfillmentOrders: async (
      orderIds: Iterable<number>,
    ): Promise<Map<number, FulfillmentOrder>> => {
      const wanted = [...orderIds]
      if (wanted.length === 0) return new Map()
      const rows = await read(db, (trx) =>
        trx
          .selectFrom('orders')
          .select([
            'id',
            'created_at',
            'shipping_first_name',
            'shipping_last_name',
            'shipping_street',
            'shipping_house_number',
            'shipping_postal_code',
            'shipping_city',
            'shipping_country',
          ])
          .where('id', 'in', wanted)
          .execute(),
      )
      return new Map(
        rows.map((row) => [
          row.id,
          {
            orderId: row.id,
            orderDate: berlinDate(row.created_at),
            customerFirstName: row.shipping_first_name,
            customerLastName: row.shipping_last_name,
            shippingStreet: row.shipping_street,
            shippingHouseNumber: row.shipping_house_number,
            shippingPostalCode: row.shipping_postal_code,
            shippingCity: row.shipping_city,
            shippingCountry: row.shipping_country,
          },
        ]),
      )
    },
  }
}

/** The live (not cancelled) order of a cart with its status, read inside the caller's transaction. */
export async function liveOrderOfCart(
  trx: Transaction<Tables>,
  cartId: number,
): Promise<{ order: PayableOrder; status: OrderStatus } | undefined> {
  const row = await trx
    .selectFrom('orders')
    .select([...payableColumns, 'status'])
    .where('cart_id', '=', cartId)
    .where('status', '<>', 'CANCELLED')
    .executeTakeFirst()
  return row === undefined ? undefined : { order: toPayableOrder(row), status: row.status }
}

/**
 * Writes the order and its lines in the customer's order (`position`) inside the caller's
 * transaction, snapshotting what `snapshots` says about every line. The caller resolved every line
 * before; a missing snapshot is a bug and throws.
 */
export async function insertOrder(
  trx: Transaction<Tables>,
  input: PlaceOrderInput,
  accessToken: string,
  snapshotOf: (line: PlaceOrderLine) => CatalogVariant,
): Promise<{ orderId: number; createdAt: Date }> {
  const billing = input.billingAddress ?? input.shippingAddress
  const shipping = input.shippingAddress
  const { id, created_at } = await trx
    .insertInto('orders')
    .values({
      cart_id: input.cartId,
      guest_session_token: input.guestToken,
      user_id: input.userId,
      promotion_id: input.promotionId,
      access_token: accessToken,
      status: 'PENDING',
      shipping_first_name: shipping.firstName,
      shipping_last_name: shipping.lastName,
      shipping_street: shipping.street,
      shipping_house_number: shipping.houseNumber,
      shipping_postal_code: shipping.postalCode,
      shipping_city: shipping.city,
      shipping_country: shipping.country,
      billing_first_name: billing.firstName,
      billing_last_name: billing.lastName,
      billing_street: billing.street,
      billing_house_number: billing.houseNumber,
      billing_postal_code: billing.postalCode,
      billing_city: billing.city,
      billing_country: billing.country,
      email: input.email,
      phone: input.phone,
      subtotal_cents: input.subtotalCents,
      shipping_cost_cents: input.shippingCostCents,
      discount_cents: input.discountCents,
      total_cents: input.subtotalCents + input.shippingCostCents - input.discountCents,
    })
    .returning(['id', 'created_at'])
    .executeTakeFirstOrThrow()
  await trx
    .insertInto('order_items')
    .values(
      input.lines.map((line, index) => {
        const snapshot = snapshotOf(line)
        return {
          order_id: id,
          position: index + 1,
          article_id: line.articleId,
          variant_id: line.variantId,
          article_type: snapshot.articleType,
          article_name: snapshot.articleName,
          variant_name: snapshot.variantName,
          supplier_article_number: snapshot.supplierArticleNumber,
          print_template_width_mm: snapshot.printTemplateWidthMm,
          print_template_height_mm: snapshot.printTemplateHeightMm,
          document_format_width_mm: snapshot.documentFormatWidthMm,
          document_format_height_mm: snapshot.documentFormatHeightMm,
          document_format_margin_bottom_mm: snapshot.documentFormatMarginBottomMm,
          spod_hotspot: snapshot.spodHotspot,
          quantity: line.quantity,
          price_cents: line.priceCents,
          prompt_price_cents: line.promptPriceCents,
          prompt_id: line.promptId,
          print_image_id: line.printImageId,
        }
      }),
    )
    .execute()
  return { orderId: id, createdAt: created_at }
}

/**
 * Locks the order row with `SELECT … FOR UPDATE` before its status is read, so two writers of one
 * order (a confirmation, a cancellation, a payment start) queue up instead of both deciding from
 * `PENDING`. The lock order of this module is always orders, then payments, then promotions.
 */
export function lockOrder(trx: Transaction<Tables>, orderId: number) {
  return trx
    .selectFrom('orders')
    .select(['status', 'cart_id as cartId', 'user_id as userId', 'promotion_id as promotionId'])
    .where('id', '=', orderId)
    .forUpdate()
    .executeTakeFirst()
}

export async function setOrderStatus(
  trx: Transaction<Tables>,
  orderId: number,
  status: OrderStatus,
): Promise<void> {
  await trx
    .updateTable('orders')
    .set({ status, updated_at: sql<Date>`now()` })
    .where('id', '=', orderId)
    .execute()
}

/**
 * "This order is the caller's": the signed-in customer it belongs to, or the guest token it was
 * placed with while it has no user. The `user_id IS NULL` half keeps the guest cookie a signed-in
 * checkout left behind from opening the account's order. No identity at all matches nothing.
 */
function readable(userId: number | null, guestToken: string | null) {
  return (eb: ExpressionBuilder<Tables, 'orders'>) => {
    const branches = []
    if (userId !== null) branches.push(eb('orders.user_id', '=', userId))
    if (guestToken !== null) {
      branches.push(
        eb.and([
          eb('orders.user_id', 'is', null),
          eb('orders.guest_session_token', '=', guestToken),
        ]),
      )
    }
    return eb.or(branches)
  }
}

/** The order records with their lines, one statement for all lines. */
async function withLines(
  trx: Transaction<Tables>,
  rows: {
    id: number
    created_at: Date
    status: OrderStatus
    subtotal_cents: number
    shipping_cost_cents: number
    discount_cents: number
    total_cents: number
  }[],
): Promise<OrderRecord[]> {
  if (rows.length === 0) return []
  const lines = await trx
    .selectFrom('order_items')
    .select([
      'order_id',
      'id as orderItemId',
      'article_id as articleId',
      'variant_id as variantId',
      'article_type as articleType',
      'article_name as articleName',
      'variant_name as variantName',
      'quantity',
      'price_cents as price',
      'prompt_price_cents as promptPrice',
      'print_image_id as imageId',
    ])
    .where(
      'order_id',
      'in',
      rows.map((row) => row.id),
    )
    .orderBy('position')
    .execute()
  const linesOf = new Map<number, OrderLineView[]>()
  for (const { order_id, ...line } of lines) {
    linesOf.set(order_id, [...(linesOf.get(order_id) ?? []), line])
  }
  return rows.map((row) => ({
    orderId: row.id,
    createdAt: row.created_at.toISOString(),
    status: row.status,
    subtotal: row.subtotal_cents,
    shippingCost: row.shipping_cost_cents,
    discountAmount: row.discount_cents,
    total: row.total_cents,
    items: linesOf.get(row.id) ?? [],
  }))
}

type PayableRow = { id: number } & Pick<OrdersTable, Exclude<(typeof payableColumns)[number], 'id'>>

export function toPayableOrder(row: PayableRow): PayableOrder {
  return {
    orderId: row.id,
    cartId: row.cart_id,
    totalCents: row.total_cents,
    email: row.email,
    phone: row.phone,
    shippingAddress: {
      firstName: row.shipping_first_name,
      lastName: row.shipping_last_name,
      street: row.shipping_street,
      houseNumber: row.shipping_house_number,
      postalCode: row.shipping_postal_code,
      city: row.shipping_city,
      country: row.shipping_country,
    },
    billingAddress: {
      firstName: row.billing_first_name,
      lastName: row.billing_last_name,
      street: row.billing_street,
      houseNumber: row.billing_house_number,
      postalCode: row.billing_postal_code,
      city: row.billing_city,
      country: row.billing_country,
    },
  }
}

const BERLIN_DATE = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Europe/Berlin',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
})

/**
 * The `Europe/Berlin` calendar day of an instant, `YYYY-MM-DD`: an order placed at 00:30 Berlin
 * time belongs to that Berlin day. Production, the supplier pages, and the mail all take the date
 * from here, so they can never name different days.
 */
export function berlinDate(instant: Date): string {
  return BERLIN_DATE.format(instant)
}
