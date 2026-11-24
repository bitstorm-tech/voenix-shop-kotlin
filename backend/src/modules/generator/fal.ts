import { isIPv4, isIPv6 } from 'node:net'
import { z } from 'zod'
import { inspectImageBytes } from '@/modules/image'
import type { Fetch } from '@/platform/fetch'
import { errorDetails, type Logger } from '@/platform/logging'
import type { BackgroundRemover, ImageGenerator, RawImage } from './image-generator'
import { MAX_IMAGE_BYTES } from './upload'

/** The generation endpoint. Not configurable, so no test can reach the real one. */
export const FAL_EDIT_URL = 'https://fal.run/fal-ai/nano-banana-2/edit'

/** fal.ai's background removal (Bria RMBG 2.0, $0.018 per image). Not configurable either. */
export const FAL_BACKGROUND_REMOVAL_URL = 'https://fal.run/fal-ai/bria/background/remove'

/** Generating an image takes far longer than an ordinary API call. */
const REQUEST_TIMEOUT_MILLIS = 120_000

/** Maximum redirect hops followed when downloading a provider result image. */
const MAX_REDIRECTS = 5
const REDIRECT_STATUSES = new Set([301, 302, 303, 307, 308])

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
  const call = falCall(dependencies, 'generation')
  return (image, prompt, aspectRatio) =>
    call(
      FAL_EDIT_URL,
      { image_urls: [dataUri(image)], prompt, num_images: 1, aspect_ratio: aspectRatio },
      (answer) => generationAnswer.parse(answer).images[0],
    )
}

