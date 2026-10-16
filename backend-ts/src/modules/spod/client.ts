import { z } from 'zod'
import type { Fetch } from '@/platform/fetch'
import { createLogger, errorClass, type Logger } from '@/platform/logging'
import {
  lenientId,
  spodCatalogPage,
  spodSizeChart,
  type SpodBinary,
  type SpodCatalogPage,
  type SpodSizeChart,
} from './catalog'
import {
  spodPreviews,
  spodViews,
  type SpodPreviewRequest,
  type SpodPreviews,
  type SpodViews,
} from './previews'

/**
 * The SPOD installations a destination can talk to. The base URL is a property of the environment
 * and never a column or an admin input (SPOD fulfillment decision 3). The stored value is the name,
 * which is also what `ck_production_destination_spod_environment` allows.
 */
export const SPOD_ENVIRONMENTS = ['PRODUCTION', 'STAGING'] as const
export type SpodEnvironment = (typeof SPOD_ENVIRONMENTS)[number]

const BASE_URLS: Record<SpodEnvironment, string> = {
  PRODUCTION: 'https://rest.spod.com',
  STAGING: 'https://rest.spod-staging.com',
}

/**
 * The stored value read back. The check constraint allows nothing else, so an unknown value is a
 * broken database and throws rather than turning into a missing destination far from the cause.
 */
export function spodEnvironmentOfStoredValue(value: string): SpodEnvironment {
  const environment = SPOD_ENVIRONMENTS.find((candidate) => candidate === value)
  if (environment === undefined) {
    throw new Error(`Unknown SPOD environment ${value} stored on a production destination`)
  }
  return environment
}

/**
 * Where a call goes and how it authenticates: one destination row's installation, token, and
 * timeout. It exists only on the calling path; never log it, never return it from an API.
 * `destinationId` is the only part of it that may be logged.
 */
export interface SpodAccess {
  destinationId: number
  environment: SpodEnvironment
  accessToken: string
  timeoutSeconds: number
}

/** The bounded vocabulary of provider-call failures; the values are the persisted error codes. */
export type SpodError =
  /** The 60-per-minute limit answered `429`; the worker simply tries again later. */
  | 'RATE_LIMITED'
  /** Unreachable, timed out, or answered `5xx`: nothing usable came back. */
  | 'PROVIDER_UNAVAILABLE'
  /** An answer arrived but could not be read as what this adapter expects. */
  | 'PROVIDER_ANSWER_UNREADABLE'
  /** The partner refused the call with a `4xx`. What it said about it stays with it. */
  | 'REFUSED'

/**
 * The typed outcome of one provider call. `failed` carries only a bounded `SpodError` plus
 * `ambiguous`: whether the call may have taken effect without saying so. It is `false` only for a
 * refusal the partner stated; a timeout, a reset, an unreadable answer, and every `5xx` are `true`,
 * because the safe assumption for an order creation is "it may exist".
 */
export type SpodResult<T> =
  | { kind: 'answered'; value: T }
  | { kind: 'failed'; error: SpodError; ambiguous: boolean }

type SpodFailure = Extract<SpodResult<never>, { kind: 'failed' }>

/**
 * The one order shape this shop sends. The client adds `orderItems: []` (required by the partner,
 * always empty: every line is a unique print), `state: 'NEW'` (never `CONFIRMED` at creation, which
 * is what makes an ambiguous creation repeatable), and `shipping.preferredType: 'STANDARD'`.
 */
export interface SpodOrderRequest {
  externalOrderReference: string
  email: string
  phone: string
  shipping: SpodShipping
  oneTimeItems: SpodOneTimeItem[]
}

export interface SpodShipping {
  address: SpodAddress
}

export interface SpodAddress {
  firstName: string
  lastName: string
  street: string
  city: string
  country: string
  zipCode: string
}

/**
 * One printable product of the order. The grouping is the partner's: one entry per product type,
 * design, and placement at one price, with a quantity line per size and colour.
 */
export interface SpodOneTimeItem {
  productTypeId: number
  quantityItems: SpodQuantityItem[]
  configurations: SpodConfiguration[]
  /**
   * What the customer paid for one piece. The partner requires it: without it, `POST /orders`
   * answers a bare `400` before it even validates the order.
   */
  customerPricePerItem: SpodPrice
}

