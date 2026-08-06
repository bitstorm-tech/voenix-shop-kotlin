import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useEditorStore, type EditorImageEditPatch } from '@/stores/shop/editor'
import type { GeneratedImage } from '@/stores/shop/imageGeneration'
import type { TextOverlay } from '@/stores/shop/textOverlays'

function createBlob(value: string) {
  return new Blob([value], { type: 'image/png' })
}

function createGeneratedImage(id: string, blob: Blob, url: string): GeneratedImage {
  return {
    id,
    blob,
    url,
    createdAt: 1,
  }
}

function createOverlay(id: string, text: string): TextOverlay {
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

describe('editor store', () => {
  let nextUuid = 1
  let nextUrl = 1

  beforeEach(() => {
    setActivePinia(createPinia())
    vi.restoreAllMocks()
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
  })

  it('creates a product draft with article and variant context but no selected image', () => {
    const store = useEditorStore()

    const draft = store.createDraftFromProduct({ articleId: 10, variantId: 20 })

    expect(draft).toMatchObject({
      id: 'editor-id-1',
      source: 'product',
      articleId: 10,
      variantId: 20,
      images: [],
      selectedImageId: null,
    })
    expect(store.selectedDraftId).toBe(draft.id)
    expect(store.currentDraft?.id).toBe(draft.id)
    expect(store.currentImage).toBeNull()
    expect(URL.createObjectURL).not.toHaveBeenCalled()
  })

  it('requires article and variant ids before creating any draft object URLs', () => {
    const store = useEditorStore()

    expect(() =>
      store.createDraftFromProduct({ articleId: 10, variantId: undefined as unknown as number }),
    ).toThrow('variantId')
    expect(() =>
      store.createDraftFromGeneratedImages({
        articleId: 0,
        variantId: 20,
        images: [createGeneratedImage('generated-1', createBlob('generated'), 'blob:wizard-1')],
      }),
    ).toThrow('articleId')
    expect(() =>
      store.createDraftFromOrderRedesign({
        articleId: 10,
        variantId: -1,
        imageBlob: createBlob('order'),
      }),
    ).toThrow('variantId')

    expect(store.drafts).toHaveLength(0)
    expect(URL.createObjectURL).not.toHaveBeenCalled()
  })

  it('creates a wizard draft from generated blobs with editor-owned object URLs', () => {
    const store = useEditorStore()
    const firstBlob = createBlob('first')
    const secondBlob = createBlob('second')

    const draft = store.createDraftFromGeneratedImages({
      articleId: 11,
      variantId: 21,
      images: [
        createGeneratedImage('generated-1', firstBlob, 'blob:wizard-1'),
        createGeneratedImage('generated-2', secondBlob, 'blob:wizard-2'),
      ],
    })

    expect(draft.source).toBe('wizard')
    expect(draft.images).toHaveLength(2)
    expect(draft.images[0]).toMatchObject({
      id: 'editor-id-1',
      blob: firstBlob,
      url: 'blob:editor-1',
    })
    expect(draft.images[1]).toMatchObject({
      id: 'editor-id-2',
      blob: secondBlob,
      url: 'blob:editor-2',
    })
    expect(draft.selectedImageId).toBe('editor-id-1')
    expect(draft.images.map((image) => image.url)).not.toContain('blob:wizard-1')
    expect(URL.createObjectURL).toHaveBeenCalledWith(firstBlob)
    expect(URL.createObjectURL).toHaveBeenCalledWith(secondBlob)
  })

  it('creates an order-redesign draft from an existing image blob', () => {
    const store = useEditorStore()
    const imageBlob = createBlob('order image')

    const draft = store.createDraftFromOrderRedesign({
      articleId: 12,
      variantId: 22,
      imageBlob,
    })

    expect(draft).toMatchObject({
      source: 'order-redesign',
      articleId: 12,
      variantId: 22,
      selectedImageId: 'editor-id-1',
    })
    expect(draft.images).toHaveLength(1)
    expect(draft.images[0]).toMatchObject({
      blob: imageBlob,
      url: 'blob:editor-1',
    })
    expect(store.currentImage?.id).toBe(draft.images[0]!.id)
  })

  it('creates and reuses a development fixture draft with a selected image', () => {
    const store = useEditorStore()
    const firstBlob = createBlob('dev first')
    const secondBlob = createBlob('dev second')

    const firstDraft = store.ensureDevDraft({
      id: 'test',
      articleId: 17,
      variantId: 27,
      imageBlob: firstBlob,
    })
    const secondDraft = store.ensureDevDraft({
      id: 'test',
      articleId: 17,
      variantId: 27,
      imageBlob: secondBlob,
    })

    expect(secondDraft.id).toBe(firstDraft.id)
    expect(firstDraft).toMatchObject({
      id: 'test',
      source: 'dev-fixture',
      articleId: 17,
      variantId: 27,
      selectedImageId: 'editor-id-1',
    })
    expect(firstDraft.images).toHaveLength(1)
    expect(firstDraft.images[0]).toMatchObject({
      blob: firstBlob,
      url: 'blob:editor-1',
    })
    expect(store.drafts).toHaveLength(1)
    expect(store.selectedDraftId).toBe(firstDraft.id)
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1)
    expect(URL.createObjectURL).toHaveBeenCalledWith(firstBlob)
  })

  it('adds an uploaded image to an existing product draft and selects it', () => {
    const store = useEditorStore()
    const draft = store.createDraftFromProduct({ articleId: 13, variantId: 23 })

    const image = store.addUploadedImageToDraft({
      draftId: draft.id,
      imageBlob: createBlob('upload'),
    })

    expect(image).toMatchObject({
      id: 'editor-id-2',
      url: 'blob:editor-1',
    })
    expect(draft.images.map((draftImage) => draftImage.id)).toEqual([image?.id])
    expect(draft.selectedImageId).toBe(image?.id)
    expect(store.currentImage?.id).toBe(image?.id)
  })

  it('keeps edits isolated per editor image', () => {
    const store = useEditorStore()
    const draft = store.createDraftFromGeneratedImages({
      articleId: 14,
      variantId: 24,
      images: [
        createGeneratedImage('generated-1', createBlob('first'), 'blob:wizard-1'),
        createGeneratedImage('generated-2', createBlob('second'), 'blob:wizard-2'),
      ],
    })
    const imageA = draft.images[0]!
    const imageB = draft.images[1]!
    const imageAEdits: EditorImageEditPatch = {
      textOverlays: [createOverlay('overlay-a', 'A')],
    }
    const imageBEdits: EditorImageEditPatch = {
      cropTransform: { scale: 1.5, panX: 12, panY: -8 },
      textOverlays: [createOverlay('overlay-b', 'B')],
      cliparts: [{ id: 'clipart-b', name: 'Star' }],
    }

    store.selectImage(imageA.id)
    expect(store.updateCurrentImageEdits(imageAEdits)).toBe(true)
    store.selectImage(imageB.id)
    expect(store.updateCurrentImageEdits(imageBEdits)).toBe(true)

    expect(imageA.edits).toEqual({
      cropTransform: { scale: 1, panX: 0, panY: 0 },
      textOverlays: [createOverlay('overlay-a', 'A')],
      cliparts: [],
    })
    expect(imageB.edits).toEqual({
      cropTransform: { scale: 1.5, panX: 12, panY: -8 },
      textOverlays: [createOverlay('overlay-b', 'B')],
      cliparts: [{ id: 'clipart-b', name: 'Star' }],
    })
  })

  it('revokes only editor-owned object URLs when drafts are removed and reset', () => {
    const store = useEditorStore()
    const firstDraft = store.createDraftFromGeneratedImages({
      articleId: 15,
      variantId: 25,
      images: [
        createGeneratedImage('generated-1', createBlob('first'), 'blob:wizard-1'),
        createGeneratedImage('generated-2', createBlob('second'), 'blob:wizard-2'),
      ],
    })
    const secondDraft = store.createDraftFromOrderRedesign({
      articleId: 16,
      variantId: 26,
      imageBlob: createBlob('order'),
    })

    expect(store.removeDraft(firstDraft.id)).toBe(true)

    expect(URL.revokeObjectURL).toHaveBeenCalledTimes(2)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:editor-1')
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:editor-2')
    expect(URL.revokeObjectURL).not.toHaveBeenCalledWith('blob:wizard-1')
    expect(URL.revokeObjectURL).not.toHaveBeenCalledWith('blob:wizard-2')
    expect(store.drafts.map((draft) => draft.id)).toEqual([secondDraft.id])

    store.reset()

    expect(URL.revokeObjectURL).toHaveBeenCalledTimes(3)
    expect(URL.revokeObjectURL).toHaveBeenLastCalledWith('blob:editor-3')
    expect(store.drafts).toHaveLength(0)
    expect(store.selectedDraftId).toBeNull()
  })
})
