import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { fetchJson, type ApiFieldErrors } from '@/lib/api'
import { toCheckoutError, type CheckoutErrorCode } from '@/lib/checkoutErrors'
import type { OrderStatusSnapshot } from './orders'

export interface Address {
  firstName: string
  lastName: string
  street: string
  houseNumber: string
  city: string
  postalCode: string
  country: string
  email: string
  phone: string
}

export type AddressInput = { [Field in keyof Address]?: Address[Field] | null }

/**
 * Both checkout routes answer this one shape: `POST /api/checkout` with `201` and
 * `POST /api/checkout/orders/{orderId}/payment` with `200`. `checkoutUrl` is `null` for a free order
 * that is already confirmed — there is nothing to pay (`docs/dev/backend/checkout-package.md`).
 */
export interface CheckoutResult {
  orderId: number
  checkoutUrl: string | null
}

/**
 * The order vocabulary lives in the orders store, which owns the `/api/orders` contract. Checkout
 * only reads it, so it re-exports the three types instead of declaring a second pair.
 */
export type { OrderPaymentStatus, OrderStatus, OrderStatusSnapshot } from './orders'

/**
 * The country starts empty on purpose: the shippable list comes from `GET /api/countries` and a
 * hardcoded `'DE'` would pretend an answer the form does not have yet
 * (`docs/dev/backend/checkout-package.md`).
 */
export function createEmptyAddress(): Address {
  return {
    firstName: '',
    lastName: '',
    street: '',
    houseNumber: '',
    city: '',
    postalCode: '',
    country: '',
    email: '',
    phone: '',
  }
}

export function normalizeAddress(address: AddressInput | null | undefined): Address {
  const emptyAddress = createEmptyAddress()

  return {
    firstName: address?.firstName ?? emptyAddress.firstName,
    lastName: address?.lastName ?? emptyAddress.lastName,
    street: address?.street ?? emptyAddress.street,
    houseNumber: address?.houseNumber ?? emptyAddress.houseNumber,
    city: address?.city ?? emptyAddress.city,
    postalCode: address?.postalCode ?? emptyAddress.postalCode,
    country: address?.country || emptyAddress.country,
    email: address?.email ?? emptyAddress.email,
    phone: address?.phone ?? emptyAddress.phone,
  }
}

export const useCheckoutStore = defineStore('checkout', () => {
  const shippingAddress = ref<Address>(createEmptyAddress())
  const billingAddress = ref<Address>(createEmptyAddress())
  const sameAsShipping = shallowRef(true)
  const isSubmitting = shallowRef(false)
  const error = shallowRef<string | null>(null)
  /** The stable `code` of the last refusal, or `null` when it carried none (a field error). */
  const errorCode = shallowRef<CheckoutErrorCode | null>(null)
  /**
   * Validation messages keyed by JSON path. The unshippable shipping country arrives here as
   * `shippingAddress.country` and deliberately carries no `code`
   * (`docs/dev/backend/checkout-package.md`).
   */
  const fieldErrors = ref<ApiFieldErrors>({})

  async function submitCheckout(): Promise<CheckoutResult> {
    const body: Record<string, unknown> = {
      shippingAddress: shippingAddress.value,
    }

    if (!sameAsShipping.value) {
      body.billingAddress = billingAddress.value
    }

    return runCheckoutRequest('/api/checkout', body, 'Checkout failed')
  }

  /**
   * Asks for a payment of an order that was already placed. The route takes **no body**: everything
   * the payment needs is what the order stored. A live payment answers its stored URL; a paid,
   * cancelled, or free order is a `409` with `ORDER_ALREADY_PAID` or `ORDER_NOT_PAYABLE`, and an
   * unknown or foreign order id is a `404`.
   */
  async function startPayment(orderId: number): Promise<CheckoutResult> {
    return runCheckoutRequest(
      `/api/checkout/orders/${orderId}/payment`,
      undefined,
      'Payment could not be started',
    )
  }

  async function runCheckoutRequest(
    path: string,
    body: Record<string, unknown> | undefined,
    fallbackMessage: string,
  ): Promise<CheckoutResult> {
    isSubmitting.value = true
    error.value = null
    errorCode.value = null
    fieldErrors.value = {}

    try {
      return await fetchJson<CheckoutResult>(path, { method: 'POST', body })
    } catch (err) {
      const checkoutError = toCheckoutError(err, fallbackMessage)
      error.value = checkoutError.message
      errorCode.value = checkoutError.code
      fieldErrors.value = checkoutError.fieldErrors
      throw checkoutError
    } finally {
      isSubmitting.value = false
    }
  }

  /**
   * The order detail is the status source, and reading it is also what repairs a missed webhook: the
   * backend refreshes a still-running payment from Mollie while answering. There is no client
   * payment endpoint: the payment module's only route is `POST /api/payments/webhook/{secret}`,
   * which Mollie calls and a browser never does (`docs/migration/payment-post-migration.md`).
   */
  async function fetchOrderStatus(orderId: number): Promise<OrderStatusSnapshot> {
    const order = await fetchJson<OrderStatusSnapshot>(`/api/orders/${orderId}`).catch((err) => {
      throw toCheckoutError(err, 'Failed to load order status')
    })

    return {
      orderId: order.orderId,
      status: order.status,
      paymentStatus: order.paymentStatus,
      total: order.total,
    }
  }

  /** Drops the server message for one field, e.g. when the customer picks another country. */
  function clearFieldError(field: string) {
    if (!fieldErrors.value[field]) {
      return
    }

    fieldErrors.value = Object.fromEntries(
      Object.entries(fieldErrors.value).filter(([path]) => path !== field),
    )
  }

  function $reset() {
    shippingAddress.value = createEmptyAddress()
    billingAddress.value = createEmptyAddress()
    sameAsShipping.value = true
    isSubmitting.value = false
    error.value = null
    errorCode.value = null
    fieldErrors.value = {}
  }

  return {
    shippingAddress,
    billingAddress,
    sameAsShipping,
    isSubmitting,
    error,
    errorCode,
    fieldErrors,
    clearFieldError,
    submitCheckout,
    startPayment,
    fetchOrderStatus,
    $reset,
  }
})
