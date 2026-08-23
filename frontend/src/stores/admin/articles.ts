import { ApiError, fetchForm, type ApiFieldErrors } from '@/lib/api'

/**
 * What every admin article store shares. The backend has one route family **per type** and the
 * admin surface has one store, one list page, and one editor per type as well — this module holds
 * the pieces all of them use: the list row shape, the error classes, the error mapping, and the
 * image pre-upload.
 *
 * The per-type stores are `stores/admin/mugArticles.ts` and `stores/admin/tshirtArticles.ts`. A new
 * article type gets a new store next to them.
 */
export type AdminArticleType = 'MUG' | 'TSHIRT'

/**
 * One row of an admin article overview, exactly as a type route answers it.
 *
 * The list is per type and ordered per type: `position` counts among the articles of one type only.
 *
 * The names next to the ids come from the backend, which resolves them in one batched lookup per
 * level. `supplierName` is `null` both when the article names no supplier and when the supplier
 * module does not answer for the id — the id itself is always reported, because it is what the
 * article stores.
 *
 * `exampleImageFilename` is the picture the table shows: the image of the default variant, or the
 * image of the oldest variant that has one.
 */
export interface AdminArticleListItemDto {
  id: number
  position: number
  name: string
  active: boolean
  categoryId: number | null
  categoryName: string | null
  subcategoryId: number | null
  subcategoryName: string | null
  supplierId: number | null
  supplierName: string | null
  variantCount: number
  exampleImageFilename: string | null
}

/** The answer of every image pre-upload: the name the picture was stored under. */
interface AdminArticleUploadedImageDto {
  filename: string
}

export class ArticleNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ArticleNotFoundError'
  }
}

/**
 * A `400` from an article route, with the messages the backend put on the fields of the request
 * body.
 *
 * Apart from the reorder, an article write has no `409` at all: every reference problem is a field
 * error here. The keys are JSON paths of the submitted body — `categoryId`, `subcategoryId`,
 * `supplierId`, `mugVariants` / `tshirtVariants`, `price`, `printFrame.widthPct`, and
 * `tshirtVariants[0].colorHex` for one variant. A rejected pre-upload sits on `file`.
 */
export class InvalidArticleRequestError extends Error {
  readonly fieldErrors: ApiFieldErrors

  constructor(message: string, fieldErrors: ApiFieldErrors = {}) {
    super(message)
    this.name = 'InvalidArticleRequestError'
    this.fieldErrors = fieldErrors
  }

  /** The first message the backend reported for `field`, or `null` when it reported none. */
  fieldError(field: string): string | null {
    return this.fieldErrors[field]?.[0] ?? null
  }
}

/** The one conflict the article routes have, and it belongs to the reorder alone. Retrying is right. */
export class ArticleOrderConflictError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ArticleOrderConflictError'
  }
}

/**
 * Every article route answers an invalid id with `400 Invalid article id`, an unknown one with
 * `404 Article not found`, and everything a body gets wrong with `400 Validation failed` plus
 * field errors. None of them answers `409`.
 */
export function toArticleError(err: unknown) {
  const message = err instanceof Error ? err.message : 'Unknown error'

  if (!(err instanceof ApiError)) {
    return new Error(message)
  }

  if (err.status === 400) {
    return new InvalidArticleRequestError(message, err.fieldErrors)
  }

  if (err.status === 404) {
    return new ArticleNotFoundError(message)
  }

  return new Error(message)
}

/** The reorder is the one route that adds a `409`: a lost race for a position, so retry it. */
export function toReorderError(err: unknown) {
  if (err instanceof ApiError && err.status === 409) {
    return new ArticleOrderConflictError(err.message)
  }

  return toArticleError(err)
}

/** Positions are per type, so within one type's list the position alone orders the rows. */
export function sortArticleListItems(items: AdminArticleListItemDto[]): AdminArticleListItemDto[] {
  return [...items].sort((a, b) => a.position - b.position || a.id - b.id)
}

/**
 * Stores an image before the article that refers to it is written, and answers the file name to put
 * into the article. The stored name is always a UUID plus `.webp` — the backend converts every
 * upload, so the submitted format does not survive.
 *
 * Each type stores its pictures in its own folder, so the type's store decides the path: a name
 * returned by one is not a name in the other.
 *
 * Every rejection — no `file` part, a body above 10 MiB, a format the storage refuses — is a `400`
 * on the `file` field.
 */
export async function uploadArticleImage(path: string, file: File): Promise<string> {
  const formData = new FormData()
  formData.append('file', file)

  try {
    const uploaded = await fetchForm<AdminArticleUploadedImageDto>(path, formData)
    return uploaded.filename
  } catch (err) {
    throw toArticleError(err)
  }
}
