import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import VariantGallery from '@/components/shop/editor/VariantGallery.vue'
import { ThumbnailButton } from '@/components/ui/thumbnail-button'
import type { EditorImage } from '@/stores/shop/editor'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string, params?: Record<string, unknown>) =>
      params ? `${key}:${String(params.number)}` : key,
  }),
}))

function image(id: string, createdAt: number): EditorImage {
  return {
    id,
    blob: new Blob([id], { type: 'image/png' }),
    url: `blob:${id}`,
    createdAt,
    edits: {
      cropTransform: { scale: 1, panX: 0, panY: 0 },
      textOverlays: [],
      cliparts: [],
    },
  }
}

describe('VariantGallery', () => {
  it('renders editor variants as thumbnail buttons and emits selected image ids', async () => {
    const images = [image('first', 1), image('second', 2), image('third', 3)]
    const wrapper = mount(VariantGallery, {
      props: {
        images,
        selectedImageId: 'second',
      },
    })
    const thumbnails = wrapper.findAllComponents(ThumbnailButton)

    expect(thumbnails).toHaveLength(3)
    expect(thumbnails.map((thumbnail) => thumbnail.get('img').attributes('src'))).toEqual([
      'blob:third',
      'blob:second',
      'blob:first',
    ])
    expect(thumbnails[1]!.attributes('data-state')).toBe('selected')

    await thumbnails[0]!.trigger('click')

    expect(wrapper.emitted('select')).toEqual([['third']])
  })
})
