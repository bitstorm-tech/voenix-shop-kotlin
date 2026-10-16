import { ALLOWED_IMAGE_CONTENT_TYPES, type RawImage } from './image-generator'

/**
 * The part names, and therefore also the field names every rejection concerning them is reported
 * under, so an error can never name a field the reader does not look for.
 */
export const IMAGE_PART_NAME = 'image'
export const PROMPT_ID_PART_NAME = 'promptId'
export const ARTICLE_ID_PART_NAME = 'articleId'

/** How large one picture may be: the upload, the provider's answer, and the generated image. */
export const MAX_IMAGE_BYTES = 10 * 1024 * 1024

/**
 * How many bytes of file parts one request may carry. Two images' worth is deliberate slack: one
 * legitimate upload plus whatever a browser puts around it fits, a body repeating parts does not.
 */
export const MAX_FILE_PART_BYTES = 2 * MAX_IMAGE_BYTES

/** What a generation request carried, or the one field the client has to fix. */
export type GenerationUpload =
  | { kind: 'received'; image: RawImage; promptId: number; articleId: number }
  | { kind: 'rejected'; field: string; message: string }

/**
 * Reads the multipart body of a generation request (`await c.req.formData()`), in whatever order
 * the parts came. The last `image` file part, the last `promptId`, and the last `articleId` text
 * part win, the way every form parser resolves a repeated field. Every file part, whatever its
 * name, counts against `MAX_FILE_PART_BYTES`; an `image` part past `MAX_IMAGE_BYTES` is refused
 * even when a later one would fit. A body with several problems is refused on the first one the
 * client has to fix: the size, the image, the prompt id, the article id, the image's type.
 */
export async function readGenerationUpload(form: FormData): Promise<GenerationUpload> {
  let image: File | undefined
  let fileBytes = 0
  let oversized = false
  // Bun's typings claim `entries()` yields strings only, so the parts are walked name by name.
  for (const name of new Set(form.keys())) {
    for (const value of form.getAll(name)) {
      if (typeof value === 'string') continue
      fileBytes += value.size
      if (name === IMAGE_PART_NAME) {
        oversized ||= value.size > MAX_IMAGE_BYTES
        image = value
      }
    }
  }
  const promptId = numericId(form.getAll(PROMPT_ID_PART_NAME))
  const articleId = numericId(form.getAll(ARTICLE_ID_PART_NAME))
  if (oversized || fileBytes > MAX_FILE_PART_BYTES) {
    return rejected(
      IMAGE_PART_NAME,
      'Image files may carry at most 10 MiB each and 20 MiB per request',
    )
  }
  if (image === undefined || image.size === 0) {
    return rejected(IMAGE_PART_NAME, 'An image file is required')
  }
  if (promptId === null) return rejected(PROMPT_ID_PART_NAME, 'A numeric prompt id is required')
  if (articleId === null) return rejected(ARTICLE_ID_PART_NAME, 'A numeric article id is required')
  if (!ALLOWED_IMAGE_CONTENT_TYPES.has(image.type.trim().toLowerCase())) {
    return rejected(IMAGE_PART_NAME, 'Image must be a JPEG, PNG, or WebP file')
  }
  return {
    kind: 'received',
    image: { bytes: new Uint8Array(await image.arrayBuffer()), contentType: image.type },
    promptId,
    articleId,
  }
}

const rejected = (field: string, message: string): GenerationUpload => ({
  kind: 'rejected',
  field,
  message,
})

/**
 * The last text part's value as a whole number, signed or not; no text part, anything else, or a
 * number past JavaScript's safe range is none.
 */
function numericId(values: Bun.FormDataEntryValue[]): number | null {
  const last = values.filter((value) => typeof value === 'string').at(-1)
  const trimmed = last?.trim() ?? ''
  const id = Number(trimmed)
  return /^[+-]?\d+$/.test(trimmed) && Number.isSafeInteger(id) ? id : null
}