/** An amount in the currency's main unit as a decimal number: `14.99`, not cents. */
export interface SpodPrice {
  amount: number
  currency: 'EUR'
}

export interface SpodQuantityItem {
  quantity: number
  sizeId: number
  appearanceId: number
}

/** Where the design sits: the view of the garment and one of that view's named hotspots. */
export interface SpodConfiguration {
  image: SpodConfigurationImage
  view: string
  hotspot: string
}

export interface SpodConfigurationImage {
  designId: string
}

/** The partner allows 60 requests per minute; the extra 50 ms absorb the scheduling jitter. */
const MIN_REQUEST_INTERVAL_MILLIS = 1050
const ACCESS_TOKEN_HEADER = 'X-SPOD-ACCESS-TOKEN'
/** The `User-Agent` the partner's image CDN lets through; it answers any other client `403`. */
const CDN_USER_AGENT = 'Spreadconnect_API_flow'
/** How much of a catalog image is ever held: the same 10 MiB a visitor may upload. */
const MAX_IMAGE_BYTES = 10 * 1024 * 1024

const designAnswer = z.object({ designId: lenientId })
const createdOrderAnswer = z.object({ id: lenientId })
const orderStateAnswer = z.object({ state: z.string() })
const hotspotsAnswer = z.object({ hotspots: z.array(z.object({ name: z.string() })).default([]) })

/** A download that is no usable image: not https, not an image, or over the cap. */
const UNUSABLE_IMAGE: SpodFailure = {
  kind: 'failed',
  error: 'PROVIDER_ANSWER_UNREADABLE',
  ambiguous: false,
}

/**
 * The one client of the partner's HTTP API; one instance per application, so its pacer holds the
 * account's budget for every module and every supplier at once. See the SPOD package guide for the
 * four rules: nothing secret or foreign is logged, it never retries, it paces itself, and where and
 * how to authenticate travels with the call. `fetch`, `now` (a monotonic clock in milliseconds),
 * `sleep`, and `logger` are seams for the tests.
 */
