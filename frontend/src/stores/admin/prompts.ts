import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchForm, fetchJson, type ApiFieldErrors } from '@/lib/api'
import type { AdminPriceDto, AdminPriceInputDto } from '@/stores/admin/prices'
import type { ReorderRequest } from '@/stores/admin/reorder'

/**
 * What a list row shows of a prompt's price: the sales total split into its three amounts, plus the
 * whole-number VAT percentage they were calculated with. The detail carries the full calculated
 * price instead, because the editor recalculates from its inputs.
 */
export interface AdminPromptPriceDto {
  salesTotalNet: number
  salesTotalGross: number
  salesTotalTax: number
  salesVatRatePercent: number
}

/**
 * One row of the admin prompt list.
 *
 * The row is flat: it names the category and the subcategory by id *and* by display name, because
 * the overview table shows the names and the backend reads them together with the rows. There are
 * no nested `category`/`subcategory` objects any more — only the storefront keeps those.
 *
 * `position` is response-only; ordering is changed exclusively through the reorder route.
 */
export interface AdminPromptListItemDto {
  id: number
  position: number
  title: string
  categoryId: number
  categoryName: string
  subcategoryId: number | null
  subcategoryName: string | null
  exampleImageFilename: string | null
  llm: string | null
  active: boolean
  archived: boolean
  price: AdminPromptPriceDto | null
}

/**
 * The admin prompt detail: what get, create, and update answer with.
 *
 * It is flat in the same way as a list row, but it carries the ids only — the editor loads both
 * category lists itself — and adds what an editor needs and a table does not: `promptText`, the
 * selected `slotVariantIds`, and the full calculated `price`.
 *
 * `price` is nullable although every write requires one: a prompt whose price row was never linked
 * reads back without one, and the next valid update repairs it. There is no `priceId` anywhere in
 * this contract; the calculated price carries the only price id there is.
 */
export interface AdminPromptDetailDto {
  id: number
  position: number
  title: string
  promptText: string
  categoryId: number
  subcategoryId: number | null
  slotVariantIds: number[]
  exampleImageFilename: string | null
  llm: string | null
  active: boolean
  archived: boolean
  price: AdminPriceDto | null
}

/**
 * The shared create/update body of a prompt. Both operations replace every stored value, including
 * the whole set of slot variants.
 *
 * Neither `position` nor `priceId` exists: the module decides the position, and a price row belongs
 * to exactly one prompt because its id is only minted while the prompt is written. `price` is
 * required on create *and* on update.
 *
 * `title` and `llm` come back trimmed, `promptText` keeps its whitespace verbatim, and
 * `slotVariantIds` may be empty and comes back deduplicated rather than rejected.
 */
export interface SaveAdminPromptRequest {
  title: string
  promptText: string
  categoryId: number
  subcategoryId: number | null
  slotVariantIds: number[]
  exampleImageFilename: string | null
  llm: string | null
  active: boolean
  archived: boolean
  price: AdminPriceInputDto
}

/** The reorder lost a race for the position. Nothing was written, so the move may be retried. */
export class PromptOrderConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptOrderConflictError'
  }
}

export class PromptNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PromptNotFoundError'
  }
}

/** The editor tab that owns the reported problem. */
export type PromptSaveErrorSection = 'prompt' | 'price'

/**
 * A refused prompt write.
 *
 * No prompt write answers `409` — the reorder is the only route that does — so every reference a
 * client can get wrong arrives as a `400 Validation failed` with the messages keyed by the JSON
 * path of the offending field: `categoryId`, `subcategoryId`, `slotVariantIds`,
 * `exampleImageFilename`, `price.salesVatId`. {@link section} folds those paths onto the editor tab
 * that has to be opened to show them.
 */
export class PromptSaveError extends Error {
  readonly section: PromptSaveErrorSection
  readonly fieldErrors: ApiFieldErrors

  constructor(message: string, section: PromptSaveErrorSection, fieldErrors: ApiFieldErrors = {}) {
    super(message)
    this.name = 'PromptSaveError'
    this.section = section
    this.fieldErrors = fieldErrors
  }

  /** The first message the backend reported for `field`, or `null` when it reported none. */
  fieldError(field: string): string | null {
    return this.fieldErrors[field]?.[0] ?? null
  }
}

/**
 * Whether the rejection blames the embedded price.
 *
 * This is the whole check. The legacy backend marked a refused price with the machine-readable code
 * `invalid_price_request`; the Kotlin error body has no `code` at all, and a rejected price is a
 * plain validation failure whose fields sit under `price`.
 */
