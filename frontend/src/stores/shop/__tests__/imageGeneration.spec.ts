import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import { INSUFFICIENT_MAGIC_COINS_CODE } from '@/lib/magicCoins'
import { useImageGenerationStore } from '@/stores/shop/imageGeneration'
import { useMagicCoinsStore } from '@/stores/shop/magicCoins'

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

  it('refetches Magic Coins after a successful generation', async () => {
    const magicCoinsStore = useMagicCoinsStore()
    magicCoinsStore.balance = 1
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
      if (url === '/api/magic-coins/balance') {
        return Promise.resolve(jsonResponse({ balance: 0 }))
      }
      return Promise.reject(new Error(`Unexpected URL: ${url}`))
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useImageGenerationStore()

    await store.generateImage(new Blob(['image'], { type: 'image/png' }), 12)

    expect(store.generatedImages).toHaveLength(1)
    expect(store.generatedImages[0]!.blob).toBeInstanceOf(Blob)
    expect(store.generatedImages[0]!.blob.size).toBeGreaterThan(0)
    expect(store.generatedImages[0]!.blob.type).toBe('image/png')
    expect(store.generatedImages[0]!.url).toBe('blob:generated')
    expect(store.selectedImageId).toBe('generated-image-id')
    expect(magicCoinsStore.balance).toBe(0)
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/magic-coins/balance',
      expect.objectContaining({ cache: 'no-store', signal: expect.any(AbortSignal) }),
    )
  })

  it('does not send a generation request when balance is zero', async () => {
    const magicCoinsStore = useMagicCoinsStore()
    magicCoinsStore.balance = 0
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const store = useImageGenerationStore()

    await store.generateImage(new Blob(['image'], { type: 'image/png' }), 12)

    expect(fetchMock).not.toHaveBeenCalled()
    expect(store.errorCode).toBe(INSUFFICIENT_MAGIC_COINS_CODE)
    expect(store.generatedImages).toHaveLength(0)
  })

  it('resets only image-generation-owned preview URLs', async () => {
    vi.mocked(URL.createObjectURL)
      .mockReturnValueOnce('blob:image-generation')
      .mockReturnValueOnce('blob:editor-owned')
    const magicCoinsStore = useMagicCoinsStore()
    magicCoinsStore.balance = 1
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
        if (url === '/api/magic-coins/balance') {
          return Promise.resolve(jsonResponse({ balance: 0 }))
        }
        return Promise.reject(new Error(`Unexpected URL: ${url}`))
      }),
    )
    const store = useImageGenerationStore()

    await store.generateImage(new Blob(['image'], { type: 'image/png' }), 12)
    const editorOwnedUrl = URL.createObjectURL(store.generatedImages[0]!.blob)

    store.reset()

    expect(editorOwnedUrl).toBe('blob:editor-owned')
    expect(URL.revokeObjectURL).toHaveBeenCalledTimes(1)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:image-generation')
    expect(URL.revokeObjectURL).not.toHaveBeenCalledWith('blob:editor-owned')
    expect(store.generatedImages).toHaveLength(0)
  })
})
