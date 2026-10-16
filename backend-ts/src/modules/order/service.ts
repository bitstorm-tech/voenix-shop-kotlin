import { randomBytes } from 'node:crypto'
import type { Transaction } from 'kysely'
import { catalogKey, type ArticleCatalog } from '@/modules/article'
import { renderOrderConfirmation, type EmailModule } from '@/modules/email'
import type { ImageModule } from '@/modules/image'
import type { ProductionData, ShippingNotificationOrder } from '@/modules/production'
import type { Tables } from '@/platform/db/database'
import { notFound, ok, type OperationResult } from '@/platform/operation'
import type { PaymentService } from './payment/service'
import {
  berlinDate,
  insertOrder,
  liveOrderOfCart,
  toPayableOrder,
  type OrderRepository,
  type OrderView,
  type PayableOrder,
  type PlaceOrderInput,
} from './repository'

/**
 * What a placement ends in. `alreadyPlaced` is a success: the cart has a live order, and the
 * answer is that stored order, never the request just made; `alreadyPaid` is that same case for a
 * live order that is already `PAID`, which has nothing left to pay. `unknownArticle` wrote nothing.
 */
export type PlaceOrderResult =
  | { kind: 'placed'; order: PayableOrder }
  | { kind: 'alreadyPlaced'; order: PayableOrder }
  | { kind: 'alreadyPaid'; order: PayableOrder }
  | { kind: 'unknownArticle' }

/**
 * The one read this module needs of the cart's `print_images` table, declared here as a type and
 * implemented by the cart module (`createPrintImageReads`), which depends on this one and can
 * therefore not be asked directly: the composition root hands the implementation in. No ownership
 * rule applies; the caller is the production read, never a customer.
 */
export interface PrintImageReads {
  /** The stored file name of every known id in `imageIds`; unknown ids are absent. */
  filenames(imageIds: Iterable<number>): Promise<Map<number, string>>
}

/**
 * Why an order can or cannot start a second payment journey. `notFound` covers unknown *and*
 * foreign ids, so nobody can probe for somebody else's order.
 */
export type PayableResult =
  | { kind: 'payable'; order: PayableOrder }
  | { kind: 'notFound' }
  | { kind: 'alreadyPaid' }
  | { kind: 'cancelled' }
  | { kind: 'free' }

/** 32 random bytes are 43 URL-safe Base64 characters: what a token looks like, and nothing else. */
const ACCESS_TOKEN = /^[A-Za-z0-9_-]{43}$/

export type OrderService = ReturnType<typeof createOrderService>

/**
 * What an order *means*: who may read it, what a placement snapshots and sets in motion, and the
 * three reads production makes of a paid order.
 */
