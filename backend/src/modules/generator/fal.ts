import { z } from 'zod'
import type { Fetch } from '@/platform/fetch'
import { errorDetails, type Logger } from '@/platform/logging'
import {
  ALLOWED_IMAGE_CONTENT_TYPES,
  type BackgroundRemover,
  type ImageGenerator,
  type RawImage,
} from './image-generator'
import { MAX_IMAGE_BYTES } from './upload'

/** The generation endpoint. Not configurable, so no test can reach the real one. */
export const FAL_EDIT_URL = 'https://fal.run/fal-ai/nano-banana-2/edit'

/** fal.ai's background removal (Bria RMBG 2.0, $0.018 per image). Not configurable either. */
export const FAL_BACKGROUND_REMOVAL_URL = 'https://fal.run/fal-ai/bria/background/remove'

/** Generating an image takes far longer than an ordinary API call. */
const REQUEST_TIMEOUT_MILLIS = 120_000

/** An image fal.ai names in its answer. */
const falImage = z.object({ url: z.string().default(''), content_type: z.string().nullish() })
type FalImage = z.infer<typeof falImage>

/**
 * What the two endpoints answer. Unknown fields are ignored, so a provider that adds one breaks
 * nothing, and an answer without an image is an absent image rather than an error.
 */
const generationAnswer = z.object({ images: z.array(falImage).default([]) })
const backgroundRemovalAnswer = z.object({ image: falImage.nullish() })

interface FalDependencies {
  apiKey: string
  fetch: Fetch
  logger: Logger
}

/** The generation call of the editor: the upload and the composed prompt to `nano-banana-2/edit`. */
export function createFalImageGenerator(dependencies: FalDependencies): ImageGenerator {
  const call = falCall(dependencies, 'generation', 'image/jpeg')
  return (image, prompt, aspectRatio) =>
    call(
      FAL_EDIT_URL,
      { image_urls: [dataUri(image)], prompt, num_images: 1, aspect_ratio: aspectRatio },
      (answer) => generationAnswer.parse(answer).images[0],
    )
}

/** The admin's background removal: the image to Bria RMBG 2.0, the cut-out back. */
export function createFalBackgroundRemover(dependencies: FalDependencies): BackgroundRemover {
  const call = falCall(dependencies, 'background removal', 'image/png')
  return (image) =>
    call(
      FAL_BACKGROUND_REMOVAL_URL,
      { image_url: dataUri(image) },
      (answer) => backgroundRemovalAnswer.parse(answer).image ?? undefined,
    )
}

/**
 * The one place that knows how fal.ai is called: the upload goes out as a `data:` URI inside
 * `body`, the result image comes back from the URL the answer names. Every way this can fail ends
 * in `null`, with the reason in the log. The paid call is never retried and never follows a
 * redirect; the download follows redirects (the result usually sits behind a CDN), carries no
 * credential, and must start from an `https` URL. Both answers are read up to the 10 MiB a visitor
 * may upload. Nothing the provider wrote is logged: a refusal is logged by its status, an
 * unreadable answer and a transport failure by the error's name, because a parse error's message
 * quotes the input it failed on. A result type this shop does not serve becomes `fallbackType`.
 */
function falCall({ apiKey, fetch, logger }: FalDependencies, what: string, fallbackType: string) {
  const failed = (message: string, error: unknown): null => {
    logger.error({ error: errorDetails(error) }, message)
    return null
  }

  const request = async (
    endpoint: string,
    body: object,
    pick: (answer: unknown) => FalImage | undefined,
  ) => {
    let payload: Uint8Array | null
    try {
      const response = await fetch(endpoint, {
        method: 'POST',
        headers: { Authorization: `Key ${apiKey}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        redirect: 'manual',
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MILLIS),
      })
      if (!response.ok) {
        await response.body?.cancel()
        logger.error(`fal.ai refused a ${what} with status ${String(response.status)}`)
        return null
      }
      payload = await readCapped(response)
    } catch (error) {
      return failed(`The fal.ai ${what} call failed: the provider could not be reached`, error)
    }
    if (payload === null) {
      logger.error(`The fal.ai ${what} answer is larger than ${String(MAX_IMAGE_BYTES)} bytes`)
      return null
    }
    try {
      const result = pick(JSON.parse(new TextDecoder().decode(payload)))
      if (result === undefined) logger.error(`fal.ai answered a ${what} without an image`)
      return result ?? null
    } catch (error) {
      return failed(`The fal.ai ${what} call failed: the provider answer could not be read`, error)
    }
  }

  const download = async ({ url, content_type }: FalImage) => {
    if (!url.toLowerCase().startsWith('https://')) {
      logger.error('fal.ai returned a result URL that is not an HTTPS URL')
      return null
    }
    try {
      const response = await fetch(url, {
        method: 'GET',
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MILLIS),
      })
      if (!response.ok) {
        await response.body?.cancel()
        logger.error(`Downloading a fal.ai result answered status ${String(response.status)}`)
        return null
      }
      const bytes = await readCapped(response)
      if (bytes === null) {
        logger.error(`The fal.ai result is larger than ${String(MAX_IMAGE_BYTES)} bytes`)
        return null
      }
      return { bytes, contentType: resultContentType(content_type, fallbackType) }
    } catch (error) {
      return failed('The fal.ai result could not be downloaded', error)
    }
  }

  return async (
    endpoint: string,
    body: object,
    pick: (answer: unknown) => FalImage | undefined,
  ): Promise<RawImage | null> => {
    const result = await request(endpoint, body, pick)
    return result === null ? null : download(result)
  }
}

/** How fal.ai wants the input image: inline in the request, not as a URL. */
const dataUri = (image: RawImage) =>
  `data:${image.contentType};base64,${Buffer.from(image.bytes).toString('base64')}`

/** The reported type when this shop serves it, otherwise `fallback`. */
function resultContentType(reported: string | null | undefined, fallback: string): string {
  const normalized = (reported ?? '').trim().toLowerCase()
  return ALLOWED_IMAGE_CONTENT_TYPES.has(normalized) ? normalized : fallback
}

/** The body, or `null` as soon as it exceeds the cap, whatever `Content-Length` announced. */
async function readCapped(response: Response): Promise<Uint8Array<ArrayBuffer> | null> {
  if (response.body === null) return new Uint8Array()
  // A fetch body is a byte stream by the standard; the runtime's typings leave its chunks `any`.
  const reader = response.body.getReader() as ReadableStreamDefaultReader<Uint8Array>
  const chunks: Uint8Array[] = []
  let size = 0
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    size += value.length
    if (size > MAX_IMAGE_BYTES) {
      await reader.cancel()
      return null
    }
    chunks.push(value)
  }
  const bytes = new Uint8Array(size)
  let offset = 0
  for (const chunk of chunks) {
    bytes.set(chunk, offset)
    offset += chunk.length
  }
  return bytes
}
