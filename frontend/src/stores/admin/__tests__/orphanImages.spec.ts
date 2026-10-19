import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetApiClientForTests } from '@/lib/api'
import {
  orphanImageUrl,
  type OrphanImage,
  useAdminOrphanImagesStore,
} from '@/stores/admin/orphanImages'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

function stubFetch(handler: (input: RequestInfo | URL, init?: RequestInit) => unknown) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) =>
    input === '/api/antiforgery/token'
      ? jsonResponse({ requestToken: 'token-1' })
      : handler(input, init),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const mockup: OrphanImage = {
  visibility: 'public',
  path: 'articles/spod/mockups/a.webp',
  size: 2048,
  modifiedAt: '2026-10-01T10:00:00.000Z',
}
const printImage: OrphanImage = {
  visibility: 'private',
  path: 'print-images/b.webp',
  size: 4096,
  modifiedAt: '2026-10-02T10:00:00.000Z',
}

describe('admin orphan images store', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads the orphans', async () => {
    const fetchMock = stubFetch(() => jsonResponse([mockup, printImage]))
    const store = useAdminOrphanImagesStore()

    await store.fetchOrphans()

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/images/orphans')
    expect(store.images).toEqual([mockup, printImage])
    expect(store.error).toBeNull()
  })

  it('keeps the failure of a load', async () => {
    stubFetch(() => jsonResponse({ message: 'Forbidden', errors: {} }, { status: 403 }))
    const store = useAdminOrphanImagesStore()

    await store.fetchOrphans()

    expect(store.images).toEqual([])
    expect(store.error?.message).toBe('Forbidden')
  })

  it('posts the locations and drops only the deleted ones from the list', async () => {
    const fetchMock = stubFetch(() =>
      jsonResponse({
        deleted: [{ visibility: 'public', path: mockup.path }],
        kept: [{ visibility: 'private', path: printImage.path }],
      }),
    )
    const store = useAdminOrphanImagesStore()
    store.images = [mockup, printImage]

    const result = await store.deleteOrphans([mockup, printImage])

    expect(fetchMock.mock.calls[1]![0]).toBe('/api/admin/images/orphans/delete')
    expect(fetchMock.mock.calls[1]![1]).toMatchObject({ method: 'POST' })
    expect(JSON.parse(String(fetchMock.mock.calls[1]![1]?.body))).toEqual({
      images: [
        { visibility: 'public', path: mockup.path },
        { visibility: 'private', path: printImage.path },
      ],
    })
    expect(result.kept).toHaveLength(1)
    expect(store.images).toEqual([printImage])
  })

  it('builds the preview URL from the delivery route of the root', () => {
    expect(orphanImageUrl(mockup, 400)).toBe('/api/images/public/400/articles/spod/mockups/a.webp')
    expect(orphanImageUrl(printImage, 400)).toBe('/api/images/private/400/print-images/b.webp')
  })
})
