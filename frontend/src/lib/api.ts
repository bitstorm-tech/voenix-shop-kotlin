/**
 * Validation messages of the shared backend error body, keyed by the JSON path of the offending
 * field (`shippingAddress.country`, `price.salesVatId`, `mugVariants[0].exampleImageFilename`).
 */
export type ApiFieldErrors = Record<string, string[]>

/**
 * The parsed shared error body (`ApiError` on the backend): a human-readable `message`, optional
 * validation `errors` keyed by JSON path, and an optional machine-readable `code`. The index
 * signature keeps route-specific extras readable.
 */
export interface ApiErrorDetails {
  message?: string
  code?: string
  errors?: unknown
  [key: string]: unknown
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string | null
  readonly fieldErrors: ApiFieldErrors
  /** Seconds the server asked the client to wait, from the `Retry-After` response header. */
  readonly retryAfterSeconds: number | null
  readonly rawBody?: string

  constructor(
    message: string,
    status: number,
    details: ApiErrorDetails | null,
    rawBody?: string,
    options: { retryAfterSeconds?: number | null } = {},
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = details?.code ?? null
    this.fieldErrors = parseFieldErrors(details?.errors)
    this.retryAfterSeconds = options.retryAfterSeconds ?? null
    this.rawBody = rawBody
  }
}

export interface ApiRequestOptions extends Omit<RequestInit, 'body' | 'headers' | 'method'> {
  method?: string
  headers?: HeadersInit
  body?: unknown
  skipAntiforgery?: boolean
  responseType?: 'json' | 'blob' | 'text' | 'void'
}

interface ParsedApiError {
  message: string
  details: ApiErrorDetails | null
  rawBody?: string
  retryAfterSeconds: number | null
}

/**
 * The backend rejects a missing or stale CSRF token with `400` and this exact message, without a
 * machine-readable code (`AuthModule.requireCsrf`).
 */
export const CSRF_ERROR_MESSAGE = 'Invalid CSRF token'

let cachedRequestToken: string | null = null
let pendingRequestToken: Promise<string> | null = null
let requestTokenCacheGeneration = 0

export async function readApiError(response: Response): Promise<string> {
  const error = await parseApiError(response)
  return error.message
}

export async function fetchJson<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  return fetchApi<T>(path, {
    ...options,
    responseType: options.responseType ?? 'json',
  })
}

export async function fetchForm<T>(
  path: string,
  formData: FormData,
  options: ApiRequestOptions = {},
): Promise<T> {
  return fetchApi<T>(path, {
    ...options,
    method: options.method ?? 'POST',
    body: formData,
    responseType: options.responseType ?? 'json',
  })
}

export async function fetchRequestToken({
  forceRefresh = false,
}: { forceRefresh?: boolean } = {}): Promise<string> {
  if (forceRefresh) {
    clearApiClientCache()
  }

  if (!forceRefresh && cachedRequestToken) {
    return cachedRequestToken
  }

  if (!forceRefresh && pendingRequestToken) {
    return pendingRequestToken
  }

  const cacheGeneration = requestTokenCacheGeneration
  const tokenRequest = (async () => {
    const response = await fetch('/api/antiforgery/token')

    if (!response.ok) {
      throw await toApiError(response)
    }

    const body = (await response.json().catch(() => null)) as { requestToken?: unknown } | null
    if (!body || typeof body.requestToken !== 'string') {
      throw new Error('Invalid antiforgery token response')
    }

    if (cacheGeneration === requestTokenCacheGeneration) {
      cachedRequestToken = body.requestToken
    }

    return body.requestToken
  })()

  pendingRequestToken = tokenRequest

  try {
    return await tokenRequest
  } finally {
    if (pendingRequestToken === tokenRequest) {
      pendingRequestToken = null
    }
  }
}

export function clearApiClientCache(): void {
  cachedRequestToken = null
  pendingRequestToken = null
  requestTokenCacheGeneration += 1
}

export function resetApiClientForTests(): void {
  clearApiClientCache()
}

async function fetchApi<T>(path: string, options: ApiRequestOptions): Promise<T> {
  const { body, headers, method, skipAntiforgery, responseType, ...requestOptions } = options
  const normalizedMethod = (method ?? 'GET').toUpperCase()
  const requestHeaders = toHeaderRecord(headers)
  const requestBody = prepareRequestBody(body, requestHeaders)

  const managesAntiforgeryToken =
    isUnsafeMethod(normalizedMethod) &&
    !skipAntiforgery &&
    !hasHeader(requestHeaders, 'X-XSRF-TOKEN')

  if (managesAntiforgeryToken) {
    requestHeaders['X-XSRF-TOKEN'] = await fetchRequestToken()
  }

  const sendRequest = () => {
    const init = toRequestInit({
      ...requestOptions,
      method: normalizedMethod,
      headers: { ...requestHeaders },
      body: requestBody,
    })

    return init ? fetch(path, init) : fetch(path)
  }

  let response = await sendRequest()

  // A stale token (e.g. after a server key rotation) is recoverable: refresh
  // the cached token once and retry the request.
  if (managesAntiforgeryToken && (await isCsrfRejection(response))) {
    requestHeaders['X-XSRF-TOKEN'] = await fetchRequestToken({ forceRefresh: true })
    response = await sendRequest()
  }

  if (!response.ok) {
    throw await toApiError(response)
  }

  return readResponse<T>(response, responseType ?? 'json')
}

