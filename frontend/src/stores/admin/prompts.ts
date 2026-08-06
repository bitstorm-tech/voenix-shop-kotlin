import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchForm, fetchJson } from '@/lib/api'
import type { AdminPriceDto, AdminPriceInputDto } from '@/stores/admin/prices'

export interface AdminPromptCategoryDto {
  id: number
  name: string
  position: number
}

export interface AdminPromptSubcategoryDto {
  id: number
  name: string
  position: number
}

export interface AdminPromptPriceDto {
  salesTotalNet: number
  salesTotalGross: number
  salesTotalTax: number
  salesVatRatePercent: number
}

export interface AdminPromptListItemDto {
  id: number
  position: number
  title: string
  category: AdminPromptCategoryDto
  subcategory?: AdminPromptSubcategoryDto
  exampleImageFilename?: string | null
  llm?: string | null
  price?: AdminPromptPriceDto
  active: boolean
  archived: boolean
}

export interface AdminPromptDetailDto {
  id: number
  position: number
  title: string
  promptText: string
  category: AdminPromptCategoryDto
  subcategory?: AdminPromptSubcategoryDto
  exampleImageFilename?: string | null
  llm?: string | null
  price: AdminPriceDto
  active: boolean
  archived: boolean
  slotVariantIds: number[]
}

export interface AdminPromptRequest {
  title: string
  promptText: string
  llm?: string | null
  exampleImageFilename?: string | null
  active: boolean
  archived: boolean
  categoryId: number
  subcategoryId?: number | null
  slotVariantIds: number[]
  price: AdminPriceInputDto
}

export type CreateAdminPromptRequest = AdminPromptRequest
export type AdminUpdatePromptRequest = AdminPromptRequest

export interface ReorderAdminPromptsRequest {
  sourcePromptId: number
  targetPromptId: number
}

export class PromptOrderConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptOrderConflictError'
  }
}

export class PromptCreateConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptCreateConflictError'
  }
}

export class PromptNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptNotFoundError'
  }
}

export type PromptSaveErrorSection = 'prompt' | 'price'

export class PromptSaveError extends Error {
  readonly section: PromptSaveErrorSection

  constructor(message: string, section: PromptSaveErrorSection) {
    super(message)
    this.name = 'PromptSaveError'
    this.section = section
  }
}

function hasPriceValidationErrors(error: ApiError) {
  const errors = error.details?.errors
  if (errors === null || typeof errors !== 'object' || Array.isArray(errors)) {
    return false
  }

  return Object.keys(errors).some((key) => {
    const normalizedKey = key.toLowerCase()
    return normalizedKey === 'price' || normalizedKey.startsWith('price.')
  })
}

function toPromptSaveError(error: unknown) {
  const message = error instanceof Error ? error.message : 'Unknown error'
  const section =
    error instanceof ApiError &&
    (error.details?.code === 'invalid_price_request' || hasPriceValidationErrors(error))
      ? 'price'
      : 'prompt'

  return new PromptSaveError(message, section)
}

export const useAdminPromptsStore = defineStore('admin-prompts', () => {
  const prompts = ref<AdminPromptListItemDto[]>([])
  const isLoading = shallowRef(false)
  const isReordering = shallowRef(false)
  const error = shallowRef<string | null>(null)
  let pendingPromptListRequest: Promise<void> | null = null

  function sortPrompts(items: AdminPromptListItemDto[]) {
    return [...items].sort((a, b) => a.position - b.position || a.id - b.id)
  }

  function syncPromptList(items: AdminPromptListItemDto[]) {
    prompts.value = sortPrompts(items)
  }

  function syncPromptListItem(prompt: AdminPromptDetailDto) {
    const index = prompts.value.findIndex((item) => item.id === prompt.id)
    if (index === -1) {
      return
    }

    prompts.value[index] = {
      id: prompt.id,
      position: prompt.position,
      title: prompt.title,
      category: prompt.category,
      subcategory: prompt.subcategory,
      exampleImageFilename: prompt.exampleImageFilename,
      llm: prompt.llm,
      price: {
        salesTotalNet: prompt.price.salesTotal.net,
        salesTotalGross: prompt.price.salesTotal.gross,
        salesTotalTax: prompt.price.salesTotal.tax,
        salesVatRatePercent: prompt.price.salesVat.percent,
      },
      active: prompt.active,
      archived: prompt.archived,
    }
  }

  function fetchPrompts(): Promise<void> {
    if (pendingPromptListRequest !== null) {
      return pendingPromptListRequest
    }

    pendingPromptListRequest = (async () => {
      isLoading.value = true
      error.value = null

      try {
        const data = await fetchJson<{ items: AdminPromptListItemDto[] }>('/api/admin/prompts')
        syncPromptList(data.items)
      } catch (err) {
        error.value = err instanceof Error ? err.message : 'Unknown error'
      } finally {
        isLoading.value = false
        pendingPromptListRequest = null
      }
    })()

    return pendingPromptListRequest
  }

  async function refreshPrompts(): Promise<void> {
    if (pendingPromptListRequest !== null) {
      await pendingPromptListRequest
    }

    await fetchPrompts()
  }

  async function fetchPrompt(id: number): Promise<AdminPromptDetailDto> {
    try {
      return await fetchJson<AdminPromptDetailDto>(`/api/admin/prompts/${id}`)
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        throw new PromptNotFoundError(error.message)
      }
      throw error
    }
  }

  async function updatePrompt(
    id: number,
    payload: AdminUpdatePromptRequest,
  ): Promise<AdminPromptDetailDto> {
    try {
      const prompt = await fetchJson<AdminPromptDetailDto>(`/api/admin/prompts/${id}`, {
        method: 'PUT',
        body: payload,
      })
      syncPromptListItem(prompt)
      return prompt
    } catch (error) {
      throw toPromptSaveError(error)
    }
  }

  async function createPrompt(payload: CreateAdminPromptRequest): Promise<AdminPromptDetailDto> {
    try {
      return await fetchJson<AdminPromptDetailDto>('/api/admin/prompts', {
        method: 'POST',
        body: payload,
      })
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        throw new PromptCreateConflictError(error.message)
      }
      throw toPromptSaveError(error)
    }
  }

  async function uploadExampleImage(file: File): Promise<string> {
    const formData = new FormData()
    formData.append('file', file)
    const data = await fetchForm<{ filename: string }>(
      '/api/admin/prompts/example-images',
      formData,
    )
    return data.filename
  }

  async function reorderPrompts(
    sourcePromptId: number,
    targetPromptId: number,
  ): Promise<AdminPromptListItemDto[]> {
    const payload: ReorderAdminPromptsRequest = { sourcePromptId, targetPromptId }

    isReordering.value = true
    try {
      const data = await fetchJson<{ items: AdminPromptListItemDto[] }>(
        '/api/admin/prompts/order',
        { method: 'PUT', body: payload },
      )
      syncPromptList(data.items)
      return prompts.value
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        throw new PromptOrderConflictError(error.message)
      }
      throw error
    } finally {
      isReordering.value = false
    }
  }

  return {
    prompts,
    isLoading,
    isReordering,
    error,
    fetchPrompts,
    refreshPrompts,
    fetchPrompt,
    createPrompt,
    updatePrompt,
    uploadExampleImage,
    reorderPrompts,
  }
})