export function createSpodClient({
  fetch = globalThis.fetch,
  now = () => performance.now(),
  sleep = (millis: number) =>
    new Promise<void>((resolve) => {
      setTimeout(resolve, millis)
    }),
  logger = createLogger('spod'),
}: {
  fetch?: Fetch
  now?: () => number
  sleep?: (millis: number) => Promise<void>
  logger?: Logger
} = {}) {
  let queue = Promise.resolve()
  let earliestNextRequest: number | undefined

  /**
   * Waits until at least the minimum interval has passed since the previous request and books the
   * current one. The waits are chained, so concurrent callers queue up behind each other instead of
   * all sleeping the same interval and then firing together.
   */
  const pace = (): Promise<void> => {
    queue = queue.then(async () => {
      const current = now()
      const wait =
        earliestNextRequest === undefined ? 0 : Math.max(0, earliestNextRequest - current)
      if (wait > 0) await sleep(wait)
      // The clock is read again: a sleep that overshoots must push the next request out with it.
      earliestNextRequest = now() + MIN_REQUEST_INTERVAL_MILLIS
    })
    return queue
  }

  /** A request that never got a status: the outcome is unknown. Only the error's name is logged. */
  const unavailable = (context: string, error: unknown): SpodFailure => {
    const name = errorClass(error)
    const what = name === 'TimeoutError' ? 'did not answer within the configured timeout' : 'failed'
    logger.error(`SPOD ${context} ${what} (${name})`)
    return { kind: 'failed', error: 'PROVIDER_UNAVAILABLE', ambiguous: true }
  }

  /**
   * One paced API call: the URL from the environment, the token and the timeout from the access,
   * no redirect followed. A refusal's body is never read; a success is handed to `read`, and an
   * answer `read` cannot take is unreadable and ambiguous, logged by the error's name only.
   */
  const call = async <T>(
    access: SpodAccess,
    what: string,
    path: string,
    init: { method: 'GET' | 'POST'; body?: string | FormData; contentType?: string },
    read: (body: string) => T,
  ): Promise<SpodResult<T>> => {
    const context = `destination ${String(access.destinationId)}: ${what}`
    await pace()
    const headers: Record<string, string> = { [ACCESS_TOKEN_HEADER]: access.accessToken }
    if (init.contentType !== undefined) headers['Content-Type'] = init.contentType
    let body: string
    try {
      const response = await fetch(BASE_URLS[access.environment] + path, {
        method: init.method,
        headers,
        ...(init.body === undefined ? {} : { body: init.body }),
        redirect: 'manual',
        signal: AbortSignal.timeout(access.timeoutSeconds * 1000),
      })
      if (!response.ok) {
        await discardBody(response)
        logger.warn(`SPOD ${context} was refused with status ${String(response.status)}`)
        return refusalOf(response.status)
      }
      body = await response.text()
    } catch (error) {
      return unavailable(context, error)
    }
    try {
      return { kind: 'answered', value: read(body) }
    } catch (error) {
      logger.error(
        `SPOD ${context} produced an answer that could not be read (${errorClass(error)})`,
      )
      return { kind: 'failed', error: 'PROVIDER_ANSWER_UNREADABLE', ambiguous: true }
    }
  }

  const json =
    <S extends z.ZodType>(schema: S) =>
    (body: string): z.output<S> =>
      schema.parse(JSON.parse(body))

  return {
    /** Uploads one print image as PNG; answers the design id. Safe to repeat. */
    uploadDesign: (access: SpodAccess, fileName: string, png: Uint8Array) => {
      const form = new FormData()
      form.append('file', new Blob([png], { type: 'image/png' }), fileName)
      return call(
        access,
        'uploading a design',
        '/designs/upload',
        { method: 'POST', body: form },
        (body) => json(designAnswer)(body).designId,
      )
    },

    /** Creates the order in state `NEW`; answers the partner's order id. */
    createOrder: (access: SpodAccess, request: SpodOrderRequest) =>
      call(
        access,
        'creating an order',
        '/orders',
        {
          method: 'POST',
          contentType: 'application/json',
          body: JSON.stringify({
            ...request,
            shipping: { ...request.shipping, preferredType: 'STANDARD' },
            orderItems: [],
            state: 'NEW',
          }),
        },
        (body) => json(createdOrderAnswer)(body).id,
      ),

    /** Reads the state of an order: `NEW` while inert, `CONFIRMED` once confirmed. */
    getOrder: (access: SpodAccess, orderId: string) =>
      call(
        access,
        'reading an order',
        `/orders/${orderId}`,
        { method: 'GET' },
        (body) => json(orderStateAnswer)(body).state,
      ),

    /** Turns the inert `NEW` order into a real one. This is the moment production is ordered. */
    confirmOrder: (access: SpodAccess, orderId: string) =>
      call(
        access,
        'confirming an order',
        `/orders/${orderId}/confirm`,
        { method: 'POST' },
        () => undefined,
      ),

    /** The placement names this product type offers for this design. */
    availableHotspots: (access: SpodAccess, productTypeId: number, designId: string) =>
      call(
        access,
        'reading available hotspots',
        `/productTypes/${String(productTypeId)}/hotspots/design/${designId}`,
        { method: 'GET' },
        (body) => json(hotspotsAnswer)(body).hotspots.map((hotspot) => hotspot.name),
      ),

    /** One page of the merchant's backoffice articles; paging is the caller's. */
    articles: (
      access: SpodAccess,
      limit: number,
      offset: number,
    ): Promise<SpodResult<SpodCatalogPage>> =>
      call(
        access,
        'listing articles',
        `/articles?limit=${String(limit)}&offset=${String(offset)}`,
        { method: 'GET' },
        json(spodCatalogPage),
      ),

    /** The size chart of one product type. */
    sizeChart: (access: SpodAccess, productTypeId: number): Promise<SpodResult<SpodSizeChart>> =>
      call(
        access,
        'reading a size chart',
        `/productTypes/${String(productTypeId)}/size-chart`,
        { method: 'GET' },
        json(spodSizeChart),
      ),

    /** The views of one product type: each side's placement names and mockup per colour. */
    views: (access: SpodAccess, productTypeId: number): Promise<SpodResult<SpodViews>> =>
      call(
        access,
        'reading views',
        `/productTypes/${String(productTypeId)}/views`,
        { method: 'GET' },
        json(spodViews),
      ),

    /** Renders the request's designs on one colour of a product type; answers the picture URLs. */
    previews: (
      access: SpodAccess,
      productTypeId: number,
      request: SpodPreviewRequest,
    ): Promise<SpodResult<SpodPreviews>> =>
      call(
        access,
        'rendering previews',
        `/productTypes/${String(productTypeId)}/previews`,
        { method: 'POST', contentType: 'application/json', body: JSON.stringify(request) },
        json(spodPreviews),
      ),

    /**
     * Fetches one image by the URL a previous answer carried: the one call whose URL is not from
     * the environment, so it is bounded instead (SPOD sync decision 5). https only, no token, no
     * redirect, only an `image/*` answer, at most `MAX_IMAGE_BYTES` read, and not paced. Only the
     * host is ever logged.
     */
    download: async (url: string, timeoutSeconds: number): Promise<SpodResult<SpodBinary>> => {
      const host = httpsHostOf(url)
      if (host === null) {
        logger.warn('SPOD image download refused: the answered URL is not an https URL')
        return UNUSABLE_IMAGE
      }
      const context = `image download from ${host}`
      try {
        const response = await fetch(url, {
          method: 'GET',
          headers: { 'User-Agent': CDN_USER_AGENT },
          redirect: 'manual',
          signal: AbortSignal.timeout(timeoutSeconds * 1000),
        })
        const contentType = (response.headers.get('Content-Type') ?? '').split(';', 1)[0]?.trim()
        if (!response.ok || !contentType?.toLowerCase().startsWith('image/')) {
          await discardBody(response)
          if (response.ok) {
            logger.warn(`SPOD ${context} did not answer an image`)
            return UNUSABLE_IMAGE
          }
          logger.warn(`SPOD ${context} was refused with status ${String(response.status)}`)
          return refusalOf(response.status)
        }
        const bytes = await readCapped(response)
        if (bytes === null) {
          logger.warn(`SPOD ${context} is larger than ${String(MAX_IMAGE_BYTES)} bytes`)
          return UNUSABLE_IMAGE
        }
        return { kind: 'answered', value: { bytes, contentType } }
      } catch (error) {
        return unavailable(context, error)
      }
    },
  }
}

