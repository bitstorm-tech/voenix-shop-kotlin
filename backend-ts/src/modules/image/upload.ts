import { invalid, type OperationResult } from '@/platform/operation'
import { MAX_IMAGE_BYTES } from './codec'

/**
 * The name of the multipart part every pre-upload reads, and therefore also the field every
 * rejected upload is reported on: a client is told about the part it sent, in the part's own name.
 */
export const FILE_PART_NAME = 'file'

/** The bytes of an uploaded image and the content type its part declared (`''` for none). */
export interface ImageUpload {
  bytes: Uint8Array
  contentType: string
}

/** What a pre-upload carried: the image of its `file` part, no such part, or more than 10 MiB. */
export type UploadedImage =
  | { kind: 'received'; upload: ImageUpload }
  | { kind: 'missing' }
  | { kind: 'tooLarge' }

/**
 * Reads the first file part named `file` of a multipart body (`await c.req.formData()`); text parts
 * of that name are skipped. Each consumer chooses its own message for a missing or oversized part
 * and answers it with `uploadRejection`.
 */
export async function readUploadedImage(form: FormData): Promise<UploadedImage> {
  const file = form.getAll(FILE_PART_NAME).find((part) => typeof part !== 'string')
  if (file === undefined) return { kind: 'missing' }
  if (file.size > MAX_IMAGE_BYTES) return { kind: 'tooLarge' }
  return {
    kind: 'received',
    upload: { bytes: new Uint8Array(await file.arrayBuffer()), contentType: file.type },
  }
}

/**
 * The one answer to a rejected upload, `400 Validation failed` with `message` on the `file` field,
 * through `respondFailure`. The storage reports its own rejections (format, emptiness, decoding)
 * in the same shape. `413` stays the application-wide body limit's status.
 */
export const uploadRejection = (message: string): OperationResult<never> =>
  invalid({ [FILE_PART_NAME]: [message] })
