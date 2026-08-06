import { beforeEach, describe, expect, it, vi, afterEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { PrintImageGoneError, usePrintImagesStore } from '@/stores/shop/printImages'
import { resetApiClientForTests } from '@/lib/api'

describe('print images store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    resetApiClientForTests()
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('downloads the print image as a blob from the guest route', async () => {
    const fetchMock = vi.fn(
      async () =>
        new Response(new Blob(['bytes']), {
          status: 200,
          headers: { 'Content-Type': 'image/png' },
        }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const blob = await usePrintImagesStore().fetchPrintImageBlob(321, 1600)

    expect(fetchMock).toHaveBeenCalledWith('/api/images/guest/1600/321')
    expect(blob.type).toBe('image/png')
  })

  // The view must never see the `ApiError`: a `404` here is a domain fact, not an HTTP detail.
  it('turns a 404 into a PrintImageGoneError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(JSON.stringify({ message: 'Image not found' }), {
            status: 404,
            headers: { 'Content-Type': 'application/json' },
          }),
      ),
    )

    const store = usePrintImagesStore()

    await expect(() => store.fetchPrintImageBlob(999, 1600)).rejects.toThrow(PrintImageGoneError)
  })

  it('leaves any other refusal as it is', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          new Response(JSON.stringify({ message: 'Internal server error' }), {
            status: 500,
            headers: { 'Content-Type': 'application/json' },
          }),
      ),
    )

    const store = usePrintImagesStore()

    await expect(() => store.fetchPrintImageBlob(321, 1600)).rejects.not.toThrow(
      PrintImageGoneError,
    )
  })
})
