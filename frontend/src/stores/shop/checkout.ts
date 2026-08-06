import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchJson, type ApiFieldErrors } from '@/lib/api'
import {
  isCheckoutPromotionErrorCode,
  type CheckoutPromotionErrorCode,
} from '@/lib/checkoutPromotionErrors'

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

export interface CheckoutResult {
  orderId: number
  checkoutUrl: string | null
}

interface OrderStatusApiResponse {
  orderId: number
  status: string
  paymentStatus: string | null
  totalAmountInCents: number
}

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
  const promotionErrorCode = shallowRef<CheckoutPromotionErrorCode | null>(null)
  /**
   * Validation messages keyed by JSON path. The unshippable shipping country arrives here as
   * `shippingAddress.country` and deliberately carries no `code`
   * (`docs/dev/backend/checkout-package.md`).
   */
  const fieldErrors = ref<ApiFieldErrors>({})

  async function submitCheckout(): Promise<CheckoutResult> {
    isSubmitting.value = true
    error.value = null
    promotionErrorCode.value = null
    fieldErrors.value = {}

    try {
      const body: Record<string, unknown> = {
        shippingAddress: shippingAddress.value,
      }

      if (!sameAsShipping.value) {
        body.billingAddress = billingAddress.value
      }

      return await fetchJson<CheckoutResult>('/api/checkout', {
        method: 'POST',
        body,
      })
    } catch (err) {
      const checkoutError = toCheckoutError(err, 'Checkout failed')
      error.value = checkoutError.message
      promotionErrorCode.value = toCheckoutPromotionErrorCode(err)
      fieldErrors.value = err instanceof ApiError ? err.fieldErrors : {}
      throw checkoutError
    } finally {
      isSubmitting.value = false
    }
  }

  async function fetchOrderStatus(orderId: number): Promise<OrderStatusApiResponse> {
    const data = await fetchJson<OrderStatusApiResponse>(`/api/checkout/orders/${orderId}`).catch(
      (err) => {
        throw toCheckoutError(err, 'Failed to load order status')
      },
    )
    return {
      ...data,
      status: data.status.toLowerCase(),
      paymentStatus: data.paymentStatus?.toLowerCase() ?? data.paymentStatus,
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
    promotionErrorCode.value = null
    fieldErrors.value = {}
  }

  return {
    shippingAddress,
    billingAddress,
    sameAsShipping,
    isSubmitting,
    error,
    promotionErrorCode,
    fieldErrors,
    clearFieldError,
    submitCheckout,
    fetchOrderStatus,
    $reset,
  }
})

function toCheckoutPromotionErrorCode(error: unknown): CheckoutPromotionErrorCode | null {
  const code = error instanceof ApiError ? error.details?.code : null
  return isCheckoutPromotionErrorCode(code) ? code : null
}

function toCheckoutError(error: unknown, fallback: string) {
  if (error instanceof ApiError) {
    const hasParsedMessage = Boolean(
      error.details?.detail || error.details?.message || error.details?.title,
    )
    return new Error(hasParsedMessage ? error.message : `HTTP ${error.status}`)
  }

  return error instanceof Error ? error : new Error(fallback)
}
