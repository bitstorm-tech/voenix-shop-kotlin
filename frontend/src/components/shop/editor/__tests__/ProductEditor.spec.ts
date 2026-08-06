import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import type { Component } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ProductEditor from '@/components/shop/editor/ProductEditor.vue'
import type { EditorArticle, EditorArticleVariant } from '@/components/shop/editor/types'
import { SegmentedControl } from '@/components/ui/segmented-control'
import { useEditorStore, type EditorDraft } from '@/stores/shop/editor'
import type { GeneratedImage } from '@/stores/shop/imageGeneration'
import type { TextOverlay } from '@/stores/shop/textOverlays'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({
    push: vi.fn(),
  }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({
    toast: vi.fn(),
  }),
}))

vi.mock('@/composables/useImageCoverRect', () => ({
  useImageCoverRect: () => ({ value: { x: 0, y: -200, width: 320, height: 640 } }),
}))

vi.mock('@/composables/useMugTexture', () => ({
  useMugTexture: vi.fn(),
}))

vi.mock('@google/model-viewer', () => ({}))

const variant: EditorArticleVariant = {
  id: 11,
  name: 'White',
  outsideColorCode: '#ffffff',
  insideColorCode: '#ffffff',
  isDefault: true,
  exampleImageFilename: null,
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

function generatedImage(id: string): GeneratedImage {
  return {
    id,
    blob: new Blob([id], { type: 'image/png' }),
    url: `blob:wizard-${id}`,
    createdAt: 1,
  }
}

function overlay(id: string, text: string): TextOverlay {
  return {
    id,
    text,
    rx: 0.5,
    ry: 0.5,
    fontFamily: 'Plus Jakarta Sans',
    fontSize: 64,
    color: 'oklch(0.99 0 0)',
    bold: false,
    italic: false,
    underline: false,
    rotation: 0,
  }
}

function createDraft(imageCount = 1) {
  const store = useEditorStore()
  return store.createDraftFromGeneratedImages({
    articleId: article.id,
    variantId: variant.id,
    images: Array.from({ length: imageCount }, (_, index) => generatedImage(`image-${index + 1}`)),
  })
}

function findToolButton(wrapper: ReturnType<typeof mountProductEditor>, labelKey: string) {
  return wrapper.findAll('.edit-tool-btn').find((button) => button.text().includes(labelKey))
}

function mountProductEditor(draft: EditorDraft, stubs: Record<string, boolean | Component> = {}) {
  return mount(ProductEditor, {
    props: {
      draft,
      article,
      variant,
    },
    global: {
      stubs: {
        TextToolPanel: {
          emits: ['addOverlay'],
          template:
            '<button type="button" data-testid="stub-add-text" @click="$emit(\'addOverlay\')" />',
        },
        CropFrameLayer: {
          emits: ['update:transform'],
          template:
            '<button type="button" data-testid="stub-crop-frame" @click="$emit(\'update:transform\', { scale: 1.4, panX: 12, panY: -8 })" />',
        },
        VariantGallery: {
          props: ['images'],
          emits: ['select'],
          template:
            '<div data-testid="stub-variants"><button v-for="image in images" :key="image.id" type="button" class="stub-variant" @click="$emit(\'select\', image.id)">{{ image.id }}</button></div>',
        },
        ...stubs,
      },
    },
  })
}

describe('ProductEditor', () => {
  let nextUuid = 1
  let nextUrl = 1

  beforeEach(() => {
    setActivePinia(createPinia())
    nextUuid = 1
    nextUrl = 1

    Object.defineProperty(URL, 'createObjectURL', {
      value: vi.fn(() => `blob:editor-${nextUrl++}`),
      configurable: true,
    })
    vi.stubGlobal('crypto', {
      randomUUID: () => `editor-id-${nextUuid++}`,
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('renders editor tools without article or variant switching UI', () => {
    const draft = createDraft(2)
    const wrapper = mountProductEditor(draft)

    expect(wrapper.findComponent(SegmentedControl).exists()).toBe(true)
    expect(wrapper.get('[data-testid="editor-product-context"]').text()).toContain('Classic Mug')
    expect(wrapper.text()).not.toContain('editor.tools.changeMug')
    expect(wrapper.findAll('.edit-tool-btn').map((button) => button.text())).toEqual([
      'editor.tools.text',
      'editor.tools.crop',
      'editor.tools.cliparts',
      'editor.tools.variants',
    ])
  })

  it('switches edit and preview modes through the segmented control', async () => {
    const draft = createDraft()
    const wrapper = mountProductEditor(draft)
    const modeButtons = wrapper.findAll('.edit-mode-btn')

    expect(modeButtons).toHaveLength(2)
    expect(modeButtons[0]!.attributes('data-state')).toBe('on')
    expect(modeButtons[1]!.attributes('data-state')).toBe('off')
    expect(wrapper.find('[data-testid="editor-layout"]').exists()).toBe(true)

    await modeButtons[1]!.trigger('click')
    await flushPromises()

    expect(modeButtons[0]!.attributes('data-state')).toBe('off')
    expect(modeButtons[1]!.attributes('data-state')).toBe('on')
    expect(wrapper.find('[data-testid="editor-preview-workspace"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="editor-layout"]').exists()).toBe(false)
  })

  it('keeps the controls shell rail-only until a tool is selected', async () => {
    const draft = createDraft()
    const wrapper = mountProductEditor(draft)
    const layout = wrapper.get('[data-testid="editor-layout"]')
    const sidepanel = wrapper.get('[data-testid="editor-sidepanel"]')
    const textTool = findToolButton(wrapper, 'editor.tools.text')!

    expect(layout.classes()).not.toContain('editor-layout--panel-open')
    expect(sidepanel.classes()).toContain('editor-sidepanel--rail-only')
    expect(sidepanel.classes()).not.toContain('editor-sidepanel--with-panel')
    expect(wrapper.find('[data-testid="editor-active-tool-panel"]').exists()).toBe(false)
    expect(textTool.attributes('aria-pressed')).toBe('false')

    await textTool.trigger('click')

    expect(layout.classes()).toContain('editor-layout--panel-open')
    expect(sidepanel.classes()).toContain('editor-sidepanel--with-panel')
    expect(sidepanel.classes()).not.toContain('editor-sidepanel--rail-only')
    expect(wrapper.find('[data-testid="editor-active-tool-panel"]').exists()).toBe(true)
    expect(textTool.attributes('aria-pressed')).toBe('true')

    await textTool.trigger('click')

    expect(layout.classes()).not.toContain('editor-layout--panel-open')
    expect(sidepanel.classes()).toContain('editor-sidepanel--rail-only')
    expect(wrapper.find('[data-testid="editor-active-tool-panel"]').exists()).toBe(false)
  })

  it('closes an open tool panel from the panel close control', async () => {
    const draft = createDraft()
    const wrapper = mountProductEditor(draft)
    const sidepanel = wrapper.get('[data-testid="editor-sidepanel"]')
    const textTool = findToolButton(wrapper, 'editor.tools.text')!

    await textTool.trigger('click')

    const closePanel = wrapper.get('[data-testid="editor-close-panel"]')
    expect(closePanel.attributes('aria-label')).toBe('editor.closePanel')

    await closePanel.trigger('click')

    expect(sidepanel.classes()).toContain('editor-sidepanel--rail-only')
    expect(sidepanel.classes()).not.toContain('editor-sidepanel--with-panel')
    expect(wrapper.find('[data-testid="editor-active-tool-panel"]').exists()).toBe(false)
    expect(textTool.attributes('aria-pressed')).toBe('false')
  })

  it('uses the print area as the edit surface and cover-crops the image inside it', async () => {
    const draft = createDraft()
    const wrapper = mountProductEditor(draft)
    await flushPromises()

    const cropWorkspace = wrapper.get('[data-testid="editor-crop-workspace"]')
    const printFrame = wrapper.get('[data-testid="editor-print-frame"]')
    const image = wrapper.get('[data-testid="editor-print-image"]')

    expect(cropWorkspace.classes()).not.toContain('editor-crop-workspace--active')
    expect(wrapper.find('[data-testid="editor-crop-preview-image"]').exists()).toBe(false)
    expect(printFrame.attributes('style')).toContain('aspect-ratio: 2.2222222222222223;')
    expect(printFrame.attributes('style')).not.toContain('clip-path')
    expect(image.attributes('style')).toContain('left: 0px;')
    expect(image.attributes('style')).toContain('top: -200px;')
    expect(image.attributes('style')).toContain('width: 320px;')
    expect(image.attributes('style')).toContain('height: 640px;')
  })

  it('shows a dimmed image context only while the crop tool is active', async () => {
    const draft = createDraft()
    const wrapper = mountProductEditor(draft)

    expect(wrapper.get('[data-testid="editor-crop-workspace"]').classes()).not.toContain(
      'editor-crop-workspace--active',
    )
    expect(wrapper.find('[data-testid="editor-crop-preview-image"]').exists()).toBe(false)

    await findToolButton(wrapper, 'editor.tools.crop')!.trigger('click')

    const cropWorkspace = wrapper.get('[data-testid="editor-crop-workspace"]')
    const cropPreview = wrapper.get('[data-testid="editor-crop-preview-image"]')
    const printFrame = wrapper.get('[data-testid="editor-print-frame"]')
    const printImage = wrapper.get('[data-testid="editor-print-image"]')

    expect(cropWorkspace.classes()).toContain('editor-crop-workspace--active')
    expect(cropPreview.attributes('src')).toBe(printImage.attributes('src'))
    expect(cropPreview.attributes('aria-hidden')).toBe('true')
    expect(cropPreview.attributes('draggable')).toBe('false')
    expect(cropPreview.attributes('style')).toContain('width: 320px;')
    expect(cropPreview.attributes('style')).toContain('height: 640px;')
    expect(printFrame.attributes('style')).toContain('aspect-ratio: 2.2222222222222223;')
  })

  it('writes added text overlays to the current editor image edits', async () => {
    const draft = createDraft()
    const store = useEditorStore()
    const wrapper = mountProductEditor(draft)

    await findToolButton(wrapper, 'editor.tools.text')!.trigger('click')
    await wrapper.get('[data-testid="stub-add-text"]').trigger('click')

    expect(store.currentImage?.edits.textOverlays).toHaveLength(1)
    expect(store.currentImage?.edits.textOverlays[0]).toMatchObject({
      id: 'editor-id-3',
      text: 'editor.textTool.defaultText',
    })
  })

  it('writes crop changes to the current editor image edits', async () => {
    const draft = createDraft()
    const store = useEditorStore()
    const wrapper = mountProductEditor(draft)

    await findToolButton(wrapper, 'editor.tools.crop')!.trigger('click')
    await wrapper.get('[data-testid="stub-crop-frame"]').trigger('click')

    expect(store.currentImage?.edits.cropTransform).toEqual({
      scale: 1.4,
      panX: 12,
      panY: -8,
    })
  })

  it('resets crop changes from the crop tool panel', async () => {
    const draft = createDraft()
    const store = useEditorStore()
    const wrapper = mountProductEditor(draft)

    await findToolButton(wrapper, 'editor.tools.crop')!.trigger('click')
    await wrapper.get('[data-testid="stub-crop-frame"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="editor-reset-crop"]').trigger('click')

    expect(store.currentImage?.edits.cropTransform).toEqual({
      scale: 1,
      panX: 0,
      panY: 0,
    })
  })

  it('keeps image variant edits isolated when switching variants', async () => {
    const draft = createDraft(2)
    const store = useEditorStore()
    const firstImage = draft.images[0]!
    const secondImage = draft.images[1]!
    store.updateCurrentImageEdits({ textOverlays: [overlay('overlay-a', 'First')] })
    const wrapper = mountProductEditor(draft)

    await findToolButton(wrapper, 'editor.tools.variants')!.trigger('click')
    await wrapper
      .findAll('.stub-variant')
      .find((button) => button.text() === secondImage.id)!
      .trigger('click')
    await flushPromises()

    expect(store.currentImage?.id).toBe(secondImage.id)
    expect(store.currentImage?.edits.textOverlays).toEqual([])

    await findToolButton(wrapper, 'editor.tools.text')!.trigger('click')
    await wrapper.get('[data-testid="stub-add-text"]').trigger('click')

    expect(secondImage.edits.textOverlays).toHaveLength(1)
    expect(firstImage.edits.textOverlays).toEqual([overlay('overlay-a', 'First')])

    await findToolButton(wrapper, 'editor.tools.variants')!.trigger('click')
    await wrapper
      .findAll('.stub-variant')
      .find((button) => button.text() === firstImage.id)!
      .trigger('click')

    expect(store.currentImage?.id).toBe(firstImage.id)
    expect(store.currentImage?.edits.textOverlays).toEqual([overlay('overlay-a', 'First')])
  })

  it('keeps the cliparts tool visible as a disabled placeholder', async () => {
    const draft = createDraft()
    const wrapper = mountProductEditor(draft)

    await findToolButton(wrapper, 'editor.tools.cliparts')!.trigger('click')

    expect(
      wrapper.get('[data-testid="editor-cliparts-placeholder"]').attributes('aria-disabled'),
    ).toBe('true')
  })
})