export type SpodClient = ReturnType<typeof createSpodClient>

/**
 * The two refusal families, kept apart because the order-creation protocol depends on them: a `4xx`
 * is the partner saying "I did not do this" (known), a `5xx` after the request went out may have
 * created an order (ambiguous). A redirect is not followed and counts as a `4xx`-like refusal.
 */
function refusalOf(status: number): SpodFailure {
  const serverSide = status >= 500
  const error = status === 429 ? 'RATE_LIMITED' : serverSide ? 'PROVIDER_UNAVAILABLE' : 'REFUSED'
  return { kind: 'failed', error, ambiguous: serverSide }
}

/**
 * Drops an answer's unread body. Best effort: a body that already failed rejects the cancel, and the
 * status that was read decides the outcome all the same.
 */
async function discardBody(response: Response): Promise<void> {
  await response.body?.cancel().catch(() => undefined)
}

/** The body, or `null` as soon as it exceeds the cap, whatever `Content-Length` announced. */
async function readCapped(response: Response): Promise<Uint8Array | null> {
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

/**
 * The host name of an `https` URL, or `null` for anything else, including a URL that does not
 * parse. Taken from the parsed URL, the way the fetch itself reads it (a `\` counts as a `/`),
 * and only the host name, because what it answers is logged: a partner-written
 * `https://secret@host/` or `https://host?signature=secret` must not leak.
 */
function httpsHostOf(url: string): string | null {
  let parsed: URL
  try {
    parsed = new URL(url)
  } catch {
    return null
  }
  return parsed.protocol === 'https:' && parsed.hostname !== '' ? parsed.hostname : null
}
