<script setup lang="ts">
import { shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { Upload, Trash2, RefreshCw, Crop } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { FileInput } from '@/components/ui/file-input'
import { useWizardStore, type CropState } from '@/stores/shop/wizard'
import ImageUploadDropzone from '@/components/shop/ImageUploadDropzone.vue'
import ImageCropDialog from '@/components/shop/wizard/ImageCropDialog.vue'

const acceptedImageTypes = 'image/jpeg,image/png,image/webp,image/gif'
const { t } = useI18n()
const wizard = useWizardStore()

const cropDialogOpen = shallowRef(false)

function handleUpload(file: File) {
  if (wizard.setImage(file)) {
    cropDialogOpen.value = true
  }
}

function onCropApplied(blob: Blob, cropState: CropState) {
  wizard.setCroppedImage(blob, cropState)
}

function onFileChange(files: File[]) {
  const file = files[0]

  if (file) {
    handleUpload(file)
  }
}
</script>

<template>
  <div class="wizard-step-enter pb-2">
    <h2 class="sr-only">{{ t('mugConfigurator.steps.uploadImage.title') }}</h2>

    <!-- State A: Empty dropzone -->
    <div v-if="!wizard.hasUploadedImage" class="mt-6 sm:mt-8">
      <ImageUploadDropzone
        :title="t('mugConfigurator.steps.uploadImage.dropzoneTitle')"
        :accept="acceptedImageTypes"
        layout="stacked"
        tone="adaptive"
        @upload="handleUpload"
      >
        <template #icon>
          <Upload class="h-6 w-6 text-white sm:h-7 sm:w-7" />
        </template>

        <template #hint>
          {{ t('mugConfigurator.steps.uploadImage.dropzoneHint') }}
          <span
            class="mx-1.5 inline-block size-1 rounded-full bg-[oklch(0.75_0.06_45)] align-middle dark:bg-[oklch(0.7_0.12_45)]"
          />
          {{ t('mugConfigurator.steps.uploadImage.constraints') }}
        </template>
      </ImageUploadDropzone>

      <!-- Validation error -->
      <p v-if="wizard.validationError" class="mt-3 text-sm text-destructive">
        {{
          wizard.validationError.type === 'fileType'
            ? t('mugConfigurator.steps.uploadImage.errorFileType')
            : t('mugConfigurator.steps.uploadImage.errorFileSize')
        }}
      </p>
    </div>

    <!-- State B: Image preview -->
    <div v-else class="mt-6 sm:mt-8">
      <div
        class="overflow-hidden rounded-xl border border-border bg-card shadow-[0_1px_3px_oklch(0_0_0_/_0.06),0_4px_12px_oklch(0_0_0_/_0.04)] motion-safe:animate-wizard-step-enter motion-reduce:animate-none dark:shadow-[inset_0_1px_0_oklch(1_0_0_/_0.04),0_14px_40px_oklch(0_0_0_/_0.24)]"
      >
        <div class="flex items-center justify-center bg-surface-image p-5 sm:p-8">
          <img
            :src="wizard.effectivePreviewUrl!"
            :alt="t('mugConfigurator.steps.uploadImage.previewAlt')"
            class="max-h-60 rounded-lg object-contain sm:max-h-80"
          />
        </div>

        <div
          class="flex items-center justify-center gap-2 border-t border-[oklch(0.92_0.02_50_/_0.4)] p-4 dark:border-[oklch(1_0_0_/_0.08)] sm:px-5 sm:py-4"
        >
          <div class="flex flex-wrap justify-center gap-2">
            <Button variant="outline" size="sm" @click="cropDialogOpen = true">
              <Crop class="h-3.5 w-3.5" />
              {{ t('mugConfigurator.steps.uploadImage.editCrop') }}
            </Button>
            <FileInput
              :accept="acceptedImageTypes"
              input-test-id="wizard-replacement-image-input"
              reset-on-select
              variant="outline"
              size="sm"
              @change="onFileChange"
            >
              <RefreshCw class="h-3.5 w-3.5" />
              {{ t('mugConfigurator.steps.uploadImage.change') }}
            </FileInput>
            <Button variant="destructive" size="sm" @click="wizard.removeImage()">
              <Trash2 class="h-3.5 w-3.5" />
              {{ t('mugConfigurator.steps.uploadImage.remove') }}
            </Button>
          </div>
        </div>
      </div>

      <ImageCropDialog
        :open="cropDialogOpen"
        :image-src="wizard.previewUrl!"
        :mime-type="wizard.uploadedFile?.type ?? 'image/png'"
        :initial-crop-state="wizard.cropState"
        @update:open="cropDialogOpen = $event"
        @crop="onCropApplied"
      />
    </div>
  </div>
</template>
