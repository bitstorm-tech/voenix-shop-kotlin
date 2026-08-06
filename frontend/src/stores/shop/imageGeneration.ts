import { ref, computed, markRaw, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchForm } from '@/lib/api'
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

  const selectedImageUrl = computed(
    () => generatedImages.value.find((img) => img.id === selectedImageId.value)?.url ?? null,
  )

  const hasImages = computed(() => generatedImages.value.length > 0)
  const imageCount = computed(() => generatedImages.value.length)

  async function generateImage(image: File | Blob, promptId: number) {
    isGenerating.value = true
    error.value = null
    errorCode.value = null

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
        errorCode.value = typeof err.details?.code === 'string' ? err.details.code : null
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
    error.value = null
    errorCode.value = null
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
    generateImage,
    selectImage,
    reset,
  }
})
