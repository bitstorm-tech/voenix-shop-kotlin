import { computed, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchForm, fetchJson } from '@/lib/api'

export interface CartItem {
  id: number
  articleId: number
  variantId: number
  articleName: string
  variantName: string
  price: number
  originalPrice: number
  quantity: number
  outsideColorCode: string
  insideColorCode: string
  generatedEditedImageId: number | null
  promptId: number | null
  promptPrice: number
  promptOriginalPrice: number
  customData: string
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

interface CartResponse {
  id: number | null
  items: CartItem[]
  subtotal: number
  shippingCost: number
  discountAmount: number
  total: number
  totalItems: number
  appliedPromotion: AppliedPromotion | null
}

function toPromotionApplicationErrorCode(error: unknown): PromotionApplicationErrorCode {
  const code = error instanceof ApiError ? error.details?.code : null
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

  const totalItems = computed(() => items.value.reduce((sum, item) => sum + item.quantity, 0))

  const isEmpty = computed(() => items.value.length === 0)

  function applyCartResponse(data: CartResponse) {
    items.value = data.items
    subtotal.value = data.subtotal
    shippingCost.value = data.shippingCost
    discountAmount.value = data.discountAmount
    totalPrice.value = data.total
    appliedPromotion.value = data.appliedPromotion
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
      const data = await fetchJson<CartResponse>('/api/cart')
      applyCartResponse(data)
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load cart'
    } finally {
      isLoading.value = false
    }
  }

  async function addToCart(
    request: {
      articleId: number
      variantId: number
      quantity: number
      promptId?: number | null
      generatedEditedImageId?: number | null
      customData?: string | null
    },
    imageBlob?: Blob | null,
  ): Promise<void> {
    const formData = new FormData()
    formData.append('articleId', String(request.articleId))
    formData.append('variantId', String(request.variantId))
    formData.append('quantity', String(request.quantity))
    if (request.promptId != null) {
      formData.append('promptId', String(request.promptId))
    }
    if (request.generatedEditedImageId != null) {
      formData.append('generatedEditedImageId', String(request.generatedEditedImageId))
    }
    if (request.customData != null) {
      formData.append('customData', request.customData)
    }
    if (imageBlob) {
      formData.append('image', imageBlob, 'design.png')
    }

    const data = await fetchForm<CartResponse>('/api/cart/items', formData)
    applyCartResponse(data)
  }

  async function reorderOrderItem(orderItemId: number): Promise<void> {
    const data = await fetchJson<CartResponse>(`/api/cart/order-items/${orderItemId}`, {
      method: 'POST',
    })
    applyCartResponse(data)
  }

  async function updateQuantity(id: number, quantity: number) {
    try {
      const data = await fetchJson<CartResponse>(`/api/cart/items/${id}`, {
        method: 'PATCH',
        body: { quantity },
      })
      applyCartResponse(data)
    } catch (err) {
      if (err instanceof ApiError) {
        return
      }

      throw err
    }
  }

  async function removeItem(id: number) {
    try {
      const data = await fetchJson<CartResponse>(`/api/cart/items/${id}`, { method: 'DELETE' })
      applyCartResponse(data)
    } catch (err) {
      if (err instanceof ApiError) {
        return
      }

      throw err
    }
  }

  async function clearCart() {
    const ids = items.value.map((item) => item.id)
    for (const id of ids) {
      await removeItem(id)
    }
  }

  async function applyPromotion(promotionCode: string) {
    isPromotionLoading.value = true
    promotionErrorCode.value = null
    try {
      const data = await fetchJson<CartResponse>('/api/cart/promotion', {
        method: 'POST',
        body: { promotionCode },
      })
      applyCartResponse(data)
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
      const data = await fetchJson<CartResponse>('/api/cart/promotion', { method: 'DELETE' })
      applyCartResponse(data)
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
    isEmpty,
    formatPrice,
    fetchCart,
    addToCart,
    reorderOrderItem,
    removeItem,
    updateQuantity,
    clearCart,
    applyPromotion,
    removePromotion,
  }
})
