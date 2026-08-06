export interface ApiErrorDetails {
  detail?: string
  message?: string
  title?: string
  code?: string
  [key: string]: unknown
}

export class ApiError extends Error {
  readonly status: number
  readonly details: ApiErrorDetails | null
  readonly rawBody?: string

  constructor(message: string, status: number, details: ApiErrorDetails | null, rawBody?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.details = details
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
}

export const ANTIFORGERY_ERROR_CODE = 'antiforgery_token_invalid'

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
      const error = await parseApiError(response)
      throw new ApiError(error.message, response.status, error.details, error.rawBody)
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
  if (
    managesAntiforgeryToken &&
    response.status === 400 &&
    (await isAntiforgeryRejection(response))
  ) {
    requestHeaders['X-XSRF-TOKEN'] = await fetchRequestToken({ forceRefresh: true })
    response = await sendRequest()
  }

  if (!response.ok) {
    const error = await parseApiError(response)
    throw new ApiError(error.message, response.status, error.details, error.rawBody)
  }

  return readResponse<T>(response, responseType ?? 'json')
}

async function isAntiforgeryRejection(response: Response): Promise<boolean> {
  const rawBody = await response
    .clone()
    .text()
    .catch(() => '')
  return parseErrorDetails(rawBody)?.code === ANTIFORGERY_ERROR_CODE
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

async function parseApiError(response: Response): Promise<ParsedApiError> {
  const rawBody = await response.text().catch(() => '')
  const details = parseErrorDetails(rawBody)
  const message =
    details?.detail ?? details?.message ?? details?.title ?? `HTTP error ${response.status}`

  return {
    message,
    details,
    rawBody: rawBody || undefined,
  }
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
    detail: optionalString(record.detail),
    message: optionalString(record.message),
    title: optionalString(record.title),
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
