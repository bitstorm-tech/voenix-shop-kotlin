import { ref, computed, markRaw, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchForm, type ApiFieldErrors } from '@/lib/api'

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
   * "something went wrong" (`docs/dev/backend/packages/generator-package.md`).
   */
  const errorFieldErrors = shallowRef<ApiFieldErrors>({})

  const selectedImageUrl = computed(
    () => generatedImages.value.find((img) => img.id === selectedImageId.value)?.url ?? null,
  )

  const hasImages = computed(() => generatedImages.value.length > 0)
  const imageCount = computed(() => generatedImages.value.length)

  /**
   * Monotonic counter incremented on every new generation and every reset so late responses from
   * superseded or reset requests are discarded instead of polluting the new wizard inputs.
   */
  let requestGeneration = 0
  let abortController: AbortController | null = null

  function clearError() {
    error.value = null
    errorCode.value = null
    errorStatus.value = null
    errorRetryAfterSeconds.value = null
    errorFieldErrors.value = {}
  }

  /**
   * Generates one design for exactly one article.
   *
   * [articleId] is required by the route and is not decoration: the generator asks the article
   * catalog for the type behind the id and generates in the format that type prints in — a mug
   * wrap or a shirt's print ratio (issue #205). A request without it is a `400` with a field error
   * on `articleId`, so the caller must have a selected article before it starts.
   *
   * Magic Coin balances are deliberately never queried ahead of time; an exhausted balance surfaces
   * directly from `POST /api/generator/generate` as `402 INSUFFICIENT_MAGIC_COINS`.
   */
  async function generateImage(image: File | Blob, promptId: number, articleId: number) {
    requestGeneration += 1
    const activeGeneration = requestGeneration
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller

    isGenerating.value = true
    clearError()

    try {
      const formData = new FormData()
      const ext = { 'image/png': 'png', 'image/webp': 'webp' }[image.type] ?? 'jpg'
      const fileName = image instanceof File ? image.name : `cropped.${ext}`
      formData.append('image', image, fileName)
      formData.append('promptId', String(promptId))
      formData.append('articleId', String(articleId))

      const blob = await fetchForm<Blob>('/api/generator/generate', formData, {
        responseType: 'blob',
        signal: controller.signal,
      })

      if (activeGeneration !== requestGeneration) {
        return
      }

      const newImage: GeneratedImage = {
        id: crypto.randomUUID(),
        blob: markRaw(blob),
        url: URL.createObjectURL(blob),
        createdAt: Date.now(),
      }
      generatedImages.value.push(newImage)
      selectedImageId.value = newImage.id
    } catch (err) {
      if (activeGeneration !== requestGeneration) {
        return
      }

      if (err instanceof ApiError) {
        errorCode.value = err.code
        errorStatus.value = err.status
        errorRetryAfterSeconds.value = err.retryAfterSeconds
        errorFieldErrors.value = err.fieldErrors
        error.value = err.message
        return
      }

      error.value = err instanceof Error ? err.message : 'Unknown error occurred'
    } finally {
      if (activeGeneration === requestGeneration) {
        isGenerating.value = false
        if (abortController === controller) {
          abortController = null
        }
      }
    }
  }

  function selectImage(id: string) {
    selectedImageId.value = id
  }

  function reset() {
    requestGeneration += 1
    abortController?.abort()
    abortController = null
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
    clearError,
    generateImage,
    selectImage,
    reset,
  }
})
