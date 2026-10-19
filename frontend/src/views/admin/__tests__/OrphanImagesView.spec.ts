import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import type { OrphanImage } from '@/stores/admin/orphanImages'
import OrphanImagesView from '../OrphanImagesView.vue'

const orphansState = reactive({
  images: [] as OrphanImage[],
  isLoading: false,
  error: null as Error | null,
  fetchOrphans: vi.fn(),
  deleteOrphans: vi.fn(),
})

vi.mock('@/stores/admin/orphanImages', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/stores/admin/orphanImages')>()
  return { ...actual, useAdminOrphanImagesStore: () => orphansState }
})

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

async function mountView() {
  const wrapper = mount(OrphanImagesView, { attachTo: document.body })
  await flushPromises()
  return wrapper
}

const confirmButton = () =>
  document.body.querySelector<HTMLButtonElement>('[data-testid="confirm-delete"]')

describe('OrphanImagesView', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    orphansState.images = []
    orphansState.isLoading = false
    orphansState.error = null
    orphansState.fetchOrphans = vi.fn()
    orphansState.deleteOrphans = vi.fn(async () => ({ deleted: [], kept: [] }))
  })

  it('loads the orphans and shows customer images and other images in two tables', async () => {
    orphansState.images = [mockup, printImage]
    const wrapper = await mountView()

    expect(orphansState.fetchOrphans).toHaveBeenCalledOnce()
    const customer = wrapper.get('[data-testid="orphan-section-customer"]')
    const other = wrapper.get('[data-testid="orphan-section-other"]')
    expect(customer.findAll('[data-testid="orphan-image"]')).toHaveLength(1)
    expect(customer.find('img').attributes('src')).toBe(
      '/api/images/private/400/print-images/b.webp',
    )
    expect(customer.get('[data-testid="orphan-summary"]').text()).toContain('1 image(s), 4 KB')
    const rows = other.findAll('[data-testid="orphan-image"]')
    expect(rows).toHaveLength(1)
    expect(rows[0]!.find('img').attributes('src')).toBe(
      '/api/images/public/400/articles/spod/mockups/a.webp',
    )
    expect(rows[0]!.text()).toContain('articles/spod/mockups')
  })

  it('shows an empty table when only one kind of image is orphaned', async () => {
    orphansState.images = [mockup]
    const wrapper = await mountView()

    const customer = wrapper.get('[data-testid="orphan-section-customer"]')
    expect(customer.text()).toContain('There is no orphaned image here.')
    expect(wrapper.get('[data-testid="delete-all-customer"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="delete-all-other"]').attributes('disabled')).toBeUndefined()
  })

  it('says so when there is no orphan', async () => {
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('There is no orphaned image.')
    expect(wrapper.find('[data-testid^="delete-all"]').exists()).toBe(false)
  })

  it('deletes one image after the confirmation', async () => {
    orphansState.images = [mockup, printImage]
    const wrapper = await mountView()

    await wrapper.get(`[data-testid="delete-${mockup.path}"]`).trigger('click')
    await flushPromises()
    confirmButton()!.click()
    await flushPromises()

    expect(orphansState.deleteOrphans).toHaveBeenCalledWith([mockup])
  })

  it('deletes every image of one table and reloads when some were kept', async () => {
    const secondPrintImage: OrphanImage = { ...printImage, path: 'print-images/c.webp' }
    orphansState.images = [mockup, printImage, secondPrintImage]
    orphansState.deleteOrphans = vi.fn(async () => ({
      deleted: [printImage],
      kept: [{ visibility: 'private' as const, path: secondPrintImage.path }],
    }))
    const wrapper = await mountView()

    await wrapper.get('[data-testid="delete-all-customer"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('Delete all 2 images?')
    confirmButton()!.click()
    await flushPromises()

    expect(orphansState.deleteOrphans).toHaveBeenCalledWith([printImage, secondPrintImage])
    expect(orphansState.fetchOrphans).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('1 image(s) were kept')
  })
})
