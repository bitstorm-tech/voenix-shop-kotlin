import { mount } from '@vue/test-utils'
import { compileStyle, parse } from '@vue/compiler-sfc'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import ProductContextBar from '@/components/shop/editor/ProductContextBar.vue'
import type { EditorArticle, EditorArticleVariant } from '@/components/shop/editor/types'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string, values?: Record<string, number>) =>
      values ? `${key} ${values.width} x ${values.height}` : key,
  }),
}))

const componentPath = resolve(process.cwd(), 'src/components/shop/editor/ProductContextBar.vue')

const variant: EditorArticleVariant = {
  id: 11,
  name: 'White',
  outsideColorCode: '#ffffff',
  insideColorCode: '#ffffff',
  isDefault: true,
  exampleImageFilename: 'white-mug.png',
}

const article: EditorArticle = {
  id: 1,
  type: 'MUG',
  name: 'Classic Mug',
  descriptionShort: 'Short',
  price: 1499,
  printArea: {
    documentFormatWidthMm: 200,
    documentFormatHeightMm: 90,
    aspectRatio: 200 / 90,
  },
  variants: [variant],
}

function compileScopedCss() {
  const source = readFileSync(componentPath, 'utf-8')
  const { descriptor } = parse(source)
  const style = descriptor.styles[0]

  if (!style) {
    throw new Error('ProductContextBar.vue has no style block')
  }

  return compileStyle({
    source: style.content,
    filename: componentPath,
    id: 'data-v-product-context-test',
    scoped: style.scoped,
  }).code
}

describe('ProductContextBar', () => {
  it('renders the selected product metadata', () => {
    const wrapper = mount(ProductContextBar, {
      props: {
        article,
        variant,
      },
    })

    expect(wrapper.get('[data-testid="editor-product-context"]').text()).toContain('Classic Mug')
    expect(wrapper.get('[data-testid="editor-product-context"]').text()).toContain('White')
    expect(wrapper.get('[data-testid="editor-product-context"]').text()).not.toContain(
      'editor.context.label',
    )
    expect(wrapper.find('.product-context-colors').exists()).toBe(false)
    expect(wrapper.find('.product-context-swatch').exists()).toBe(false)
  })

  it('shows the selected variant article image', () => {
    const wrapper = mount(ProductContextBar, {
      props: {
        article,
        variant,
      },
    })

    const image = wrapper.get('img.product-context-image')

    expect(image.attributes('src')).toBe(
      '/api/images/public/200/articles/mugs/variant-example-images/white-mug.png',
    )
    expect(image.attributes('alt')).toBe('Classic Mug White')
  })

  it('keeps dark-mode styles scoped to the product context bar', () => {
    const css = compileScopedCss()

    expect(css).toContain('.dark .product-context[data-v-product-context-test]')
    expect(css).toContain('.dark .product-context-media[data-v-product-context-test]')
    expect(css).not.toContain('product-context-swatch')
    expect(css).not.toContain(':global(.dark)')
  })
})
