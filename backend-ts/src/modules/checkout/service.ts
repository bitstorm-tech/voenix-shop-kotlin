import type { Kysely, Transaction } from 'kysely'
import type { CheckoutCart, CheckoutCarts } from '@/modules/cart'
import type { CountryModule } from '@/modules/country'
import type { OrderAddress, Orders, PayableOrder, PlaceOrderInput } from '@/modules/order'
import type { PromotionCodes, PromotionRejection } from '@/modules/promotion'
import { write, type Tables } from '@/platform/db/database'
import { createLogger } from '@/platform/logging'

/** One postal address as the request carries it; the country is already upper-cased. */
export interface CheckoutAddressInput {
  firstName: string
  lastName: string
  street: string
  houseNumber: string
  postalCode: string
  city: string
  country: string
}

/**
 * What a customer sends to buy the contents of their cart: where it goes, how to reach them about
 * it, and optionally where the invoice goes. Nothing about the money is in here: the prices, the
 * shipping cost, the coupon, and the total are read from the stored cart, never from the client.
 * `phone` is `null` when the customer left it empty; `billingAddress: null` means "same address".
 */
export interface CheckoutInput {
  shippingAddress: CheckoutAddressInput & { email: string; phone: string | null }
  billingAddress: CheckoutAddressInput | null
}

/**
 * Everything a checkout, or a retried payment, can end in. A result of its own rather than the
 * shared `OperationResult`, because the reason a checkout stopped is the only thing that tells a
 * customer what to do next. `shippingCountryUnavailable` and `phoneRequired` are the two refusals
 * the customer can fix at the form, so the routes answer them as field errors.
 */
export type CheckoutResult =
  /** The order exists and, unless it is free (`checkoutUrl: null`), so does the payment. */
  | { kind: 'started'; orderId: number; checkoutUrl: string | null }
  /** No identity, no active cart, or a cart without a line: all the same sentence. */
  | { kind: 'emptyCart' }
  | { kind: 'shippingCountryUnavailable' }
  /** The cart contains a SPOD article and the request carries no phone number. */
  | { kind: 'phoneRequired' }
  /** The coupon the cart carries could not be reserved; the promotion module's own reason. */
  | { kind: 'promotionRejected'; rejection: PromotionRejection }
  /** A line names an article variant the catalog no longer has. */
  | { kind: 'itemUnavailable' }
  /** The cart's amounts do not fit the integer cents an order stores. */
  | { kind: 'totalTooLarge' }
  /** The provider started no payment; the order is cancelled and the cart stays active. */
  | { kind: 'paymentNotStarted' }
  /** The order does not exist or belongs to somebody else, deliberately indistinguishable. */
  | { kind: 'orderNotFound' }
  | { kind: 'alreadyPaid' }
  /** Cancelled or free: it will never be paid through a retry. */
  | { kind: 'notPayable' }

/** What TX1 ends in: a refusal, a free order already settled, or a payable order to start. */
type Placement = CheckoutResult | { kind: 'payable'; order: PayableOrder }

/** The `integer` columns of an order hold at most this many cents. */
const MAX_ORDER_CENTS = 2_147_483_647

export type CheckoutService = ReturnType<typeof createCheckoutService>

/**
 * The checkout in two transactions (#247). The read-only guards run first, so a checkout that could
 * never succeed writes nothing. **TX1** holds the cart row lock while it reserves the coupon, places
 * the order with its confirmation mail, and, for a free order, confirms it and closes the cart. The
 * provider call runs outside any transaction. **TX2** closes the cart once a payment exists. A
 * provider refusal leaves the cart active: `orders.startPayment` has cancelled the order and
 * released the reservation, and the customer's next attempt finds their cart again.
 *
 * The guest token is read, never minted, and never logged: the only identifier that reaches a log
 * line here is the order id.
 */
