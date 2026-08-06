import { ref, computed } from 'vue'
import { defineStore } from 'pinia'

const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif']
const MAX_SIZE_BYTES = 10 * 1024 * 1024 // 10 MB

export interface CropState {
  rx: number // x-offset as fraction of image width (0–1)
  ry: number // y-offset as fraction of image height (0–1)
  rw: number // width as fraction of image width (0–1)
  rh: number // height as fraction of image height (0–1)
  rotation: number // rotation in degrees (0, 90, 180, 270)
}

export const useWizardStore = defineStore('wizard', () => {
  const uploadedFile = ref<File | null>(null)
  const previewUrl = ref<string | null>(null)
  const validationError = ref<{ type: 'fileType' | 'fileSize' } | null>(null)

  const croppedBlob = ref<Blob | null>(null)
  const croppedPreviewUrl = ref<string | null>(null)
  const cropState = ref<CropState | null>(null)

  const hasUploadedImage = computed(() => !!uploadedFile.value)
  const fileName = computed(() => uploadedFile.value?.name ?? null)
  const fileSize = computed(() => uploadedFile.value?.size ?? null)
  const effectivePreviewUrl = computed(() => croppedPreviewUrl.value ?? previewUrl.value)
  const imageForGeneration = computed(() => croppedBlob.value ?? uploadedFile.value)

  const selectedMugId = ref<number | null>(null)
  const selectedVariantId = ref<number | null>(null)
  const hasSelectedMug = computed(
    () => selectedMugId.value !== null && selectedVariantId.value !== null,
  )

  function selectMug(mugId: number, variantId?: number) {
    selectedMugId.value = mugId
    selectedVariantId.value = variantId ?? null
  }

  function selectVariant(variantId: number) {
    selectedVariantId.value = variantId
  }

  function clearMugSelection() {
    selectedMugId.value = null
    selectedVariantId.value = null
  }

  const selectedPromptId = ref<number | null>(null)
  const hasSelectedPrompt = computed(() => selectedPromptId.value !== null)

  function selectPrompt(promptId: number) {
    selectedPromptId.value = promptId
  }

  function clearPromptSelection() {
    selectedPromptId.value = null
  }

  function setImage(file: File): boolean {
    validationError.value = null

    if (!ALLOWED_TYPES.includes(file.type)) {
      validationError.value = { type: 'fileType' }
      return false
    }

    if (file.size > MAX_SIZE_BYTES) {
      validationError.value = { type: 'fileSize' }
      return false
    }

    if (previewUrl.value) {
      URL.revokeObjectURL(previewUrl.value)
    }

    clearCrop()
    uploadedFile.value = file
    previewUrl.value = URL.createObjectURL(file)
    return true
  }

  function setCroppedImage(blob: Blob, state: CropState) {
    if (croppedPreviewUrl.value) {
      URL.revokeObjectURL(croppedPreviewUrl.value)
    }
    croppedBlob.value = blob
    croppedPreviewUrl.value = URL.createObjectURL(blob)
    cropState.value = state
  }

  function clearCrop() {
    if (croppedPreviewUrl.value) {
      URL.revokeObjectURL(croppedPreviewUrl.value)
    }
    croppedBlob.value = null
    croppedPreviewUrl.value = null
    cropState.value = null
  }

  function removeImage() {
    if (previewUrl.value) {
      URL.revokeObjectURL(previewUrl.value)
    }
    clearCrop()
    uploadedFile.value = null
    previewUrl.value = null
    validationError.value = null
  }

  function resetWizard() {
    removeImage()
    clearMugSelection()
    clearPromptSelection()
  }

  return {
    uploadedFile,
    previewUrl,
    validationError,
    hasUploadedImage,
    fileName,
    fileSize,
    effectivePreviewUrl,
    imageForGeneration,
    cropState,
    setImage,
    setCroppedImage,
    removeImage,
    selectedMugId,
    selectedVariantId,
    hasSelectedMug,
    selectMug,
    selectVariant,
    clearMugSelection,
    selectedPromptId,
    hasSelectedPrompt,
    selectPrompt,
    clearPromptSelection,
    resetWizard,
  }
})
