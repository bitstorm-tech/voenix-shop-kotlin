<script setup lang="ts">
import { shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { ImagePlus, Upload } from 'lucide-vue-next'
import ImageUploadDropzone from '@/components/shop/ImageUploadDropzone.vue'

const emit = defineEmits<{
  upload: [file: File]
}>()

const { t } = useI18n()

const ALLOWED_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif']
const MAX_SIZE_BYTES = 10 * 1024 * 1024

const validationError = shallowRef<'fileType' | 'fileSize' | null>(null)

function validateFile(file: File) {
  if (!ALLOWED_TYPES.includes(file.type)) {
    validationError.value = 'fileType'
    return false
  }

  if (file.size > MAX_SIZE_BYTES) {
    validationError.value = 'fileSize'
    return false
  }

  validationError.value = null
  return true
}

function handleUpload(file: File) {
  if (!validateFile(file)) return

  emit('upload', file)
}
</script>

<template>
  <div class="grid gap-3">
    <ImageUploadDropzone
      :title="t('editor.upload.title')"
      :body="t('editor.upload.body')"
      :action-label="t('editor.upload.action')"
      layout="inline"
      test-id="editor-draft-upload"
      input-test-id="editor-upload-input"
      @upload="handleUpload"
    >
      <template #icon>
        <ImagePlus class="size-7" />
      </template>

      <template #action-icon>
        <Upload class="size-4" />
      </template>
    </ImageUploadDropzone>

    <p v-if="validationError" class="m-0 text-[0.9rem] text-destructive">
      {{
        validationError === 'fileType'
          ? t('editor.upload.errorFileType')
          : t('editor.upload.errorFileSize')
      }}
    </p>
  </div>
</template>
