<script setup lang="ts">
import HennesBehrensImage from '@/assets/images/hennes-behrens.jpg'
import { Button } from '@/components/ui/button'
import { FileInput } from '@/components/ui/file-input'
import { useImageGenerationStore } from '@/stores/shop/imageGeneration'
import { ImagePlus, Loader2 } from 'lucide-vue-next'
import { onBeforeUnmount, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { useToast } from '@/composables/useToast'

const { t } = useI18n()
const imageGenerationStore = useImageGenerationStore()
const { toast } = useToast()

const selectedImage = shallowRef<File | null>(null)
const imagePreviewUrl = shallowRef<string | null>(null)

function revokePreviewUrl() {
  if (!imagePreviewUrl.value) return

  URL.revokeObjectURL(imagePreviewUrl.value)
  imagePreviewUrl.value = null
}

function setSelectedImage(file: File) {
  revokePreviewUrl()
  selectedImage.value = file
  imagePreviewUrl.value = URL.createObjectURL(file)
  imageGenerationStore.reset()
}

function handleFileSelect(files: File[]) {
  const file = files[0]
  if (!file) return

  setSelectedImage(file)
}

// TODO: FanView needs prompt selection — promptId 0 will result in a 404 from the backend
async function handleGenerate() {
  if (!selectedImage.value) {
    return
  }

  await imageGenerationStore.generateImage(selectedImage.value, 0)

  if (imageGenerationStore.error) {
    toast({ title: imageGenerationStore.error, variant: 'destructive' })
  }
}

onBeforeUnmount(() => {
  revokePreviewUrl()
})
</script>

<template>
  <div class="min-h-[50vh] px-4 py-8">
    <div class="w-full">
      <!-- Action buttons (when image uploaded) -->
      <div v-if="imagePreviewUrl" class="mb-4 flex justify-center gap-2">
        <FileInput
          accept="image/*"
          input-test-id="fan-upload-input"
          reset-on-select
          variant="outline"
          @change="handleFileSelect"
        >
          {{ t('fanConfigurator.chooseAnother') }}
        </FileInput>
        <Button :disabled="imageGenerationStore.isGenerating" @click="handleGenerate">
          <Loader2 v-if="imageGenerationStore.isGenerating" class="mr-2 size-4 animate-spin" />
          {{
            imageGenerationStore.isGenerating
              ? t('fanConfigurator.generating')
              : t('fanConfigurator.generate')
          }}
        </Button>
      </div>
      <!-- Images row (always visible) -->
      <div class="flex flex-wrap items-end justify-center gap-4">
        <!-- Hennes Behrens reference image (always visible) -->
        <div class="relative w-full max-w-sm shrink-0 sm:w-auto sm:max-w-none">
          <img
            :src="HennesBehrensImage"
            :alt="t('fanConfigurator.referenceAlt')"
            class="h-auto max-h-[32rem] w-full rounded-lg object-contain sm:h-128 sm:max-h-none sm:w-auto"
          />
          <div class="absolute bottom-0 left-0 right-0 rounded-b-lg bg-black/60 px-3 py-2">
            <p class="text-sm font-medium text-white">{{ t('fanConfigurator.referenceLabel') }}</p>
          </div>
        </div>

        <!-- Upload area or uploaded image -->
        <div class="relative w-full max-w-sm shrink-0 sm:w-auto sm:max-w-none">
          <!-- Upload area (when no image) -->
          <FileInput
            v-if="!imagePreviewUrl"
            accept="image/*"
            input-test-id="fan-upload-input"
            reset-on-select
            variant="ghost"
            class="flex h-128 w-64 cursor-pointer flex-col items-center justify-center gap-4 whitespace-normal rounded-lg border-2 border-dashed border-muted-foreground/25 px-4 transition-colors hover:border-muted-foreground/50"
            @change="handleFileSelect"
          >
            <ImagePlus class="size-12 text-muted-foreground/50" />
            <p class="text-center text-sm text-muted-foreground">
              {{ t('fanConfigurator.uploadPrompt') }}
            </p>
          </FileInput>

          <!-- Uploaded image -->
          <template v-else>
            <img
              :src="imagePreviewUrl"
              :alt="t('fanConfigurator.previewAlt')"
              class="h-auto max-h-[32rem] w-full rounded-lg object-contain sm:h-128 sm:max-h-none sm:w-auto"
            />
            <div class="absolute bottom-0 left-0 right-0 rounded-b-lg bg-black/60 px-3 py-2">
              <p class="text-sm font-medium text-white">{{ t('fanConfigurator.uploadedLabel') }}</p>
            </div>
          </template>
        </div>

        <!-- Generated image (when available) -->
        <div
          v-if="imageGenerationStore.selectedImageUrl"
          class="relative w-full max-w-sm shrink-0 sm:w-auto sm:max-w-none"
        >
          <img
            :src="imageGenerationStore.selectedImageUrl"
            :alt="t('fanConfigurator.generatedAlt')"
            class="h-auto max-h-[32rem] w-full rounded-lg object-contain sm:h-128 sm:max-h-none sm:w-auto"
          />
          <div class="absolute bottom-0 left-0 right-0 rounded-b-lg bg-black/60 px-3 py-2">
            <p class="text-sm font-medium text-white">{{ t('fanConfigurator.generatedLabel') }}</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
