import { computed, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchForm, fetchJson } from '@/lib/api'

/**
 * One line of the rendered cart, exactly as the Kotlin `CartLine` serializes it
 * (`docs/dev/backend/cart-package.md`).
 *
 * `price` and `promptPrice` are the snapshots the line was quoted at; the names and the two color
 * codes are current master data and are `null` when the article catalog no longer answers for the
 * reference. Such a line renders with `available = false` instead of disappearing, so the customer
 * sees what they put in and why they cannot buy it.
 */
export interface CartItem {
  id: number
  articleId: number
  variantId: number
  articleName: string | null
  variantName: string | null
  outsideColorCode: string | null
  insideColorCode: string | null
  available: boolean
  price: number
  quantity: number
  imageId: number | null
  promptId: number | null
  promptPrice: number
}

export type PromotionDiscountType = 'PERCENTAGE' | 'FIXED_AMOUNT'

export type PromotionApplicationErrorCode =
  | 'PROMOTION_INVALID_CODE'
  | 'PROMOTION_INACTIVE'
  | 'PROMOTION_NOT_STARTED'
  | 'PROMOTION_EXPIRED'
  | 'PROMOTION_TOTAL_EXHAUSTED'
  | 'PROMOTION_PER_USER_EXHAUSTED'
  | 'PROMOTION_LOGIN_REQUIRED'
  | 'PROMOTION_APPLICATION_FAILED'
  | 'PROMOTION_REMOVE_FAILED'

export interface AppliedPromotion {
  id: number
  name: string
  promotionCode: string
  discountType: PromotionDiscountType
  discountValue: number
}

/** The complete recalculated cart every cart route answers with, the upload apart. */
export interface CartView {
  id: number | null
  items: CartItem[]
  subtotal: number
  shippingCost: number
  discountAmount: number
  total: number
  totalItems: number
  appliedPromotion: AppliedPromotion | null
}

/** The answer of the print-image pre-upload: `POST /api/cart/images` → `201 {"id": 42}`. */
interface PrintImageId {
  id: number
}

export interface AddCartItemRequest {
  articleId: number
  variantId: number
  quantity: number
  promptId?: number | null
  imageId?: number | null
}

/**
 * Which half of the two-step add failed.
 *
 * The upload mints the print image, the line references it by id. A failed upload therefore never
 * reaches the line request — only a minted id is ever submitted — and the two failures mean
 * different things to the customer: the file was refused, or the line was.
 */
export type CartAddStep = 'image-upload' | 'line'

export class CartAddError extends Error {
  readonly step: CartAddStep
  readonly cause: unknown

  constructor(step: CartAddStep, cause: unknown) {
    super(cause instanceof Error ? cause.message : `Cart add failed at step ${step}`)
    this.name = 'CartAddError'
    this.step = step
    this.cause = cause
  }
}

/** The one conflict a cart operation reports: the print image of an ordered line cannot be used. */
export const ORDER_IMAGE_UNAVAILABLE = 'ORDER_IMAGE_UNAVAILABLE'

/**
 * Whether a failed reorder means "this image is gone". The useful reaction is offering a fresh
 * upload, not a retry (`docs/migration/order-post-migration.md`).
 */
export function isOrderImageUnavailable(error: unknown): boolean {
  return error instanceof ApiError && error.code === ORDER_IMAGE_UNAVAILABLE
}

function toPromotionApplicationErrorCode(error: unknown): PromotionApplicationErrorCode {
  const code = error instanceof ApiError ? error.code : null
  switch (code) {
    case 'PROMOTION_INVALID_CODE':
    case 'PROMOTION_INACTIVE':
    case 'PROMOTION_NOT_STARTED':
    case 'PROMOTION_EXPIRED':
    case 'PROMOTION_TOTAL_EXHAUSTED':
    case 'PROMOTION_PER_USER_EXHAUSTED':
    case 'PROMOTION_LOGIN_REQUIRED':
      return code
    default:
      return 'PROMOTION_APPLICATION_FAILED'
  }
}

