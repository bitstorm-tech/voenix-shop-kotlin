import { mount } from '@vue/test-utils'
import { compileStyle, parse } from '@vue/compiler-sfc'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { FileInput } from '@/components/ui/file-input'
import ImageUploadDropzone from '@/components/shop/ImageUploadDropzone.vue'

const componentPath = resolve(process.cwd(), 'src/components/shop/ImageUploadDropzone.vue')

function compileScopedCss() {
  const source = readFileSync(componentPath, 'utf-8')
  const { descriptor } = parse(source)
  const style = descriptor.styles[0]

  if (!style) {
    throw new Error('ImageUploadDropzone.vue has no style block')
  }

  return compileStyle({
    source: style.content,
    filename: componentPath,
    id: 'data-v-upload-test',
    scoped: style.scoped,
  }).code
}

describe('ImageUploadDropzone', () => {
  it('emits the selected image file', async () => {
    const wrapper = mount(ImageUploadDropzone, {
      props: {
        title: 'Upload image',
        inputTestId: 'upload-input',
      },
    })
    const file = new File(['image'], 'image.png', { type: 'image/png' })
    const input = wrapper.get('[data-testid="upload-input"]')

    Object.defineProperty(input.element, 'files', {
      value: [file],
      configurable: true,
    })

    await input.trigger('change')

    expect(wrapper.emitted('upload')).toEqual([[file]])
  })

  it('composes the shared FileInput primitive for picker behavior', () => {
    const wrapper = mount(ImageUploadDropzone, {
      props: {
        title: 'Upload image',
        inputTestId: 'upload-input',
      },
    })

    expect(wrapper.findComponent(FileInput).exists()).toBe(true)
    expect(wrapper.get('[data-testid="upload-input"]').attributes('type')).toBe('file')
    expect(wrapper.find('input[type="file"]').exists()).toBe(true)
  })

  it('keeps dark-mode styles scoped and supports a light-toned dropzone', () => {
    const css = compileScopedCss()

    expect(css).toContain(
      '.dark .image-upload[data-v-upload-test] .image-upload-dropzone--adaptive',
    )
    expect(css).toContain('.image-upload[data-v-upload-test] .image-upload-dropzone--light')
    expect(css).toContain('.dark .image-upload[data-v-upload-test] .image-upload-dropzone--light')
    expect(css).not.toContain(':global(.dark)')
  })
})
