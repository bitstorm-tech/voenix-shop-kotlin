import { computed, markRaw, ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import type { CropFrameTransform } from '@/stores/shop/cropFrame'
import type { GeneratedImage } from '@/stores/shop/imageGeneration'
import type { TextOverlay } from '@/stores/shop/textOverlays'

export type EditorDraftSource = 'wizard' | 'product' | 'order-redesign' | 'dev-fixture'

export interface EditorClipart {
  id: string
  [key: string]: unknown
}

export interface EditorImageEdits {
  cropTransform: CropFrameTransform
  textOverlays: TextOverlay[]
  cliparts: EditorClipart[]
}

export interface EditorImage {
  id: string
  blob: Blob
  url: string
  createdAt: number
  edits: EditorImageEdits
}

export interface EditorDraft {
  id: string
  source: EditorDraftSource
  images: EditorImage[]
  selectedImageId: string | null
  articleId: number
  variantId: number
  createdAt: number
}

export interface EditorDraftContext {
  articleId: number
  variantId: number
}

export interface CreateDraftFromGeneratedImagesInput extends EditorDraftContext {
  images: readonly GeneratedImage[]
}

export interface CreateDraftFromOrderRedesignInput extends EditorDraftContext {
  imageBlob: Blob
}

export interface EnsureDevDraftInput extends EditorDraftContext {
  id: string
  imageBlob: Blob
}

export interface AddUploadedImageToDraftInput {
  draftId: string
  imageBlob: Blob
}

export type EditorImageEditPatch = Partial<EditorImageEdits>

function createDefaultCropTransform(): CropFrameTransform {
  return { scale: 1, panX: 0, panY: 0 }
}

function createDefaultEdits(): EditorImageEdits {
  return {
    cropTransform: createDefaultCropTransform(),
    textOverlays: [],
    cliparts: [],
  }
}

function cloneTextOverlay(overlay: TextOverlay): TextOverlay {
  return { ...overlay }
}

function cloneCropTransform(transform: CropFrameTransform): CropFrameTransform {
  return { ...transform }
}

function cloneClipart(clipart: EditorClipart): EditorClipart {
  return { ...clipart }
}

function assertDraftContext({ articleId, variantId }: EditorDraftContext) {
  if (!Number.isInteger(articleId) || articleId <= 0) {
    throw new Error('Editor draft requires a valid articleId')
  }

  if (!Number.isInteger(variantId) || variantId <= 0) {
    throw new Error('Editor draft requires a valid variantId')
  }
}

export const useEditorStore = defineStore('editor', () => {
  const drafts = ref<EditorDraft[]>([])
  const selectedDraftId = shallowRef<string | null>(null)
  const editorOwnedUrls = new Set<string>()

  const currentDraft = computed(
    () => drafts.value.find((draft) => draft.id === selectedDraftId.value) ?? null,
  )

  const currentImage = computed(() => {
    const draft = currentDraft.value
    if (!draft?.selectedImageId) return null
    return draft.images.find((image) => image.id === draft.selectedImageId) ?? null
  })

  function createEditorImage(blob: Blob): EditorImage {
    const rawBlob = markRaw(blob)
    const url = URL.createObjectURL(rawBlob)
    editorOwnedUrls.add(url)

    return {
      id: crypto.randomUUID(),
      blob: rawBlob,
      url,
      createdAt: Date.now(),
      edits: createDefaultEdits(),
    }
  }

  function createDraft(
    context: EditorDraftContext,
    source: EditorDraftSource,
    images: EditorImage[] = [],
    id: string = crypto.randomUUID(),
  ): EditorDraft {
    const draft: EditorDraft = {
      id,
      source,
      images,
      selectedImageId: images[0]?.id ?? null,
      articleId: context.articleId,
      variantId: context.variantId,
      createdAt: Date.now(),
    }

    drafts.value.push(draft)
    selectedDraftId.value = draft.id
    return draft
  }

  function revokeEditorUrl(url: string) {
    if (!editorOwnedUrls.has(url)) return

    URL.revokeObjectURL(url)
    editorOwnedUrls.delete(url)
  }

  function revokeDraftUrls(draft: EditorDraft) {
    for (const image of draft.images) {
      revokeEditorUrl(image.url)
    }
  }

  function createDraftFromProduct(context: EditorDraftContext): EditorDraft {
    assertDraftContext(context)

    return createDraft(context, 'product')
  }

  function createDraftFromGeneratedImages(input: CreateDraftFromGeneratedImagesInput): EditorDraft {
    assertDraftContext(input)

    if (input.images.length === 0) {
      throw new Error('Editor draft requires at least one generated image')
    }

    const images = input.images.map((image) => createEditorImage(image.blob))
    return createDraft(input, 'wizard', images)
  }

  function createDraftFromOrderRedesign(input: CreateDraftFromOrderRedesignInput): EditorDraft {
    assertDraftContext(input)

    return createDraft(input, 'order-redesign', [createEditorImage(input.imageBlob)])
  }

  function ensureDevDraft(input: EnsureDevDraftInput): EditorDraft {
    assertDraftContext(input)

    const existingDraft = drafts.value.find((draft) => draft.id === input.id)
    if (existingDraft) {
      selectedDraftId.value = existingDraft.id
      return existingDraft
    }

    return createDraft(input, 'dev-fixture', [createEditorImage(input.imageBlob)], input.id)
  }

  function selectDraft(id: string | null) {
    if (id === null) {
      selectedDraftId.value = null
      return
    }

    if (drafts.value.some((draft) => draft.id === id)) {
      selectedDraftId.value = id
    }
  }

  function addUploadedImageToDraft(input: AddUploadedImageToDraftInput): EditorImage | null {
    const draft = drafts.value.find((item) => item.id === input.draftId)
    if (!draft) return null

    const image = createEditorImage(input.imageBlob)
    draft.images.push(image)
    draft.selectedImageId = image.id
    selectedDraftId.value = draft.id
    return image
  }

  function selectImage(id: string | null) {
    const draft = currentDraft.value
    if (!draft) return

    if (id === null) {
      draft.selectedImageId = null
      return
    }

    if (draft.images.some((image) => image.id === id)) {
      draft.selectedImageId = id
    }
  }

  function updateCurrentImageEdits(patch: EditorImageEditPatch): boolean {
    const image = currentImage.value
    if (!image) return false

    if (patch.cropTransform !== undefined) {
      image.edits.cropTransform = cloneCropTransform(patch.cropTransform)
    }

    if (patch.textOverlays !== undefined) {
      image.edits.textOverlays = patch.textOverlays.map(cloneTextOverlay)
    }

    if (patch.cliparts !== undefined) {
      image.edits.cliparts = patch.cliparts.map(cloneClipart)
    }

    return true
  }

  function removeDraft(id: string): boolean {
    const index = drafts.value.findIndex((draft) => draft.id === id)
    if (index === -1) return false

    const draft = drafts.value[index]!
    drafts.value.splice(index, 1)
    revokeDraftUrls(draft)

    if (selectedDraftId.value === id) {
      selectedDraftId.value = null
    }

    return true
  }

  function reset() {
    for (const draft of drafts.value) {
      revokeDraftUrls(draft)
    }

    drafts.value = []
    selectedDraftId.value = null
    editorOwnedUrls.clear()
  }

  return {
    drafts,
    selectedDraftId,
    currentDraft,
    currentImage,
    createDraftFromProduct,
    createDraftFromGeneratedImages,
    createDraftFromOrderRedesign,
    ensureDevDraft,
    selectDraft,
    addUploadedImageToDraft,
    selectImage,
    updateCurrentImageEdits,
    removeDraft,
    reset,
  }
})