export function createCheckoutService({
  db,
  carts,
  promotionCodes,
  orders,
  countries,
}: {
  db: Kysely<Tables>
  carts: CheckoutCarts
  promotionCodes: Pick<PromotionCodes, 'reserve' | 'release'>
  orders: Pick<Orders, 'place' | 'payable' | 'confirmFree' | 'startPayment'>
  countries: Pick<CountryModule, 'isShippable'>
}) {
  const logger = createLogger('checkout')

  /**
   * `false` means the cart was closed by a concurrent checkout of the very same cart (two tabs, or
   * a retried submission) while this one ran. The order is placed and settled either way, so this
   * is a warning, not an error.
   */
  const closeCart = async (trx: Transaction<Tables>, cartId: number) => {
    if (!(await carts.markCheckedOut(trx, cartId))) {
      logger.warn({ cartId }, 'Cart was no longer active when its checkout closed it')
    }
  }

  /** TX1: lock, reserve, place, and settle a free order. */
  const place = (cart: CheckoutCart, request: CheckoutInput): Promise<Placement> =>
    write(db, async (trx) => {
      // The snapshot was read before the lock; a cart closed in between has its order already,
      // and placing from the stale snapshot would create a second one.
      if (!(await carts.lockForCheckout(trx, cart.cartId))) return { kind: 'emptyCart' }
      let reserved = false
      if (cart.promotionId !== null) {
        const reservation = await promotionCodes.reserve(
          trx,
          cart.promotionId,
          cart.cartId,
          cart.userId,
        )
        if (reservation.kind !== 'applicable') {
          return { kind: 'promotionRejected', rejection: reservation }
        }
        reserved = true
      }
      const placed = await orders.place(trx, placeOrderInput(cart, request))
      if (placed.kind === 'unknownArticle') {
        // No order exists and none ever will for this cart as it stands: the hold is given back
        // now, because a reservation has no expiry and a customer who gives up never frees it.
        if (reserved) await promotionCodes.release(trx, cart.cartId)
        return { kind: 'itemUnavailable' }
      }
      // `alreadyPlaced` takes the same path: both submissions are answered with the one order.
      const { order } = placed
      if (order.totalCents > 0) return { kind: 'payable', order }
      const confirmation = await orders.confirmFree(trx, order.orderId)
      if (confirmation !== 'paid' && confirmation !== 'alreadyPaid') {
        throw new Error(
          `Free order ${String(order.orderId)} could not be confirmed: ${confirmation}`,
        )
      }
      await closeCart(trx, cart.cartId)
      return { kind: 'started', orderId: order.orderId, checkoutUrl: null }
    })

  return {
    /** Turns the caller's active cart into an order and, unless that order is free, a payment. */
    checkout: async (
      guestToken: string | null,
      userId: number | null,
      request: CheckoutInput,
    ): Promise<CheckoutResult> => {
      if (guestToken === null && userId === null) return { kind: 'emptyCart' }
      const cart = await carts.activeCart(guestToken, userId)
      if (cart === null || cart.lines.length === 0) return { kind: 'emptyCart' }
      if (cart.subtotalCents + cart.shippingCostCents > MAX_ORDER_CENTS) {
        return { kind: 'totalTooLarge' }
      }
      // Only the shipping address is checked; an invoice may go anywhere.
      if (!(await countries.isShippable(request.shippingAddress.country))) {
        return { kind: 'shippingCountryUnavailable' }
      }
      // The print-on-demand partner refuses an order without a phone number. What decides is the
      // resolved type of the stored lines; a line the catalog no longer resolves is not a SPOD
      // article here and is refused as unavailable by the placement a moment later.
      if (
        request.shippingAddress.phone === null &&
        cart.lines.some((line) => line.articleType === 'SPOD')
      ) {
        return { kind: 'phoneRequired' }
      }

      const placement = await place(cart, request)
      if (placement.kind !== 'payable') return placement
      const { order } = placement
      logger.info({ orderId: order.orderId }, 'Checkout proceeds with order')
      const checkoutUrl = await orders.startPayment(order)
      if (checkoutUrl === null) return { kind: 'paymentNotStarted' }
      await write(db, (trx) => closeCart(trx, cart.cartId))
      return { kind: 'started', orderId: order.orderId, checkoutUrl }
    },

    /**
     * Starts the payment of an already placed order again. No cart is read and none is closed;
     * whatever cart the order came from was closed when it was placed.
     */
    startPayment: async (
      orderId: number,
      guestToken: string | null,
      userId: number | null,
    ): Promise<CheckoutResult> => {
      const payable = await orders.payable(orderId, userId, guestToken)
      switch (payable.kind) {
        case 'notFound':
          return { kind: 'orderNotFound' }
        case 'alreadyPaid':
          return { kind: 'alreadyPaid' }
        case 'cancelled':
        case 'free':
          return { kind: 'notPayable' }
        case 'payable': {
          const checkoutUrl = await orders.startPayment(payable.order)
          return checkoutUrl === null
            ? { kind: 'paymentNotStarted' }
            : { kind: 'started', orderId, checkoutUrl }
        }
      }
    },
  }
}

/**
 * The placement input: the stored cart owns the lines and the four amounts the customer was shown
 * (the discount included, calculated by the cart from the coupon TX1 has just reserved), the
 * request owns the two addresses and the contact.
 */
function placeOrderInput(cart: CheckoutCart, request: CheckoutInput): PlaceOrderInput {
  return {
    cartId: cart.cartId,
    userId: cart.userId,
    guestToken: cart.guestToken,
    promotionId: cart.promotionId,
    shippingAddress: toOrderAddress(request.shippingAddress),
    billingAddress: request.billingAddress === null ? null : toOrderAddress(request.billingAddress),
    email: request.shippingAddress.email,
    phone: request.shippingAddress.phone,
    subtotalCents: cart.subtotalCents,
    shippingCostCents: cart.shippingCostCents,
    discountCents: cart.discountCents,
    lines: cart.lines,
  }
}

const toOrderAddress = ({
  firstName,
  lastName,
  street,
  houseNumber,
  postalCode,
  city,
  country,
}: CheckoutAddressInput): OrderAddress => ({
  firstName,
  lastName,
  street,
  houseNumber,
  postalCode,
  city,
  country,
})