export const useCartStore = defineStore('cart', () => {
  const items = ref<CartItem[]>([])
  const isLoading = shallowRef(false)
  const error = shallowRef<string | null>(null)
  const subtotal = shallowRef(0)
  const shippingCost = shallowRef(0)
  const discountAmount = shallowRef(0)
  const totalPrice = shallowRef(0)
  const appliedPromotion = shallowRef<AppliedPromotion | null>(null)
  const isPromotionLoading = shallowRef(false)
  const promotionErrorCode = shallowRef<PromotionApplicationErrorCode | null>(null)
  /** The message of the last failed quantity change or removal, until the next one succeeds. */
  const mutationError = shallowRef<string | null>(null)

  const totalItems = computed(() => items.value.reduce((sum, item) => sum + item.quantity, 0))

  const isEmpty = computed(() => items.value.length === 0)

  const hasUnavailableItem = computed(() => items.value.some((item) => !item.available))

  /**
   * Adopts a `CartView` wholesale. No mutation answers a partial cart, because shipping thresholds
   * and discount caps are server rules the browser cannot recompute.
   */
  function applyCartView(cart: CartView) {
    items.value = cart.items
    subtotal.value = cart.subtotal
    shippingCost.value = cart.shippingCost
    discountAmount.value = cart.discountAmount
    totalPrice.value = cart.total
    appliedPromotion.value = cart.appliedPromotion
  }

  function formatPrice(priceInCents: number): string {
    return (priceInCents / 100).toLocaleString('de-DE', {
      style: 'currency',
      currency: 'EUR',
    })
  }

  async function fetchCart() {
    isLoading.value = true
    error.value = null
    try {
      applyCartView(await fetchJson<CartView>('/api/cart'))
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load cart'
    } finally {
      isLoading.value = false
    }
  }

  /** Step one of an add: stores the print image and answers the id the line references. */
  async function uploadPrintImage(imageBlob: Blob): Promise<number> {
    const formData = new FormData()
    formData.append('file', imageBlob, 'design.png')

    const { id } = await fetchForm<PrintImageId>('/api/cart/images', formData)
    return id
  }

  /**
   * Adds a line, in the two steps the Kotlin cart takes: the optional image is minted first, and
   * only a minted id is submitted with the JSON line. Both failures are reported as a
   * [CartAddError] that names the step, so the caller can tell the customer which half went wrong.
   */
  async function addToCart(request: AddCartItemRequest, imageBlob?: Blob | null): Promise<void> {
    let imageId = request.imageId ?? null

    if (imageBlob) {
      try {
        imageId = await uploadPrintImage(imageBlob)
      } catch (err) {
        throw new CartAddError('image-upload', err)
      }
    }

    try {
      const cart = await fetchJson<CartView>('/api/cart/items', {
        method: 'POST',
        body: {
          articleId: request.articleId,
          variantId: request.variantId,
          quantity: request.quantity,
          promptId: request.promptId ?? null,
          imageId,
        },
      })
      applyCartView(cart)
    } catch (err) {
      throw new CartAddError('line', err)
    }
  }

  /**
   * Reorders an ordered line. The new line always has quantity 1 and today's catalog price, and a
   * `409` with `ORDER_IMAGE_UNAVAILABLE` is passed on for the caller to offer a fresh upload.
   * Guests may reorder their own order's lines.
   */
  async function reorderOrderItem(orderItemId: number): Promise<void> {
    const cart = await fetchJson<CartView>(`/api/cart/order-items/${orderItemId}`, {
      method: 'POST',
    })
    applyCartView(cart)
  }

  async function runItemMutation(request: () => Promise<CartView>): Promise<boolean> {
    mutationError.value = null
    try {
      applyCartView(await request())
      return true
    } catch (err) {
      if (err instanceof ApiError) {
        mutationError.value = err.message
        return false
      }

      throw err
    }
  }

  function updateQuantity(id: number, quantity: number): Promise<boolean> {
    return runItemMutation(() =>
      fetchJson<CartView>(`/api/cart/items/${id}`, {
        method: 'PATCH',
        body: { quantity },
      }),
    )
  }

  function removeItem(id: number): Promise<boolean> {
    return runItemMutation(() => fetchJson<CartView>(`/api/cart/items/${id}`, { method: 'DELETE' }))
  }

  /** Removes the lines one by one and stops at the first refusal, which stays on `mutationError`. */
  async function clearCart(): Promise<boolean> {
    for (const id of items.value.map((item) => item.id)) {
      if (!(await removeItem(id))) {
        return false
      }
    }

    return true
  }

  function clearMutationError() {
    mutationError.value = null
  }

  async function applyPromotion(promotionCode: string) {
    isPromotionLoading.value = true
    promotionErrorCode.value = null
    try {
      applyCartView(
        await fetchJson<CartView>('/api/cart/promotion', {
          method: 'POST',
          body: { promotionCode },
        }),
      )
    } catch (err) {
      promotionErrorCode.value = toPromotionApplicationErrorCode(err)
    } finally {
      isPromotionLoading.value = false
    }
  }

  async function removePromotion() {
    isPromotionLoading.value = true
    promotionErrorCode.value = null
    try {
      applyCartView(await fetchJson<CartView>('/api/cart/promotion', { method: 'DELETE' }))
    } catch {
      promotionErrorCode.value = 'PROMOTION_REMOVE_FAILED'
    } finally {
      isPromotionLoading.value = false
    }
  }

  return {
    items,
    isLoading,
    error,
    totalItems,
    subtotal,
    shippingCost,
    discountAmount,
    totalPrice,
    appliedPromotion,
    isPromotionLoading,
    promotionErrorCode,
    mutationError,
    isEmpty,
    hasUnavailableItem,
    formatPrice,
    fetchCart,
    uploadPrintImage,
    addToCart,
    reorderOrderItem,
    removeItem,
    updateQuantity,
    clearCart,
    clearMutationError,
    applyPromotion,
    removePromotion,
  }
})
