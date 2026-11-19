import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AdminImageEditDialog from '../AdminImageEditDialog.vue'

const mocks = vi.hoisted(() => ({ removeImageBackground: vi.fn() }))

vi.mock('@/lib/backgroundRemoval', () => ({
  removeImageBackground: mocks.removeImageBackground,
}))

/** The shop dialog itself needs layout and a canvas; the stub shows its props and the tools slot. */
const cropDialogStub = {
  props: ['open', 'imageSrc', 'initialCropState', 'applyDisabled'],
  emits: ['crop', 'update:open'],
  template: `<div data-testid="crop-dialog" :data-src="imageSrc" :data-apply-disabled="String(applyDisabled)">
    <slot name="tools" />
  </div>`,
}

const photo = new File(['photo'], 'photo.png', { type: 'image/png' })
let objectUrls = 0

function mountDialog(source: Blob | string | null = photo) {
  return mount(AdminImageEditDialog, {
    props: { open: true, source },
    global: { stubs: { ImageCropDialog: cropDialogStub } },
  })
}

const removeButton = (wrapper: ReturnType<typeof mountDialog>) =>
  wrapper.get('[data-testid="image-edit-remove-background"]')
const shownSrc = (wrapper: ReturnType<typeof mountDialog>) =>
  wrapper.get('[data-testid="crop-dialog"]').attributes('data-src')

describe('AdminImageEditDialog', () => {
  beforeEach(() => {
    mocks.removeImageBackground.mockReset()
    objectUrls = 0
    // jsdom has no object URLs.
    URL.createObjectURL = vi.fn(() => `blob:${String(++objectUrls)}`)
    URL.revokeObjectURL = vi.fn()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('starts from the whole image of a chosen file', () => {
    const wrapper = mountDialog()
    expect(shownSrc(wrapper)).toBe('blob:1')
    expect(wrapper.getComponent(cropDialogStub).props('initialCropState')).toEqual({
      rx: 0,
      ry: 0,
      rw: 1,
      rh: 1,
      rotation: 0,
    })
  })

  it('removes the background of the shown image and swaps in the cut-out', async () => {
    let finish: (blob: Blob) => void = () => undefined
    mocks.removeImageBackground.mockReturnValue(new Promise<Blob>((resolve) => (finish = resolve)))
    const wrapper = mountDialog()

    await removeButton(wrapper).trigger('click')
    expect(mocks.removeImageBackground).toHaveBeenCalledWith(photo)
    expect(removeButton(wrapper).text()).toBe('Removing background…')
    expect(removeButton(wrapper).attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="crop-dialog"]').attributes('data-apply-disabled')).toBe(
      'true',
    )

    finish(new Blob(['cut'], { type: 'image/png' }))
    await flushPromises()
    expect(shownSrc(wrapper)).toBe('blob:2')
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:1')
    expect(wrapper.get('[data-testid="crop-dialog"]').attributes('data-apply-disabled')).toBe(
      'false',
    )
  })

  it('reads a stored image from its URL before removing its background', async () => {
    const stored = new Blob(['stored'], { type: 'image/webp' })
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(stored))
    mocks.removeImageBackground.mockResolvedValue(new Blob(['cut'], { type: 'image/png' }))
    const wrapper = mountDialog('/api/images/public/1600/prompt-example-images/a.webp')
    expect(shownSrc(wrapper)).toBe('/api/images/public/1600/prompt-example-images/a.webp')

    await removeButton(wrapper).trigger('click')
    await flushPromises()
    expect(globalThis.fetch).toHaveBeenCalledWith(
      '/api/images/public/1600/prompt-example-images/a.webp',
    )
    expect(shownSrc(wrapper)).toBe('blob:1')
  })

  it('keeps the image and shows the error when the removal fails', async () => {
    mocks.removeImageBackground.mockRejectedValue(new Error('Background removal failed'))
    const wrapper = mountDialog()

    await removeButton(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="image-edit-error"]').text()).toBe('Background removal failed')
    expect(shownSrc(wrapper)).toBe('blob:1')
    expect(removeButton(wrapper).attributes('disabled')).toBeUndefined()
  })

  it('applies the edited image as a WebP file', async () => {
    const wrapper = mountDialog()
    wrapper.getComponent(cropDialogStub).vm.$emit('crop', new Blob(['edited']), {})
    await flushPromises()

    const applied = wrapper.emitted('apply')?.[0]?.[0] as File
    expect(applied).toBeInstanceOf(File)
    expect(applied.name).toBe('edited-image.webp')
    expect(applied.type).toBe('image/webp')
  })
})