/** The admin's background removal: the image to Bria RMBG 2.0, the cut-out back. */
export function createFalBackgroundRemover(dependencies: FalDependencies): BackgroundRemover {
  const call = falCall(dependencies, 'background removal')
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
 * redirect; the download follows up to 5 redirects manually, verifying on every hop that the target
 * is an external `https:` URL (never plaintext HTTP, credentials, loopback, or private/link-local
 * addresses). Both answers are read up to the 10 MiB a visitor may upload, and the downloaded image
 * bytes are validated with sharp so its real format (JPEG, PNG, or WebP) determines `contentType`.
 * Nothing the provider wrote is logged: a refusal is logged by its status, an unreadable answer and
 * a transport failure by the error's name, because a parse error's message quotes the input it
 * failed on.
 */
function falCall({ apiKey, fetch, logger }: FalDependencies, what: string) {
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

  const download = async ({ url }: FalImage) => {
    let current = parseAllowedDownloadUrl(url)
    if (current === null) {
      logger.error('fal.ai returned a result URL that is not an allowed HTTPS URL')
      return null
    }
    try {
      const signal = AbortSignal.timeout(REQUEST_TIMEOUT_MILLIS)
      let response: Response | undefined
      for (let hop = 0; hop <= MAX_REDIRECTS; hop += 1) {
        response = await fetch(current.toString(), {
          method: 'GET',
          redirect: 'manual',
          signal,
        })
        if (!REDIRECT_STATUSES.has(response.status)) break
        await response.body?.cancel()
        if (hop === MAX_REDIRECTS) {
          logger.error('Downloading a fal.ai result exceeded the maximum redirect count')
          return null
        }
        const location = response.headers.get('location')
        if (location === null || location.trim() === '') {
          logger.error('Downloading a fal.ai result received a redirect without a Location header')
          return null
        }
        const next = parseAllowedDownloadUrl(location, current)
        if (next === null) {
          logger.error('Downloading a fal.ai result redirected to a disallowed URL')
          return null
        }
        current = next
      }
      if (response === undefined || !response.ok) {
        await response?.body?.cancel()
        logger.error(
          `Downloading a fal.ai result answered status ${String(response?.status ?? 'unknown')}`,
        )
        return null
      }
      const bytes = await readCapped(response)
      if (bytes === null) {
        logger.error(`The fal.ai result is larger than ${String(MAX_IMAGE_BYTES)} bytes`)
        return null
      }
      const inspected = await inspectImageBytes(bytes)
      if (inspected === null) {
        logger.error('The fal.ai result is not a valid JPEG, PNG, or WebP image')
        return null
      }
      return { bytes, contentType: inspected.contentType }
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

/**
 * Accepts only external `https:` URLs without embedded credentials and outside loopback, private,
 * link-local, or local-only hostnames. Evaluated for the initial result URL and every redirect hop.
 */
function parseAllowedDownloadUrl(raw: string, base?: URL): URL | null {
  let url: URL
  try {
    url = base === undefined ? new URL(raw) : new URL(raw, base)
  } catch {
    return null
  }
  if (url.protocol !== 'https:') return null
  if (url.username !== '' || url.password !== '') return null
  const host = url.hostname
    .toLowerCase()
    .replace(/\.+$/, '')
    .replace(/^\[|\]$/g, '')
  if (host === '' || isInternalHost(host)) return null
  return url
}

function isInternalHost(host: string): boolean {
  if (
    host === 'localhost' ||
    host.endsWith('.localhost') ||
    host.endsWith('.local') ||
    host.endsWith('.internal')
  ) {
    return true
  }
  if (isIPv4(host)) return isPrivateIpv4(host)
  if (isIPv6(host)) return isPrivateIpv6(host)
  return false
}

function isPrivateIpv4(address: string): boolean {
  const parts = address.split('.').map(Number)
  const [a = -1, b = -1] = parts
  return (
    a === 0 ||
    a === 10 ||
    a === 127 ||
    (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168) ||
    (a === 100 && b >= 64 && b <= 127) ||
    a >= 224
  )
}

function isPrivateIpv6(address: string): boolean {
  const groups = expandIpv6(address)
  if (groups === null) return true
  const [g0 = 0, g1 = 0, g2 = 0, g3 = 0, g4 = 0, g5 = 0, g6 = 0, g7 = 0] = groups
  // :: (unspecified) and ::1 (loopback)
  if (g0 === 0 && g1 === 0 && g2 === 0 && g3 === 0 && g4 === 0 && g5 === 0 && g6 === 0 && g7 <= 1) {
    return true
  }
  // ::ffff:0:0/96 (IPv4-mapped)
  if (g0 === 0 && g1 === 0 && g2 === 0 && g3 === 0 && g4 === 0 && g5 === 0xffff) {
    return isPrivateIpv4(
      `${String(g6 >> 8)}.${String(g6 & 0xff)}.${String(g7 >> 8)}.${String(g7 & 0xff)}`,
    )
  }
  // fc00::/7 (unique local), fe80::/10 (link-local), ff00::/8 (multicast), 64:ff9b::/96 (NAT64)
  if ((g0 & 0xfe00) === 0xfc00) return true
  if ((g0 & 0xffc0) === 0xfe80) return true
  if ((g0 & 0xff00) === 0xff00) return true
  if (g0 === 0x0064 && g1 === 0xff9b && g2 === 0 && g3 === 0 && g4 === 0 && g5 === 0) return true
  return false
}

function expandIpv6(address: string): number[] | null {
  // Handle optional dotted-quad IPv4 suffix (e.g. ::ffff:127.0.0.1)
  let normalized = address
  const lastColon = normalized.lastIndexOf(':')
  const tail = normalized.slice(lastColon + 1)
  if (tail.includes('.') && isIPv4(tail)) {
    const [a = 0, b = 0, c = 0, d = 0] = tail.split('.').map(Number)
    normalized = `${normalized.slice(0, lastColon + 1)}${((a << 8) | b).toString(16)}:${((c << 8) | d).toString(16)}`
  }
  const halves = normalized.split('::')
  if (halves.length > 2) return null
  const left = halves[0] === '' ? [] : (halves[0]?.split(':') ?? [])
  const right = halves[1] === undefined || halves[1] === '' ? [] : halves[1].split(':')
  const missing = 8 - (left.length + right.length)
  if (halves.length === 1 ? missing !== 0 : missing < 0) return null
  const hexGroups = [...left, ...Array.from({ length: missing }, () => '0'), ...right]
  const parsed = hexGroups.map((group) => Number.parseInt(group, 16))
  return parsed.every((value) => Number.isInteger(value) && value >= 0 && value <= 0xffff)
    ? parsed
    : null
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
