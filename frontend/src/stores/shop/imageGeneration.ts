import { ref, computed, markRaw, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchForm, type ApiFieldErrors } from '@/lib/api'
import { INSUFFICIENT_MAGIC_COINS_CODE } from '@/lib/magicCoins'
import { useMagicCoinsStore } from '@/stores/shop/magicCoins'

export interface GeneratedImage {
  id: string
  blob: Blob
  url: string
  createdAt: number
}

export const useImageGenerationStore = defineStore('imageGeneration', () => {
  const isGenerating = ref(false)
  const generatedImages = ref<GeneratedImage[]>([])
  const selectedImageId = ref<string | null>(null)
  const error = shallowRef<string | null>(null)
  const errorCode = shallowRef<string | null>(null)
  /**
   * HTTP status of the last refused generation. The generator route has no machine-readable code
   * for its two infrastructure refusals, so the status is the discriminator: `429` is the per-IP
   * rate limit, `413` the application-wide request-size bound.
   */
  const errorStatus = shallowRef<number | null>(null)
  /** Seconds the backend asked the client to wait, from the `Retry-After` header of a `429`. */
  const errorRetryAfterSeconds = shallowRef<number | null>(null)
  /**
   * Field errors of the last refused generation, keyed by the part name of the request.
   *
   * The generator has a size and type bound of its own — 10 MiB and JPEG/PNG/WebP — and it reports
   * a breach of either as a `400 Validation failed` on the `image` part, well below the
   * application-wide `413`. Without this, every such refusal reaches the user as the generic
   * "something went wrong" (`docs/dev/backend/generator-package.md`).
   */
  const errorFieldErrors = shallowRef<ApiFieldErrors>({})

  const selectedImageUrl = computed(
    () => generatedImages.value.find((img) => img.id === selectedImageId.value)?.url ?? null,
  )

  const hasImages = computed(() => generatedImages.value.length > 0)
  const imageCount = computed(() => generatedImages.value.length)

  function clearError() {
    error.value = null
    errorCode.value = null
    errorStatus.value = null
    errorRetryAfterSeconds.value = null
    errorFieldErrors.value = {}
  }

  async function generateImage(image: File | Blob, promptId: number) {
    isGenerating.value = true
    clearError()

    try {
      const magicCoinsStore = useMagicCoinsStore()
      if (magicCoinsStore.balance === null) {
        await magicCoinsStore.fetchBalance()
      }

      if (magicCoinsStore.balance === null) {
        error.value = magicCoinsStore.error ?? 'Failed to load Magic Coins'
        return
      }

      if (magicCoinsStore.balance <= 0) {
        errorCode.value = INSUFFICIENT_MAGIC_COINS_CODE
        error.value = 'Not enough Magic Coins'
        return
      }

      const formData = new FormData()
      const ext =
        { 'image/png': 'png', 'image/webp': 'webp', 'image/gif': 'gif' }[image.type] ?? 'jpg'
      const fileName = image instanceof File ? image.name : `cropped.${ext}`
      formData.append('image', image, fileName)
      formData.append('promptId', String(promptId))

      const blob = await fetchForm<Blob>('/api/generator/generate', formData, {
        responseType: 'blob',
      })
      const newImage: GeneratedImage = {
        id: crypto.randomUUID(),
        blob: markRaw(blob),
        url: URL.createObjectURL(blob),
        createdAt: Date.now(),
      }
      generatedImages.value.push(newImage)
      selectedImageId.value = newImage.id
      await magicCoinsStore.fetchBalance()
    } catch (err) {
      if (err instanceof ApiError) {
        errorCode.value = err.code
        errorStatus.value = err.status
        errorRetryAfterSeconds.value = err.retryAfterSeconds
        errorFieldErrors.value = err.fieldErrors
        error.value = err.message

        if (errorCode.value === INSUFFICIENT_MAGIC_COINS_CODE) {
          await useMagicCoinsStore().fetchBalance()
        }

        return
      }

      error.value = err instanceof Error ? err.message : 'Unknown error occurred'
    } finally {
      isGenerating.value = false
    }
  }

  function selectImage(id: string) {
    selectedImageId.value = id
  }

  function reset() {
    isGenerating.value = false
    for (const img of generatedImages.value) {
      URL.revokeObjectURL(img.url)
    }
    generatedImages.value = []
    selectedImageId.value = null
    clearError()
  }

  return {
    isGenerating,
    generatedImages,
    selectedImageId,
    selectedImageUrl,
    hasImages,
    imageCount,
    error,
    errorCode,
    errorStatus,
    errorRetryAfterSeconds,
    errorFieldErrors,
    generateImage,
    selectImage,
    reset,
  }
})
