import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import ProductDraftUpload from '@/components/shop/editor/ProductDraftUpload.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

describe('ProductDraftUpload', () => {
  it('uses the theme-adaptive upload dropzone', () => {
    const wrapper = mount(ProductDraftUpload)

    const dropzone = wrapper.get('[data-testid="editor-draft-upload"] button')

    expect(dropzone.classes()).toContain('image-upload-dropzone--adaptive')
    expect(dropzone.classes()).not.toContain('image-upload-dropzone--light')
  })
})
