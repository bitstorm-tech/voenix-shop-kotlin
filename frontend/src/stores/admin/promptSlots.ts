import { computed, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchJson, type ApiFieldErrors } from '@/lib/api'

/**
 * The admin representation of a prompt slot.
 *
 * What the legacy backend called a "slot type" is simply a slot now, and the route follows the
 * name: `/api/admin/prompts/slots`. `position` is response-only — a create appends the slot behind
 * the last one and nothing else ever writes a slot position. `variantCount` is what the list needs
 * to warn before a delete that a slot still has variants.
 */
export interface AdminPromptSlotDto {
  id: number
  name: string
  position: number
  variantCount: number
}

/**
 * The admin representation of a prompt slot variant.
 *
 * The slot is flat: `slotId` is what a client writes back, `slotName` is what it displays. There is
 * no nested slot summary any more — the admin client already holds the slot list.
 */
export interface AdminPromptSlotVariantDto {
  id: number
  slotId: number
  slotName: string
  name: string
  prompt: string
  description: string | null
  llm: string | null
  assignedPromptCount: number
}

/** The shared create/update body of a slot: a slot carries nothing but its name. */
export interface SaveAdminPromptSlotRequest {
  name: string
}

/**
 * The create body of a slot variant: the updatable values plus the slot it is created in.
 *
 * A variant belongs to one slot for its whole life, which is why {@link
 * UpdateAdminPromptSlotVariantRequest} has no `slotId` at all — there is no field for a move that
 * the backend does not offer.
 */
export interface CreateAdminPromptSlotVariantRequest {
  slotId: number
  name: string
  prompt: string
  description?: string | null
  llm?: string | null
}

/** The update body of a slot variant. It deliberately carries no `slotId`. */
export interface UpdateAdminPromptSlotVariantRequest {
  name: string
  prompt: string
  description?: string | null
  llm?: string | null
}

export class PromptSlotNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptSlotNotFoundError'
  }
}

export class PromptSlotNameConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptSlotNameConflictError'
  }
}

export class PromptSlotInUseError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptSlotInUseError'
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

/**
 * A `400 Validation failed`, with the messages the backend put on the fields of the request body.
 *
 * The rejection a caller has to react to arrives this way rather than as a conflict: creating a
 * variant in a slot that does not exist is a field error on `slotId`, not a `404`.
 */
export class PromptSlotValidationError extends Error {
  readonly fieldErrors: ApiFieldErrors

  constructor(message: string, fieldErrors: ApiFieldErrors) {
    super(message)
    this.name = 'PromptSlotValidationError'
    this.fieldErrors = fieldErrors
  }

  /** The first message the backend reported for `field`, or `null` when it reported none. */
  fieldError(field: string): string | null {
    return this.fieldErrors[field]?.[0] ?? null
  }
}

