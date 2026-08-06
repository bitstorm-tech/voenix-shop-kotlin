import { ApiError, type ApiFieldErrors } from '@/lib/api'

/**
 * Every machine-readable `code` the checkout routes answer with, mapped to the message the customer
 * reads. The table is `docs/dev/backend/checkout-package.md`; the `PROMOTION_*` half comes from the
 * promotion module, so a coupon refused in the cart and the same coupon refused while the checkout
 * reserves it reach the customer as the very same sentence.
 *
 * Two refusals of the checkout deliberately carry **no** code and are therefore not listed here: the
 * unshippable shipping country (a field error on `shippingAddress.country`) and the `404` of the
 * retry route.
 */
export const checkoutErrorKeys = {
  PROMOTION_INVALID_CODE: 'checkout.errors.promotion.invalidCode',
  PROMOTION_INACTIVE: 'checkout.errors.promotion.inactive',
  PROMOTION_NOT_STARTED: 'checkout.errors.promotion.notStarted',
  PROMOTION_EXPIRED: 'checkout.errors.promotion.expired',
  PROMOTION_TOTAL_EXHAUSTED: 'checkout.errors.promotion.totalExhausted',
  PROMOTION_PER_USER_EXHAUSTED: 'checkout.errors.promotion.perUserExhausted',
  PROMOTION_LOGIN_REQUIRED: 'checkout.errors.promotion.loginRequired',
  CART_EMPTY: 'checkout.errors.cartEmpty',
  CART_ITEM_UNAVAILABLE: 'checkout.errors.itemUnavailable',
  CART_IMAGE_UNAVAILABLE: 'checkout.errors.imageUnavailable',
  CART_TOTAL_TOO_LARGE: 'checkout.errors.totalTooLarge',
  /**
   * `502`: the payment was not started. The backend cannot tell whether the provider refused (the
   * order is already cancelled) or a race left the order `PENDING`, so the copy claims nothing about
   * the order. What is certain is that the cart stays `ACTIVE`, so trying again is the offer.
   */
  PAYMENT_NOT_STARTED: 'checkout.errors.paymentNotStarted',
  ORDER_ALREADY_PAID: 'checkout.errors.orderAlreadyPaid',
  ORDER_NOT_PAYABLE: 'checkout.errors.orderNotPayable',
} as const

export type CheckoutErrorCode = keyof typeof checkoutErrorKeys

export function isCheckoutErrorCode(value: unknown): value is CheckoutErrorCode {
  return typeof value === 'string' && value in checkoutErrorKeys
}

/**
 * A refusal of a checkout route, reduced to what a caller branches on: the stable `code`, the HTTP
 * status, and the validation messages keyed by JSON path.
 */
export class CheckoutError extends Error {
  readonly code: CheckoutErrorCode | null
  readonly status: number | null
  readonly fieldErrors: ApiFieldErrors

  constructor(
    message: string,
    options: {
      code?: CheckoutErrorCode | null
      status?: number | null
      fieldErrors?: ApiFieldErrors
    } = {},
  ) {
    super(message)
    this.name = 'CheckoutError'
    this.code = options.code ?? null
    this.status = options.status ?? null
    this.fieldErrors = options.fieldErrors ?? {}
  }
}

/**
 * Translates whatever a request threw into a {@link CheckoutError}. The backend answers one error
 * shape, so `message`, `code`, and `errors` are read from the parsed body via `ApiError` instead of
 * from route-specific extras.
 */
export function toCheckoutError(error: unknown, fallbackMessage: string): CheckoutError {
  if (error instanceof CheckoutError) {
    return error
  }

  if (error instanceof ApiError) {
    return new CheckoutError(error.message, {
      code: isCheckoutErrorCode(error.code) ? error.code : null,
      status: error.status,
      fieldErrors: error.fieldErrors,
    })
  }

  return new CheckoutError(error instanceof Error ? error.message : fallbackMessage)
}
