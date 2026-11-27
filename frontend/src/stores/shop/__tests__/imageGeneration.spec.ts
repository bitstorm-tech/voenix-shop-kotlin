import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import { INSUFFICIENT_MAGIC_COINS_CODE } from '@/lib/magicCoins'
import { useImageGenerationStore } from '@/stores/shop/imageGeneration'

function jsonResponse(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('imageGeneration store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
    Object.defineProperty(URL, 'createObjectURL', {
      value: vi.fn(() => 'blob:generated'),
      configurable: true,
    })
    Object.defineProperty(URL, 'revokeObjectURL', {
      value: vi.fn(),
      configurable: true,
    })
    vi.stubGlobal('crypto', { randomUUID: () => 'generated-image-id' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('generates an image directly without calling any balance endpoint', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/antiforgery/token') {
        return Promise.resolve(jsonResponse({ requestToken: 'csrf-token' }))
      }
      if (url === '/api/generator/generate') {
        return Promise.resolve(
          new Response(new Blob(['generated'], { type: 'image/png' }), {
            status: 200,
            headers: { 'Content-Type': 'image/png' },
          }),
        )
      }
      return Promise.reject(new Error(`Unexpected URL: ${url}`))
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useImageGenerationStore()

    await store.generateImage(new Blob(['image'], { type: 'image/png' }), 12, 7)

    expect(store.generatedImages).toHaveLength(1)
    expect(store.generatedImages[0]!.blob).toBeInstanceOf(Blob)
    expect(store.generatedImages[0]!.blob.size).toBeGreaterThan(0)
    expect(store.generatedImages[0]!.blob.type).toBe('image/png')
    expect(store.generatedImages[0]!.url).toBe('blob:generated')
    expect(store.selectedImageId).toBe('generated-image-id')
    expect(fetchMock.mock.calls.map(([input]) => String(input))).not.toContain(
      '/api/magic-coins/balance',
    )
  })

  // The generator generates *for* an article: the route reads the type behind `articleId` and picks
  // the format that type prints in, and refuses a request without it (issue #205).
  it('sends the image, the prompt, and the article as the multipart parts of the request', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/antiforgery/token') {
        return Promise.resolve(jsonResponse({ requestToken: 'csrf-token' }))
      }
      if (url === '/api/generator/generate') {
        return Promise.resolve(
          new Response(new Blob(['generated'], { type: 'image/png' }), {
            status: 200,
            headers: { 'Content-Type': 'image/png' },
          }),
        )
      }
      return Promise.reject(new Error(`Unexpected URL: ${url}`))
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useImageGenerationStore()

    await store.generateImage(new Blob(['image'], { type: 'image/png' }), 12, 7)

    const generateCall = (
      fetchMock.mock.calls as unknown as [RequestInfo | URL, RequestInit][]
    ).find(([input]) => String(input) === '/api/generator/generate')
    const body = generateCall?.[1]?.body
    expect(body).toBeInstanceOf(FormData)
    const formData = body as FormData
    expect(formData.get('promptId')).toBe('12')
    expect(formData.get('articleId')).toBe('7')
    expect(formData.get('image')).toBeInstanceOf(Blob)
  })

  it('records the rate-limit status and the Retry-After wait for a 429 refusal', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input)
        if (url === '/api/antiforgery/token') {
          return Promise.resolve(jsonResponse({ requestToken: 'csrf-token' }))
        }
        if (url === '/api/generator/generate') {
          return Promise.resolve(
            new Response(JSON.stringify({ message: 'Too many requests', errors: {} }), {
              status: 429,
              headers: { 'Content-Type': 'application/json', 'Retry-After': '3150' },
            }),
          )
        }
        return Promise.reject(new Error(`Unexpected URL: ${url}`))
      }),
    )
    const store = useImageGenerationStore()

    await store.generateImage(new Blob(['image'], { type: 'image/png' }), 12, 7)

    expect(store.errorStatus).toBe(429)
    expect(store.errorRetryAfterSeconds).toBe(3150)
    expect(store.errorCode).toBeNull()
    expect(store.error).toBe('Too many requests')
    expect(store.generatedImages).toHaveLength(0)
  })

  it('records the too-large status for a 413 refusal', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input)
        if (url === '/api/antiforgery/token') {
          return Promise.resolve(jsonResponse({ requestToken: 'csrf-token' }))
        }
        if (url === '/api/generator/generate') {
          return Promise.resolve(
            new Response(JSON.stringify({ message: 'Request body too large', errors: {} }), {
              status: 413,
              headers: { 'Content-Type': 'application/json' },
            }),
          )
        }
        return Promise.reject(new Error(`Unexpected URL: ${url}`))
      }),
    )
    const store = useImageGenerationStore()

    await store.generateImage(new Blob(['image'], { type: 'image/png' }), 12, 7)

    expect(store.errorStatus).toBe(413)
    expect(store.errorRetryAfterSeconds).toBeNull()
    expect(store.errorCode).toBeNull()
    expect(store.error).toBe('Request body too large')
    expect(store.generatedImages).toHaveLength(0)
  })

  // The generator refuses its own 10 MiB / type bound long before the application-wide `413`, as a
  // `400 Validation failed` on the `image` part. The store has to keep those field errors, or the
  // message is indistinguishable from an upstream failure.
  it('records the image field errors of a 400 refusal', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input)
        if (url === '/api/antiforgery/token') {
          return Promise.resolve(jsonResponse({ requestToken: 'csrf-token' }))
        }
        if (url === '/api/generator/generate') {
          return Promise.resolve(
            new Response(
              JSON.stringify({
                message: 'Validation failed',
                errors: {
                  image: ['Image files may carry at most 10 MiB each and 20 MiB per request'],
                },
              }),
              { status: 400, headers: { 'Content-Type': 'application/json' } },
            ),
          )
        }
        return Promise.reject(new Error(`Unexpected URL: ${url}`))
      }),
    )
    const store = useImageGenerationStore()

    await store.generateImage(new Blob(['image'], { type: 'image/png' }), 12, 7)

    expect(store.errorStatus).toBe(400)
    expect(store.errorFieldErrors).toEqual({
      image: ['Image files may carry at most 10 MiB each and 20 MiB per request'],
    })
    expect(store.errorCode).toBeNull()
  })

  it('records the insufficient Magic Coins code on 402 without fetching a balance', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/antiforgery/token') {
        return Promise.resolve(jsonResponse({ requestToken: 'csrf-token' }))
      }
      if (url === '/api/generator/generate') {
        return Promise.resolve(
          new Response(
            JSON.stringify({
              message: 'Not enough Magic Coins',
              code: INSUFFICIENT_MAGIC_COINS_CODE,
              errors: {},
            }),
            { status: 402, headers: { 'Content-Type': 'application/json' } },
          ),
        )
      }
      return Promise.reject(new Error(`Unexpected URL: ${url}`))
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useImageGenerationStore()

    await store.generateImage(new Blob(['image'], { type: 'image/png' }), 12, 7)

    expect(store.errorCode).toBe(INSUFFICIENT_MAGIC_COINS_CODE)
    expect(store.errorStatus).toBe(402)
  })

  it('resets only image-generation-owned preview URLs', async () => {
    vi.mocked(URL.createObjectURL)
      .mockReturnValueOnce('blob:image-generation')
      .mockReturnValueOnce('blob:editor-owned')
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input)
        if (url === '/api/antiforgery/token') {
          return Promise.resolve(jsonResponse({ requestToken: 'csrf-token' }))
        }
        if (url === '/api/generator/generate') {
          return Promise.resolve(
            new Response(new Blob(['generated'], { type: 'image/png' }), {
              status: 200,
              headers: { 'Content-Type': 'image/png' },
            }),
          )
        }
        return Promise.reject(new Error(`Unexpected URL: ${url}`))
      }),
    )
    const store = useImageGenerationStore()

    await store.generateImage(new Blob(['image'], { type: 'image/png' }), 12, 7)
    const editorOwnedUrl = URL.createObjectURL(store.generatedImages[0]!.blob)

    store.reset()

    expect(editorOwnedUrl).toBe('blob:editor-owned')
    expect(URL.revokeObjectURL).toHaveBeenCalledTimes(1)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:image-generation')
    expect(URL.revokeObjectURL).not.toHaveBeenCalledWith('blob:editor-owned')
    expect(store.generatedImages).toHaveLength(0)
  })

  it('discards a late generation response when reset is called while in flight', async () => {
    let resolveGenerate!: (response: Response) => void
    const generatePromise = new Promise<Response>((resolve) => {
      resolveGenerate = resolve
    })

    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input)
        if (url === '/api/antiforgery/token') {
          return Promise.resolve(jsonResponse({ requestToken: 'csrf-token' }))
        }
        if (url === '/api/generator/generate') {
          return generatePromise
        }
        return Promise.reject(new Error(`Unexpected URL: ${url}`))
      }),
    )

    const store = useImageGenerationStore()
    const pending = store.generateImage(new Blob(['image'], { type: 'image/png' }), 12, 7)
    store.reset()

    resolveGenerate(
      new Response(new Blob(['stale'], { type: 'image/png' }), {
        status: 200,
        headers: { 'Content-Type': 'image/png' },
      }),
    )
    await pending

    expect(store.generatedImages).toEqual([])
    expect(store.selectedImageId).toBeNull()
    expect(store.isGenerating).toBe(false)
    expect(URL.createObjectURL).not.toHaveBeenCalled()
  })
})