function hasPriceValidationErrors(fieldErrors: ApiFieldErrors) {
  return Object.keys(fieldErrors).some((key) => key === 'price' || key.startsWith('price.'))
}

function toPromptSaveError(error: unknown) {
  const message = error instanceof Error ? error.message : 'Unknown error'
  if (!(error instanceof ApiError)) {
    return new PromptSaveError(message, 'prompt')
  }

  const section = hasPriceValidationErrors(error.fieldErrors) ? 'price' : 'prompt'
  return new PromptSaveError(message, section, error.fieldErrors)
}

/**
 * Every pre-upload rejection — no `file` part, a body above 10 MiB, an unsupported or broken image
 * — is a `400` whose message sits on the `file` field, so that message is what a user is shown.
 */
function toUploadError(error: unknown) {
  if (!(error instanceof ApiError)) {
    return error instanceof Error ? error : new Error('Unknown error')
  }

  return new Error(error.fieldErrors.file?.[0] ?? error.message)
}

/**
 * The prompts a shop sells, and the admin operations on them.
 *
 * There is deliberately no delete: a prompt is retired by setting `archived`, because carts,
 * orders, and generated images keep referring to it.
 */
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

  function fetchPrompts(): Promise<void> {
    if (pendingPromptListRequest !== null) {
      return pendingPromptListRequest
    }

    pendingPromptListRequest = (async () => {
      isLoading.value = true
      error.value = null

      try {
        syncPromptList(await fetchJson<AdminPromptListItemDto[]>('/api/admin/prompts'))
      } catch (err) {
        error.value = err instanceof Error ? err.message : 'Unknown error'
      } finally {
        isLoading.value = false
        pendingPromptListRequest = null
      }
    })()

    return pendingPromptListRequest
  }

  /**
   * Loads the list again even when a request is already in flight.
   *
   * A write invalidates the cached rows, and the detail a write answers with cannot patch a row on
   * its own: it carries the category *ids*, while a row shows the display names.
   */
  async function refreshPrompts(): Promise<void> {
    if (pendingPromptListRequest !== null) {
      await pendingPromptListRequest
    }

    await fetchPrompts()
  }

  async function fetchPrompt(id: number): Promise<AdminPromptDetailDto> {
    try {
      return await fetchJson<AdminPromptDetailDto>(`/api/admin/prompts/${id}`)
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) {
        throw new PromptNotFoundError(err.message)
      }
      throw err
    }
  }

  async function createPrompt(payload: SaveAdminPromptRequest): Promise<AdminPromptDetailDto> {
    try {
      return await fetchJson<AdminPromptDetailDto>('/api/admin/prompts', {
        method: 'POST',
        body: payload,
      })
    } catch (err) {
      throw toPromptSaveError(err)
    }
  }

  async function updatePrompt(
    id: number,
    payload: SaveAdminPromptRequest,
  ): Promise<AdminPromptDetailDto> {
    try {
      return await fetchJson<AdminPromptDetailDto>(`/api/admin/prompts/${id}`, {
        method: 'PUT',
        body: payload,
      })
    } catch (err) {
      throw toPromptSaveError(err)
    }
  }

  /**
   * Stores an example image before the prompt that refers to it is written, and answers the file
   * name to put into `exampleImageFilename`. The stored name is always a UUID with dashes plus
   * `.webp`, whatever was uploaded; sending `null` on a write removes the image.
   */
  async function uploadExampleImage(file: File): Promise<string> {
    const formData = new FormData()
    formData.append('file', file)

    try {
      const uploaded = await fetchForm<{ filename: string }>(
        '/api/admin/prompts/example-images',
        formData,
      )
      return uploaded.filename
    } catch (err) {
      throw toUploadError(err)
    }
  }

  /**
   * Moves `sourceId` to the place currently held by `targetId`. The answer is the complete new
   * order as dense list rows, so the client never reconstructs the sequence from the one move it
   * asked for. An unknown id is `404`, and a lost race is the retryable `409`.
   */
  async function reorderPrompts(
    sourceId: number,
    targetId: number,
  ): Promise<AdminPromptListItemDto[]> {
    const payload: ReorderRequest = { sourceId, targetId }

    isReordering.value = true
    try {
      const items = await fetchJson<AdminPromptListItemDto[]>('/api/admin/prompts/order', {
        method: 'PUT',
        body: payload,
      })
      syncPromptList(items)
      return prompts.value
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) {
        throw new PromptNotFoundError(err.message)
      }
      if (err instanceof ApiError && err.status === 409) {
        throw new PromptOrderConflictError(err.message)
      }
      throw err
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
