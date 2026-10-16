import type { Context } from 'hono'
import { respondFailure, type FailureResponses } from '@/platform/http/respond'
import { invalid, type OperationResult } from '@/platform/operation'
import { MAX_IMAGE_BYTES } from './codec'
import type { ExampleImages } from './example-images'

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

/**
 * `POST …/example-images`: stores the `file` part of a multipart body in the owner's folder and
 * answers `201 { filename }`, the name a following create or update submits. A missing part and an
 * oversized one are `400` on the `file` field, like everything the storage rejects. Install it
 * behind `formBody`, which refuses a body that is no multipart form.
 */
export function exampleImageUpload(store: ExampleImages['store'], responses: FailureResponses) {
  return async (c: Context): Promise<Response> => {
    const upload = await readUploadedImage(await c.req.formData())
    const result =
      upload.kind === 'missing'
        ? uploadRejection('An example image file part is required')
        : upload.kind === 'tooLarge'
          ? uploadRejection('Example image must not exceed 10 MiB')
          : await store(upload.upload)
    return result.kind === 'ok' ? c.json(result.value, 201) : respondFailure(c, result, responses)
  }
}