async function isCsrfRejection(response: Response): Promise<boolean> {
  if (response.status !== 400) {
    return false
  }

  const rawBody = await response
    .clone()
    .text()
    .catch(() => '')
  return parseErrorDetails(rawBody)?.message === CSRF_ERROR_MESSAGE
}

function prepareRequestBody(body: unknown, headers: Record<string, string>): BodyInit | undefined {
  if (body === undefined || body === null) {
    return undefined
  }

  if (isBodyInit(body)) {
    return body
  }

  if (!hasHeader(headers, 'Content-Type')) {
    headers['Content-Type'] = 'application/json'
  }

  return JSON.stringify(body)
}

function isBodyInit(body: unknown): body is BodyInit {
  return (
    typeof body === 'string' ||
    body instanceof Blob ||
    body instanceof FormData ||
    body instanceof URLSearchParams ||
    body instanceof ArrayBuffer ||
    ArrayBuffer.isView(body)
  )
}

function toRequestInit(init: RequestInit & { headers: Record<string, string> }) {
  const { headers, method, body, ...rest } = init
  const nextInit: RequestInit = { ...rest }
  const hasHeaders = Object.keys(headers).length > 0
  const hasOptions = Object.keys(rest).some((key) => rest[key as keyof typeof rest] !== undefined)

  if (method && method !== 'GET') {
    nextInit.method = method
  }

  if (hasHeaders) {
    nextInit.headers = headers
  }

  if (body !== undefined) {
    nextInit.body = body
  }

  return Object.keys(nextInit).length > 0 || hasOptions ? nextInit : undefined
}

async function readResponse<T>(
  response: Response,
  responseType: ApiRequestOptions['responseType'],
): Promise<T> {
  if (response.status === 204 || responseType === 'void') {
    return undefined as T
  }

  if (responseType === 'blob') {
    return (await response.blob()) as T
  }

  if (responseType === 'text') {
    return (await response.text()) as T
  }

  const rawBody = await response.text()
  if (!rawBody) {
    return undefined as T
  }

  return JSON.parse(rawBody) as T
}

async function toApiError(response: Response): Promise<ApiError> {
  const error = await parseApiError(response)
  return new ApiError(error.message, response.status, error.details, error.rawBody, {
    retryAfterSeconds: error.retryAfterSeconds,
  })
}

async function parseApiError(response: Response): Promise<ParsedApiError> {
  const rawBody = await response.text().catch(() => '')
  const details = parseErrorDetails(rawBody)

  return {
    message: details?.message ?? `HTTP error ${response.status}`,
    details,
    rawBody: rawBody || undefined,
    retryAfterSeconds: parseRetryAfter(response.headers.get('Retry-After')),
  }
}

/** The backend always sends `Retry-After` as a positive whole number of seconds. */
function parseRetryAfter(headerValue: string | null): number | null {
  if (headerValue === null) {
    return null
  }

  const seconds = Number(headerValue.trim())
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : null
}

function parseFieldErrors(errors: unknown): ApiFieldErrors {
  if (errors === null || typeof errors !== 'object' || Array.isArray(errors)) {
    return {}
  }

  const fieldErrors: ApiFieldErrors = {}
  for (const [field, messages] of Object.entries(errors)) {
    if (Array.isArray(messages)) {
      const texts = messages.filter((message): message is string => typeof message === 'string')
      if (texts.length > 0) {
        fieldErrors[field] = texts
      }
    }
  }

  return fieldErrors
}

function parseErrorDetails(rawBody: string): ApiErrorDetails | null {
  if (!rawBody) {
    return null
  }

  let parsed: unknown
  try {
    parsed = JSON.parse(rawBody)
  } catch {
    return null
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return null
  }

  const record = parsed as Record<string, unknown>
  return {
    ...record,
    message: optionalString(record.message),
    code: optionalString(record.code),
  }
}

function optionalString(value: unknown) {
  return typeof value === 'string' ? value : undefined
}

function toHeaderRecord(headers?: HeadersInit): Record<string, string> {
  if (!headers) {
    return {}
  }

  if (headers instanceof Headers) {
    const record: Record<string, string> = {}
    headers.forEach((value, key) => {
      record[key] = value
    })
    return record
  }

  if (Array.isArray(headers)) {
    return Object.fromEntries(headers)
  }

  return { ...headers }
}

function hasHeader(headers: Record<string, string>, name: string) {
  const normalizedName = name.toLowerCase()
  return Object.keys(headers).some((key) => key.toLowerCase() === normalizedName)
}

function isUnsafeMethod(method: string) {
  return method === 'POST' || method === 'PUT' || method === 'PATCH' || method === 'DELETE'
}
