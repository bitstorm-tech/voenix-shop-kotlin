import { computed, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchJson } from '@/lib/api'

export interface AdminPromptSlotTypeDto {
  id: number
  name: string
  position: number
  variantCount: number
}

export interface AdminPromptSlotTypeSummaryDto {
  id: number
  name: string
  position: number
}

export interface AdminPromptSlotVariantDto {
  id: number
  slotType: AdminPromptSlotTypeSummaryDto
  name: string
  prompt: string
  description: string | null
  llm: string | null
  assignedPromptCount: number
}

export type AdminPromptSlotVariantDetailDto = AdminPromptSlotVariantDto

export interface CreateAdminPromptSlotTypeRequest {
  name: string
}

export interface UpdateAdminPromptSlotTypeRequest {
  name: string
}

export interface CreateAdminPromptSlotVariantRequest {
  slotTypeId: number
  name: string
  prompt: string
  description?: string | null
  llm?: string | null
}

export interface UpdateAdminPromptSlotVariantRequest {
  name: string
  prompt: string
  description?: string | null
  llm?: string | null
}

export class PromptSlotTypeNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptSlotTypeNotFoundError'
  }
}

export class PromptSlotTypeNameConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptSlotTypeNameConflictError'
  }
}

export class PromptSlotTypeInUseError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptSlotTypeInUseError'
  }
}

export class PromptSlotVariantNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptSlotVariantNotFoundError'
  }
}

export class PromptSlotVariantNameConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptSlotVariantNameConflictError'
  }
}

export class PromptSlotVariantInUseError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptSlotVariantInUseError'
  }
}

export class PromptSlotVariantSlotTypeNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptSlotVariantSlotTypeNotFoundError'
  }
}

