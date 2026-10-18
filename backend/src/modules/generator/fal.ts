import { z } from 'zod'
import type { Fetch } from '@/platform/fetch'
import { errorClass, type Logger } from '@/platform/logging'
import { ALLOWED_IMAGE_CONTENT_TYPES, type ImageGenerator, type RawImage } from './image-generator'
import { MAX_IMAGE_BYTES } from './upload'

/** The one fal.ai endpoint this shop calls. Not configurable, so no test can reach the real one. */
export const FAL_EDIT_URL = 'https://fal.run/fal-ai/nano-banana-2/edit'

/** Generating an image takes far longer than an ordinary API call. */
const REQUEST_TIMEOUT_MILLIS = 120_000
const DEFAULT_RESULT_CONTENT_TYPE = 'image/jpeg'

/**
 * What fal.ai answers. Unknown fields are ignored, so a provider that adds one breaks nothing, and
 * an answer without images is an empty list rather than an error.
 */
const falAnswer = z.object({
  images: z
    .array(z.object({ url: z.string().default(''), content_type: z.string().nullish() }))
    .default([]),
})

/**
 * The one place that knows fal.ai: the upload goes out as a `data:` URI, the generated image comes
 * back from the URL the answer names. Every way this can fail ends in `null`, with the reason in the
 * log. The paid call is never retried and never follows a redirect; the download follows redirects
 * (the result usually sits behind a CDN), carries no credential, and must start from an `https` URL.
 * Both answers are read up to the 10 MiB a visitor may upload. Nothing the provider wrote is logged:
 * a refusal is logged by its status, an unreadable answer and a transport failure by the error's
 * name, because a parse error's message quotes the input it failed on.
 */
export function createFalImageGenerator({
  apiKey,
  fetch,
  logger,
}: {
  apiKey: string
  fetch: Fetch
  logger: Logger
}): ImageGenerator {
  const failed = (what: string, error: unknown): null => {
    logger.error(`${what} (${errorClass(error)})`)
    return null
  }

  const requestGeneration = async (image: RawImage, prompt: string, aspectRatio: string) => {
    let payload: Uint8Array | null
    try {
      const response = await fetch(FAL_EDIT_URL, {
        method: 'POST',
        headers: { Authorization: `Key ${apiKey}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          image_urls: [dataUri(image)],
          prompt,
          num_images: 1,
          aspect_ratio: aspectRatio,
        }),
        redirect: 'manual',
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MILLIS),
      })
      if (!response.ok) {
        await response.body?.cancel()
        logger.error(`fal.ai refused a generation with status ${String(response.status)}`)
        return null
      }
      payload = await readCapped(response)
    } catch (error) {
      return failed('The fal.ai generation call failed: the provider could not be reached', error)
    }
    if (payload === null) {
      logger.error(`The fal.ai generation answer is larger than ${String(MAX_IMAGE_BYTES)} bytes`)
      return null
    }
    try {
      const generated = falAnswer.parse(JSON.parse(new TextDecoder().decode(payload))).images[0]
      if (generated === undefined) logger.error('fal.ai answered without a generated image')
      return generated ?? null
    } catch (error) {
      return failed(
        'The fal.ai generation call failed: the provider answer could not be read',
        error,
      )
    }
  }

  const download = async (url: string, reportedType: string | null | undefined) => {
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
        logger.error(`Downloading a generated image answered status ${String(response.status)}`)
        return null
      }
      const bytes = await readCapped(response)
      if (bytes === null) {
        logger.error(`The generated image is larger than ${String(MAX_IMAGE_BYTES)} bytes`)
        return null
      }
      return { bytes, contentType: resultContentType(reportedType) }
    } catch (error) {
      return failed('The generated image could not be downloaded', error)
    }
  }

  return async (image, prompt, aspectRatio) => {
    const generated = await requestGeneration(image, prompt, aspectRatio)
    return generated === null ? null : download(generated.url, generated.content_type)
  }
}

/** How fal.ai wants the input image: inline in the request, not as a URL. */
const dataUri = (image: RawImage) =>
  `data:${image.contentType};base64,${Buffer.from(image.bytes).toString('base64')}`

/** The reported type when this shop serves it, otherwise `image/jpeg`. */
function resultContentType(reported: string | null | undefined): string {
  const normalized = (reported ?? '').trim().toLowerCase()
  return ALLOWED_IMAGE_CONTENT_TYPES.has(normalized) ? normalized : DEFAULT_RESULT_CONTENT_TYPE
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
