import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import EditorView from '@/views/shop/EditorView.vue'
import { composeImage } from '@/lib/composeImage'
import { useCartStore } from '@/stores/shop/cart'
import { useEditorStore } from '@/stores/shop/editor'
import type { GeneratedImage } from '@/stores/shop/imageGeneration'
import { useMugsStore, type MugDto } from '@/stores/shop/mugs'
import { createMugVariant, createShopMug } from '@/testing/shopCatalog'
import type { TextOverlay } from '@/stores/shop/textOverlays'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
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

vi.mock('@/lib/composeImage', () => ({
  composeImage: vi.fn(),
}))

function makeMug(id = 1): MugDto {
  return createShopMug({
    id,
    name: 'Classic Mug',
    descriptionShort: 'Short',
    descriptionLong: 'Long',
    categoryId: 1,
    variants: [
      createMugVariant({ id: 11, exampleImageFilename: 'white-mug.png' }),
      createMugVariant({
        id: 12,
        name: 'Black',
        outsideColorCode: '#111111',
        insideColorCode: '#111111',
        isDefault: false,
      }),
    ],
  })
}

function generatedImage(id: string): GeneratedImage {
  return {
    id,
    blob: new Blob([id], { type: 'image/png' }),
    url: `blob:wizard-${id}`,
    createdAt: 1,
  }
}

function overlay(id: string): TextOverlay {
  return {
    id,
    text: 'Keep me',
    rx: 0.25,
    ry: 0.35,
    fontFamily: 'Plus Jakarta Sans',
    fontSize: 48,
    color: 'oklch(0.99 0 0)',
    bold: true,
    italic: false,
    underline: false,
    rotation: 12,
  }
}

function createRouterForEditor() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/editor/:draftId?', name: 'editor', component: EditorView },
      { path: '/mugs', name: 'mugs', component: { template: '<div />' } },
      { path: '/cart', name: 'cart', component: { template: '<div data-testid="cart" />' } },
    ],
  })
}

async function mountAt(router: Router, path: string) {
  await router.push(path)
  await router.isReady()

  const wrapper = mount(EditorView, {
    global: {
      plugins: [router],
      stubs: {
        Button: {
          template: '<button v-bind="$attrs"><slot /></button>',
        },
        TextToolPanel: true,
        CropFrameLayer: true,
        TextOverlayLayer: true,
      },
    },
  })
  await flushPromises()

  return wrapper
}

function mockLoadedMugs(mugs: MugDto[]) {
  const mugsStore = useMugsStore()
  mugsStore.mugs = mugs
  vi.spyOn(mugsStore, 'fetchMugs').mockResolvedValue()
  return mugsStore
}

function mockComposeCanvas() {
  const blob = new Blob(['composed-print'], { type: 'image/png' })
  const canvas = {
    toBlob: vi.fn((callback: BlobCallback) => callback(blob)),
  } as unknown as HTMLCanvasElement
  vi.mocked(composeImage).mockResolvedValue(canvas)

  return { blob, canvas }
}

describe('EditorView', () => {
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
    Object.defineProperty(URL, 'revokeObjectURL', {
      value: vi.fn(),
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

  it('shows the /editor guard without a free upload entry', async () => {
    const router = createRouterForEditor()
    const wrapper = await mountAt(router, '/editor')

    expect(wrapper.get('[data-testid="editor-state-guard"]').text()).toContain(
      'editor.states.guard.title',
    )
    expect(wrapper.find('[data-testid="editor-upload-input"]').exists()).toBe(false)
    expect(wrapper.get('a').attributes('href')).toBe('/mugs')
  })

  it('shows a missing-draft state when the draft id is unknown', async () => {
    const router = createRouterForEditor()
    mockLoadedMugs([makeMug()])

    const wrapper = await mountAt(router, '/editor/missing-draft')

    expect(wrapper.get('[data-testid="editor-state-missing"]').text()).toContain(
      'editor.states.missing.title',
    )
  })

  it('shows an invalid-context state when article or variant are not valid', async () => {
    const editorStore = useEditorStore()
    const draft = editorStore.createDraftFromProduct({ articleId: 99, variantId: 990 })
    const router = createRouterForEditor()
    mockLoadedMugs([makeMug()])

    const wrapper = await mountAt(router, `/editor/${draft.id}`)

    expect(wrapper.get('[data-testid="editor-state-invalid"]').text()).toContain(
      'editor.states.invalid.title',
    )
  })

  it('shows upload entry for a valid product draft without an image', async () => {
    const editorStore = useEditorStore()
    const draft = editorStore.createDraftFromProduct({ articleId: 1, variantId: 11 })
    const router = createRouterForEditor()
    mockLoadedMugs([makeMug()])

    const wrapper = await mountAt(router, `/editor/${draft.id}`)

    expect(wrapper.get('[data-testid="editor-product-context"]').text()).toContain('Classic Mug')
    expect(wrapper.get('img.product-context-image').attributes('src')).toBe(
      '/api/images/public/200/articles/mugs/variant-example-images/white-mug.png',
    )
    expect(wrapper.get('[data-testid="editor-draft-upload"]').text()).toContain(
      'editor.upload.title',
    )
    expect(wrapper.get('[data-testid="editor-add-to-cart"]').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain('editor.tools.changeMug')
  })

  it('adds to cart with the draft article, draft variant, and composed current image edits', async () => {
    const editorStore = useEditorStore()
    const draft = editorStore.createDraftFromGeneratedImages({
      articleId: 1,
      variantId: 11,
      images: [generatedImage('image-1')],
    })
    const image = draft.images[0]!
    const textOverlays = [overlay('overlay-1')]
    editorStore.updateCurrentImageEdits({
      cropTransform: { scale: 1.7, panX: 24, panY: -12 },
      textOverlays,
    })
    const router = createRouterForEditor()
    mockLoadedMugs([makeMug()])
    const cartStore = useCartStore()
    const addToCart = vi.spyOn(cartStore, 'addToCart').mockResolvedValue()
    const { blob } = mockComposeCanvas()
    const wrapper = await mountAt(router, `/editor/${draft.id}`)

    await wrapper.get('[data-testid="editor-add-to-cart"]').trigger('click')
    await flushPromises()

    expect(composeImage).toHaveBeenCalledWith({
      imageUrl: image.url,
      frameAspectRatio: 200 / 90,
      cropTransform: { scale: 1.7, panX: 24, panY: -12 },
      screenFrameWidth: 1,
      textOverlays,
    })
    expect(addToCart).toHaveBeenCalledWith(
      {
        articleId: 1,
        variantId: 11,
        quantity: 1,
      },
      blob,
    )
    expect(router.currentRoute.value.name).toBe('cart')
  })
})
