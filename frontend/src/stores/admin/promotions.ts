import { ref } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchJson } from '@/lib/api'

export type PromotionDiscountType = 'PERCENTAGE' | 'FIXED_AMOUNT'

export interface AdminPromotionDto {
  id: number
  name: string
  discountType: PromotionDiscountType
  discountValue: number
  couponCode: string
  startsAt: string | null
  endsAt: string | null
  usageLimitTotal: number | null
  usageLimitPerUser: number | null
  isActive: boolean
  redemptionCount: number
  isLocked: boolean
}

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

interface AdminPromotionListResponse {
  items: AdminPromotionDto[]
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
      const data = await fetchJson<AdminPromotionListResponse>('/api/admin/promotions')
      promotions.value = data.items
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
      throw toPromotionError(err)
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
      throw toPromotionError(err)
    }
  }

  async function updatePromotion(
    id: number,
    payload: UpsertAdminPromotionRequest,
  ): Promise<AdminPromotionDto> {
    try {
      const promotion = await fetchJson<AdminPromotionDto>(`/api/admin/promotions/${id}`, {
        method: 'PUT',
        body: payload,
      })
      syncPromotion(promotion)
      return promotion
    } catch (err) {
      throw toPromotionError(err)
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
      throw toPromotionError(err)
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

function toPromotionError(error: unknown) {
  const message = error instanceof Error ? error.message : 'Unknown error'

  if (error instanceof ApiError && error.status === 404) {
    return new PromotionNotFoundError(message)
  }

  if (error instanceof ApiError && error.status === 409) {
    if (message.toLowerCase().includes('locked')) {
      return new PromotionLockedError(message)
    }

    return new PromotionCodeConflictError(message)
  }

  return new Error(message)
}