export function createOrderService({
  repository,
  articleCatalog,
  email,
  images,
  printImages,
  payments,
  frontendBaseUrl,
}: {
  repository: OrderRepository
  articleCatalog: Pick<ArticleCatalog, 'find'>
  email: { outbox: Pick<EmailModule['outbox'], 'enqueue'> }
  images: Pick<ImageModule, 'privateStorage'>
  printImages: PrintImageReads
  payments: Pick<PaymentService, 'stored' | 'refreshed'>
  frontendBaseUrl: string
}) {
  /** The permanent page of one order; the token is URL-safe, so nothing needs escaping. */
  const orderUrl = (accessToken: string) => `${frontendBaseUrl}/order/${accessToken}`

  return {
    /** The caller's orders with their stored payment status: one batch read, never a provider call. */
    history: async (userId: number | null, guestToken: string | null): Promise<OrderView[]> => {
      const orders = await repository.history(userId, guestToken)
      const statuses = await payments.stored(orders.map((order) => order.orderId))
      return orders.map((order) => ({
        ...order,
        paymentStatus: statuses.get(order.orderId) ?? null,
      }))
    },

    /**
     * One order of the caller, its payment refreshed from Mollie while it is under way. The
     * ownership-filtered read comes first, so no refresh ever runs for a foreign order; when the
     * refresh itself paid the order, the order is read again so the answer does not say `PENDING`
     * next to `PAID`.
     */
    order: async (
      orderId: number,
      userId: number | null,
      guestToken: string | null,
    ): Promise<OperationResult<OrderView>> => {
      const order = await repository.order(orderId, userId, guestToken)
      if (order === undefined) return notFound
      const paymentStatus = await payments.refreshed(orderId)
      const current =
        paymentStatus === 'PAID' && order.status === 'PENDING'
          ? ((await repository.order(orderId, userId, guestToken)) ?? order)
          : order
      return ok({ ...current, paymentStatus })
    },

    /**
     * The order behind a mail link. A string not shaped like a token is answered before the
     * database is touched, exactly like an unknown one; the status is the stored one, never a
     * refresh, because an anonymous request must not drive provider calls.
     */
    orderByToken: async (token: string): Promise<OperationResult<OrderView>> => {
      const order = ACCESS_TOKEN.test(token) ? await repository.orderByToken(token) : undefined
      if (order === undefined) return notFound
      const statuses = await payments.stored([order.orderId])
      return ok({ ...order, paymentStatus: statuses.get(order.orderId) ?? null })
    },

    /**
     * Places one order inside the caller's transaction (checkout TX1, which holds the cart row
     * lock): the live order of the cart if it has one, otherwise the catalog snapshot of every line,
     * the order with its lines and access token, and the confirmation mail, rendered now and
     * enqueued in the same transaction, so an order and its mail are one committed fact. Without
     * the caller's cart lock a concurrent placement fails on `ux_orders_live_cart` and throws.
     */
    place: async (trx: Transaction<Tables>, input: PlaceOrderInput): Promise<PlaceOrderResult> => {
      const live = await liveOrderOfCart(trx, input.cartId)
      if (live !== undefined) {
        return { kind: live.status === 'PAID' ? 'alreadyPaid' : 'alreadyPlaced', order: live.order }
      }
      const catalog = await articleCatalog.find(input.lines)
      if (input.lines.some((line) => !catalog.has(catalogKey(line)))) {
        return { kind: 'unknownArticle' }
      }
      const snapshotOf = (line: PlaceOrderInput['lines'][number]) => {
        const snapshot = catalog.get(catalogKey(line))
        if (snapshot === undefined) throw new Error('Every ordered line was resolved above')
        return snapshot
      }
      const accessToken = randomBytes(32).toString('base64url')
      const { orderId, createdAt } = await insertOrder(trx, input, accessToken, snapshotOf)
      const billingAddress = input.billingAddress ?? input.shippingAddress
      const totalCents = input.subtotalCents + input.shippingCostCents - input.discountCents
      await email.outbox.enqueue(
        trx,
        renderOrderConfirmation({
          recipient: input.email,
          orderId,
          orderDate: berlinDate(createdAt),
          orderUrl: orderUrl(accessToken),
          customerFirstName: input.shippingAddress.firstName,
          shippingAddress: input.shippingAddress,
          billingAddress,
          items: input.lines.map((line) => ({
            articleName: snapshotOf(line).articleName,
            variantName: snapshotOf(line).variantName,
            quantity: line.quantity,
            unitPriceInCents: line.priceCents + line.promptPriceCents,
          })),
          subtotalInCents: input.subtotalCents,
          shippingCostInCents: input.shippingCostCents,
          discountInCents: input.discountCents,
          totalInCents: totalCents,
        }),
        { orderId },
      )
      return {
        kind: 'placed',
        order: {
          orderId,
          cartId: input.cartId,
          totalCents,
          email: input.email,
          phone: input.phone,
          shippingAddress: input.shippingAddress,
          billingAddress,
        },
      }
    },

    /** The order a payment is started for again, under the same ownership rule as the reads. */
    payable: async (
      orderId: number,
      userId: number | null,
      guestToken: string | null,
    ): Promise<PayableResult> => {
      const row = await repository.payable(orderId, userId, guestToken)
      if (row === undefined) return { kind: 'notFound' }
      if (row.status === 'PAID') return { kind: 'alreadyPaid' }
      if (row.status === 'CANCELLED') return { kind: 'cancelled' }
      if (row.total_cents === 0) return { kind: 'free' }
      return { kind: 'payable', order: toPayableOrder(row) }
    },

    /**
     * The production view of one order: the snapshot of every line plus the absolute path of its
     * print image, the file name from the cart module and the path from the image module. A file
     * the storage cannot answer for leaves `imagePath` `null`, which production records as
     * retryable.
     */
    productionData: async (orderId: number): Promise<ProductionData | null> => {
      const order = await repository.storedOrder(orderId)
      if (order === undefined) return null
      const filenames = await printImages.filenames(
        order.lines.flatMap((line) => (line.printImageId === null ? [] : [line.printImageId])),
      )
      const paths = await images.privateStorage.originalPaths(filenames.values())
      const { shippingAddress: shipping } = order
      return {
        orderId: order.orderId,
        orderDate: order.orderDate,
        customerEmail: order.email,
        customerPhone: order.phone,
        shippingFirstName: shipping.firstName,
        shippingLastName: shipping.lastName,
        shippingStreet: shipping.street,
        shippingHouseNumber: shipping.houseNumber,
        shippingPostalCode: shipping.postalCode,
        shippingCity: shipping.city,
        shippingCountry: shipping.country,
        lines: order.lines.map(({ printImageId, ...line }) => {
          const filename = printImageId === null ? undefined : filenames.get(printImageId)
          return {
            ...line,
            imagePath: filename === undefined ? null : (paths.get(filename) ?? null),
          }
        }),
      }
    },

    /** Whom a shipping mail goes to, with the finished link; the token never crosses the boundary. */
    shippingNotificationOrder: async (
      orderId: number,
    ): Promise<ShippingNotificationOrder | null> => {
      const order = await repository.storedOrder(orderId)
      if (order === undefined) return null
      return {
        recipientEmail: order.email,
        customerFirstName: order.shippingAddress.firstName,
        orderUrl: orderUrl(order.accessToken),
      }
    },
  }
}