export const useAdminPromptSlotsStore = defineStore('admin-prompt-slots', () => {
  const slotTypes = ref<AdminPromptSlotTypeDto[]>([])
  const slotVariants = ref<AdminPromptSlotVariantDto[]>([])
  const isLoadingSlotTypes = shallowRef(false)
  const isLoadingSlotVariants = shallowRef(false)
  const error = shallowRef<string | null>(null)

  const isLoading = computed(() => isLoadingSlotTypes.value || isLoadingSlotVariants.value)
  const variantsBySlotTypeId = computed(() => {
    return slotVariants.value.reduce<Record<number, AdminPromptSlotVariantDto[]>>(
      (groups, variant) => {
        const slotTypeId = variant.slotType.id
        groups[slotTypeId] = [...(groups[slotTypeId] ?? []), variant]
        return groups
      },
      {},
    )
  })

  function sortSlotTypes(items: AdminPromptSlotTypeDto[]) {
    return [...items].sort((a, b) => a.position - b.position || a.id - b.id)
  }

  function sortSlotVariants(items: AdminPromptSlotVariantDto[]) {
    return [...items].sort((a, b) => {
      return (
        a.slotType.position - b.slotType.position ||
        a.slotType.id - b.slotType.id ||
        a.name.localeCompare(b.name) ||
        a.id - b.id
      )
    })
  }

  function toSlotTypeSummary(slotType: AdminPromptSlotTypeDto): AdminPromptSlotTypeSummaryDto {
    return {
      id: slotType.id,
      name: slotType.name,
      position: slotType.position,
    }
  }

  function syncSlotType(slotType: AdminPromptSlotTypeDto) {
    const index = slotTypes.value.findIndex((item) => item.id === slotType.id)
    if (index === -1) {
      slotTypes.value = sortSlotTypes([...slotTypes.value, slotType])
    } else {
      const nextSlotTypes = [...slotTypes.value]
      nextSlotTypes[index] = slotType
      slotTypes.value = sortSlotTypes(nextSlotTypes)
    }

    const slotTypeSummary = toSlotTypeSummary(slotType)
    slotVariants.value = sortSlotVariants(
      slotVariants.value.map((variant) =>
        variant.slotType.id === slotType.id ? { ...variant, slotType: slotTypeSummary } : variant,
      ),
    )
  }

  function syncSlotVariant(variant: AdminPromptSlotVariantDto) {
    const index = slotVariants.value.findIndex((item) => item.id === variant.id)
    if (index === -1) {
      slotVariants.value = sortSlotVariants([...slotVariants.value, variant])
      return
    }

    const nextSlotVariants = [...slotVariants.value]
    nextSlotVariants[index] = variant
    slotVariants.value = sortSlotVariants(nextSlotVariants)
  }

  function removeSlotType(id: number) {
    slotTypes.value = slotTypes.value.filter((slotType) => slotType.id !== id)
    slotVariants.value = slotVariants.value.filter((variant) => variant.slotType.id !== id)
  }

  function removeSlotVariant(id: number) {
    slotVariants.value = slotVariants.value.filter((variant) => variant.id !== id)
  }

  function isPromptSlotTypeNotFoundMessage(message: string) {
    return /slot type not found|prompt slot type not found/i.test(message)
  }

  function isPromptSlotTypePositionConflictMessage(message: string) {
    return /position/i.test(message)
  }

  function toSlotTypeLoadOrSaveError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 404) {
      return new PromptSlotTypeNotFoundError(message)
    }

    if (error.status === 409 && !isPromptSlotTypePositionConflictMessage(message)) {
      return new PromptSlotTypeNameConflictError(message)
    }

    return new Error(message)
  }

  function toSlotTypeDeleteError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 404) {
      return new PromptSlotTypeNotFoundError(message)
    }

    if (error.status === 409) {
      return new PromptSlotTypeInUseError(message)
    }

    return new Error(message)
  }

  function toSlotVariantLoadError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (error instanceof ApiError && error.status === 404) {
      return new PromptSlotVariantNotFoundError(message)
    }

    return new Error(message)
  }

  function toSlotVariantSaveError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 404) {
      return isPromptSlotTypeNotFoundMessage(message)
        ? new PromptSlotVariantSlotTypeNotFoundError(message)
        : new PromptSlotVariantNotFoundError(message)
    }

    if (error.status === 409) {
      return new PromptSlotVariantNameConflictError(message)
    }

    return new Error(message)
  }

  function toSlotVariantDeleteError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 404) {
      return new PromptSlotVariantNotFoundError(message)
    }

    if (error.status === 409) {
      return new PromptSlotVariantInUseError(message)
    }

    return new Error(message)
  }

  async function fetchSlotTypes() {
    if (isLoadingSlotTypes.value) {
      return
    }

    isLoadingSlotTypes.value = true
    error.value = null

    try {
      const data = await fetchJson<{ items: AdminPromptSlotTypeDto[] }>(
        '/api/admin/prompts/slot-types',
      )
      slotTypes.value = sortSlotTypes(data.items)
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      isLoadingSlotTypes.value = false
    }
  }

  async function fetchSlotType(id: number): Promise<AdminPromptSlotTypeDto> {
    try {
      const slotType = await fetchJson<AdminPromptSlotTypeDto>(
        `/api/admin/prompts/slot-types/${id}`,
      )
      syncSlotType(slotType)
      return slotType
    } catch (err) {
      throw toSlotTypeLoadOrSaveError(err)
    }
  }

  async function createSlotType(
    payload: CreateAdminPromptSlotTypeRequest,
  ): Promise<AdminPromptSlotTypeDto> {
    try {
      const slotType = await fetchJson<AdminPromptSlotTypeDto>('/api/admin/prompts/slot-types', {
        method: 'POST',
        body: payload,
      })
      syncSlotType(slotType)
      return slotType
    } catch (err) {
      throw toSlotTypeLoadOrSaveError(err)
    }
  }

  async function updateSlotType(
    id: number,
    payload: UpdateAdminPromptSlotTypeRequest,
  ): Promise<AdminPromptSlotTypeDto> {
    try {
      const slotType = await fetchJson<AdminPromptSlotTypeDto>(
        `/api/admin/prompts/slot-types/${id}`,
        {
          method: 'PUT',
          body: payload,
        },
      )
      syncSlotType(slotType)
      return slotType
    } catch (err) {
      throw toSlotTypeLoadOrSaveError(err)
    }
  }

  async function deleteSlotType(id: number): Promise<void> {
    try {
      await fetchJson<void>(`/api/admin/prompts/slot-types/${id}`, {
        method: 'DELETE',
        responseType: 'void',
      })
      removeSlotType(id)
    } catch (err) {
      throw toSlotTypeDeleteError(err)
    }
  }

  async function fetchSlotVariants() {
    if (isLoadingSlotVariants.value) {
      return
    }

    isLoadingSlotVariants.value = true
    error.value = null

    try {
      const data = await fetchJson<{ items: AdminPromptSlotVariantDto[] }>(
        '/api/admin/prompts/slot-variants',
      )
      slotVariants.value = sortSlotVariants(data.items)
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      isLoadingSlotVariants.value = false
    }
  }

  async function fetchSlotVariant(id: number): Promise<AdminPromptSlotVariantDetailDto> {
    try {
      const slotVariant = await fetchJson<AdminPromptSlotVariantDetailDto>(
        `/api/admin/prompts/slot-variants/${id}`,
      )
      syncSlotVariant(slotVariant)
      return slotVariant
    } catch (err) {
      throw toSlotVariantLoadError(err)
    }
  }

  async function createSlotVariant(
    payload: CreateAdminPromptSlotVariantRequest,
  ): Promise<AdminPromptSlotVariantDetailDto> {
    try {
      const slotVariant = await fetchJson<AdminPromptSlotVariantDetailDto>(
        '/api/admin/prompts/slot-variants',
        {
          method: 'POST',
          body: payload,
        },
      )
      syncSlotVariant(slotVariant)
      return slotVariant
    } catch (err) {
      throw toSlotVariantSaveError(err)
    }
  }

  async function updateSlotVariant(
    id: number,
    payload: UpdateAdminPromptSlotVariantRequest,
  ): Promise<AdminPromptSlotVariantDetailDto> {
    try {
      const slotVariant = await fetchJson<AdminPromptSlotVariantDetailDto>(
        `/api/admin/prompts/slot-variants/${id}`,
        {
          method: 'PUT',
          body: payload,
        },
      )
      syncSlotVariant(slotVariant)
      return slotVariant
    } catch (err) {
      throw toSlotVariantSaveError(err)
    }
  }

  async function deleteSlotVariant(id: number): Promise<void> {
    try {
      await fetchJson<void>(`/api/admin/prompts/slot-variants/${id}`, {
        method: 'DELETE',
        responseType: 'void',
      })
      removeSlotVariant(id)
    } catch (err) {
      throw toSlotVariantDeleteError(err)
    }
  }

  return {
    slotTypes,
    slotVariants,
    isLoadingSlotTypes,
    isLoadingSlotVariants,
    isLoading,
    error,
    variantsBySlotTypeId,
    fetchSlotTypes,
    fetchSlotType,
    createSlotType,
    updateSlotType,
    deleteSlotType,
    fetchSlotVariants,
    fetchSlotVariant,
    createSlotVariant,
    updateSlotVariant,
    deleteSlotVariant,
  }
})
