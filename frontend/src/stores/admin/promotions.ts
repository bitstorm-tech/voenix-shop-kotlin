import { ref } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchJson } from '@/lib/api'

export type PromotionDiscountType = 'PERCENTAGE' | 'FIXED_AMOUNT'

/**
 * A response nests the discount, because the backend `Promotion` holds the sealed `Discount` value
 * (`docs/dev/backend/promotion-package.md`). A percentage carries at most two decimal places, a
 * fixed amount is whole cents.
 */
export interface AdminPromotionDiscountDto {
  discountType: PromotionDiscountType
  discountValue: number
}

export interface AdminPromotionDto {
  id: number
  name: string
  discount: AdminPromotionDiscountDto
  couponCode: string
  startsAt: string | null
  endsAt: string | null
  usageLimitTotal: number | null
  usageLimitPerUser: number | null
  isActive: boolean
  redemptionCount: number
  isLocked: boolean
}

/**
 * A request stays flat: `discountType` and `discountValue` sit at the top level, and the validation
 * error keys are the same flat names. The two directions are deliberately asymmetric.
 */
export interface UpsertAdminPromotionRequest {
  name: string
  discountType: PromotionDiscountType
  discountValue: number
  couponCode: string
  startsAt?: string | null
  endsAt?: string | null
  usageLimitTotal?: number | null
  usageLimitPerUser?: number | null
  isActive: boolean
}

export class PromotionNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromotionNotFoundError'
  }
}

export class PromotionCodeConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromotionCodeConflictError'
  }
}

export class PromotionLockedError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromotionLockedError'
  }
}

export class PromotionInUseError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromotionInUseError'
  }
}

export const useAdminPromotionsStore = defineStore('admin-promotions', () => {
  const promotions = ref<AdminPromotionDto[]>([])
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  function syncPromotion(promotion: AdminPromotionDto) {
    const index = promotions.value.findIndex((item) => item.id === promotion.id)
    if (index === -1) {
      promotions.value = [...promotions.value, promotion].sort(comparePromotions)
      return
    }

    promotions.value[index] = promotion
    promotions.value = [...promotions.value].sort(comparePromotions)
  }

  function removePromotion(id: number) {
    promotions.value = promotions.value.filter((promotion) => promotion.id !== id)
  }

  async function fetchPromotions() {
    if (isLoading.value) {
      return
    }

    isLoading.value = true
    error.value = null

    try {
      promotions.value = await fetchJson<AdminPromotionDto[]>('/api/admin/promotions')
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      isLoading.value = false
    }
  }

  async function fetchPromotion(id: number): Promise<AdminPromotionDto> {
    try {
      const promotion = await fetchJson<AdminPromotionDto>(`/api/admin/promotions/${id}`)
      syncPromotion(promotion)
      return promotion
    } catch (err) {
      throw toPromotionError(err, { operation: 'read' })
    }
  }

  async function createPromotion(payload: UpsertAdminPromotionRequest): Promise<AdminPromotionDto> {
    try {
      const promotion = await fetchJson<AdminPromotionDto>('/api/admin/promotions', {
        method: 'POST',
        body: payload,
      })
      syncPromotion(promotion)
      return promotion
    } catch (err) {
      throw toPromotionError(err, { operation: 'create' })
    }
  }

  async function updatePromotion(
    id: number,
    payload: UpsertAdminPromotionRequest,
  ): Promise<AdminPromotionDto> {
    // Read the known lock state *before* the call, so a concurrent list refresh cannot change the
    // answer between the refusal and its classification.
    const knownIsLocked = promotions.value.find((promotion) => promotion.id === id)?.isLocked

    try {
      const promotion = await fetchJson<AdminPromotionDto>(`/api/admin/promotions/${id}`, {
        method: 'PUT',
        body: payload,
      })
      syncPromotion(promotion)
      return promotion
    } catch (err) {
      throw toPromotionError(err, { operation: 'update', isLocked: knownIsLocked })
    }
  }

  async function deletePromotion(id: number): Promise<void> {
    try {
      await fetchJson<void>(`/api/admin/promotions/${id}`, {
        method: 'DELETE',
        responseType: 'void',
      })
      removePromotion(id)
    } catch (err) {
      throw toPromotionError(err, { operation: 'delete' })
    }
  }

  return {
    promotions,
    isLoading,
    error,
    fetchPromotions,
    fetchPromotion,
    createPromotion,
    updatePromotion,
    deletePromotion,
  }
})

function comparePromotions(left: AdminPromotionDto, right: AdminPromotionDto) {
  return left.name.localeCompare(right.name) || left.id - right.id
}

/**
 * Which call produced the refusal. A `409` means something different per operation, and the message
 * cannot tell them apart: `PUT` always answers "Coupon code is already in use or the promotion is
 * locked" and `DELETE` always answers "Promotion is still in use and cannot be deleted"
 * (`docs/dev/backend/promotion-package.md`). So the operation — plus, for an update, the `isLocked`
 * the client already knows from the representation — is the discriminator, never the message text.
 */
type PromotionOperation = 'read' | 'create' | 'update' | 'delete'

interface PromotionErrorContext {
  operation: PromotionOperation
  /** The `isLocked` of the promotion as the client last read it. Only used for an update. */
  isLocked?: boolean
}

function toPromotionError(error: unknown, context: PromotionErrorContext) {
  if (!(error instanceof ApiError)) {
    return error instanceof Error ? error : new Error('Unknown error')
  }

  if (error.status === 404) {
    return new PromotionNotFoundError(error.message)
  }

  if (error.status === 409) {
    if (context.operation === 'delete') {
      return new PromotionInUseError(error.message)
    }

    if (context.operation === 'update' && context.isLocked === true) {
      return new PromotionLockedError(error.message)
    }

    return new PromotionCodeConflictError(error.message)
  }

  // Everything else keeps its `ApiError`, so a caller can read the flat `discountValue` field
  // errors of a `400` instead of only its message.
  return error
}