export const useAdminPromptSlotsStore = defineStore('admin-prompt-slots', () => {
  const slots = ref<AdminPromptSlotDto[]>([])
  const slotVariants = ref<AdminPromptSlotVariantDto[]>([])
  const isLoadingSlots = shallowRef(false)
  const isLoadingSlotVariants = shallowRef(false)
  const error = shallowRef<string | null>(null)
  /**
   * The request currently in flight, so a second caller waits for the same answer instead of
   * returning immediately with an empty list. The prompt editor loads its reference data with one
   * `Promise.allSettled`, and a loader that resolves before the list exists fills its selects with
   * nothing. Same pattern as `fetchPrompts` in `stores/admin/prompts.ts`.
   */
  let pendingSlotsRequest: Promise<void> | null = null
  let pendingSlotVariantsRequest: Promise<void> | null = null

  const isLoading = computed(() => isLoadingSlots.value || isLoadingSlotVariants.value)
  const variantsBySlotId = computed(() => {
    return slotVariants.value.reduce<Record<number, AdminPromptSlotVariantDto[]>>(
      (groups, variant) => {
        groups[variant.slotId] = [...(groups[variant.slotId] ?? []), variant]
        return groups
      },
      {},
    )
  })

  function sortSlots(items: AdminPromptSlotDto[]) {
    return [...items].sort((a, b) => a.position - b.position || a.id - b.id)
  }

  /**
   * Groups the variants by slot and orders each group by name. Which slot comes first does not
   * matter: every screen groups the variants under the slots of {@link slots}, so that list's
   * position order decides what a user sees.
   */
  function sortSlotVariants(items: AdminPromptSlotVariantDto[]) {
    return [...items].sort((a, b) => {
      return a.slotId - b.slotId || a.name.localeCompare(b.name) || a.id - b.id
    })
  }

  /**
   * Adds or replaces a slot and keeps the `slotName` its variants carry in step, because a renamed
   * slot is the one case in which the flat name a variant holds would otherwise go stale.
   */
  function syncSlot(slot: AdminPromptSlotDto) {
    const index = slots.value.findIndex((item) => item.id === slot.id)
    if (index === -1) {
      slots.value = sortSlots([...slots.value, slot])
    } else {
      const nextSlots = [...slots.value]
      nextSlots[index] = slot
      slots.value = sortSlots(nextSlots)
    }

    slotVariants.value = sortSlotVariants(
      slotVariants.value.map((variant) =>
        variant.slotId === slot.id ? { ...variant, slotName: slot.name } : variant,
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

  function removeSlot(id: number) {
    slots.value = slots.value.filter((slot) => slot.id !== id)
    slotVariants.value = slotVariants.value.filter((variant) => variant.slotId !== id)
  }

  function removeSlotVariant(id: number) {
    slotVariants.value = slotVariants.value.filter((variant) => variant.id !== id)
  }

  function toValidationError(error: ApiError) {
    return new PromptSlotValidationError(error.message, error.fieldErrors)
  }

  /**
   * `GET`, `POST`, and `PUT` on a slot share this mapping: each route has exactly one `409`
   * meaning, and for these three it is the duplicate name.
   */
  function toSlotLoadOrSaveError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 400) {
      return toValidationError(error)
    }

    if (error.status === 404) {
      return new PromptSlotNotFoundError(message)
    }

    if (error.status === 409) {
      return new PromptSlotNameConflictError(message)
    }

    return new Error(message)
  }

  /** `DELETE` is the only slot route whose `409` means "slot variants still use this slot". */
  function toSlotDeleteError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 404) {
      return new PromptSlotNotFoundError(message)
    }

    if (error.status === 409) {
      return new PromptSlotInUseError(message)
    }

    return new Error(message)
  }

  /**
   * A variant write knows one conflict: the name, which is unique across *all* slots. A slot that
   * does not exist is a field error on `slotId` inside a `400`, not a `404`.
   */
  function toSlotVariantLoadOrSaveError(error: unknown) {
    const message = error instanceof Error ? error.message : 'Unknown error'

    if (!(error instanceof ApiError)) {
      return new Error(message)
    }

    if (error.status === 400) {
      return toValidationError(error)
    }

    if (error.status === 404) {
      return new PromptSlotVariantNotFoundError(message)
    }

    if (error.status === 409) {
      return new PromptSlotVariantNameConflictError(message)
    }

    return new Error(message)
  }

  /** `DELETE` is the only variant route whose `409` means "prompts still use this variant". */
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

  function fetchSlots(): Promise<void> {
    if (pendingSlotsRequest !== null) {
      return pendingSlotsRequest
    }

    pendingSlotsRequest = (async () => {
      isLoadingSlots.value = true
      error.value = null

      try {
        const items = await fetchJson<AdminPromptSlotDto[]>('/api/admin/prompts/slots')
        slots.value = sortSlots(items)
      } catch (err) {
        error.value = err instanceof Error ? err.message : 'Unknown error'
      } finally {
        isLoadingSlots.value = false
        pendingSlotsRequest = null
      }
    })()

    return pendingSlotsRequest
  }

  async function fetchSlot(id: number): Promise<AdminPromptSlotDto> {
    try {
      const slot = await fetchJson<AdminPromptSlotDto>(`/api/admin/prompts/slots/${id}`)
      syncSlot(slot)
      return slot
    } catch (err) {
      throw toSlotLoadOrSaveError(err)
    }
  }

  async function createSlot(payload: SaveAdminPromptSlotRequest): Promise<AdminPromptSlotDto> {
    try {
      const slot = await fetchJson<AdminPromptSlotDto>('/api/admin/prompts/slots', {
        method: 'POST',
        body: payload,
      })
      syncSlot(slot)
      return slot
    } catch (err) {
      throw toSlotLoadOrSaveError(err)
    }
  }

  async function updateSlot(
    id: number,
    payload: SaveAdminPromptSlotRequest,
  ): Promise<AdminPromptSlotDto> {
    try {
      const slot = await fetchJson<AdminPromptSlotDto>(`/api/admin/prompts/slots/${id}`, {
        method: 'PUT',
        body: payload,
      })
      syncSlot(slot)
      return slot
    } catch (err) {
      throw toSlotLoadOrSaveError(err)
    }
  }

  async function deleteSlot(id: number): Promise<void> {
    try {
      await fetchJson<void>(`/api/admin/prompts/slots/${id}`, {
        method: 'DELETE',
        responseType: 'void',
      })
      removeSlot(id)
    } catch (err) {
      throw toSlotDeleteError(err)
    }
  }

  function fetchSlotVariants(): Promise<void> {
    if (pendingSlotVariantsRequest !== null) {
      return pendingSlotVariantsRequest
    }

    pendingSlotVariantsRequest = (async () => {
      isLoadingSlotVariants.value = true
      error.value = null

      try {
        const items = await fetchJson<AdminPromptSlotVariantDto[]>(
          '/api/admin/prompts/slot-variants',
        )
        slotVariants.value = sortSlotVariants(items)
      } catch (err) {
        error.value = err instanceof Error ? err.message : 'Unknown error'
      } finally {
        isLoadingSlotVariants.value = false
        pendingSlotVariantsRequest = null
      }
    })()

    return pendingSlotVariantsRequest
  }

  async function fetchSlotVariant(id: number): Promise<AdminPromptSlotVariantDto> {
    try {
      const slotVariant = await fetchJson<AdminPromptSlotVariantDto>(
        `/api/admin/prompts/slot-variants/${id}`,
      )
      syncSlotVariant(slotVariant)
      return slotVariant
    } catch (err) {
      throw toSlotVariantLoadOrSaveError(err)
    }
  }

  async function createSlotVariant(
    payload: CreateAdminPromptSlotVariantRequest,
  ): Promise<AdminPromptSlotVariantDto> {
    try {
      const slotVariant = await fetchJson<AdminPromptSlotVariantDto>(
        '/api/admin/prompts/slot-variants',
        {
          method: 'POST',
          body: payload,
        },
      )
      syncSlotVariant(slotVariant)
      return slotVariant
    } catch (err) {
      throw toSlotVariantLoadOrSaveError(err)
    }
  }

  async function updateSlotVariant(
    id: number,
    payload: UpdateAdminPromptSlotVariantRequest,
  ): Promise<AdminPromptSlotVariantDto> {
    try {
      const slotVariant = await fetchJson<AdminPromptSlotVariantDto>(
        `/api/admin/prompts/slot-variants/${id}`,
        {
          method: 'PUT',
          body: payload,
        },
      )
      syncSlotVariant(slotVariant)
      return slotVariant
    } catch (err) {
      throw toSlotVariantLoadOrSaveError(err)
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
    slots,
    slotVariants,
    isLoadingSlots,
    isLoadingSlotVariants,
    isLoading,
    error,
    variantsBySlotId,
    fetchSlots,
    fetchSlot,
    createSlot,
    updateSlot,
    deleteSlot,
    fetchSlotVariants,
    fetchSlotVariant,
    createSlotVariant,
    updateSlotVariant,
    deleteSlotVariant,
  }
})
