<script setup lang="ts">
import { computed, onBeforeUnmount, shallowRef } from 'vue'
import { useI18n } from 'vue-i18n'
import AdminPromptSlotVariantPicker from '@/components/admin/prompts/AdminPromptSlotVariantPicker.vue'
import FormField from '@/components/admin/shared/FormField.vue'
import { Button } from '@/components/ui/button'
import { CheckboxCard } from '@/components/ui/checkbox-card'
import { FileInput } from '@/components/ui/file-input'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import {
  PROMPT_TITLE_MAX_LENGTH,
  type AdminPromptFieldErrors,
  type AdminPromptFormState,
} from '@/composables/useAdminPromptEdit'
import { promptExampleImageUrl } from '@/lib/promptExampleImage'
import type {
  AdminPromptCategoryDto,
  AdminPromptSubcategoryDto,
} from '@/stores/admin/promptCategories'

interface Props {
  form: Readonly<AdminPromptFormState>
  fieldErrors: Readonly<AdminPromptFieldErrors>
  categories: readonly Readonly<AdminPromptCategoryDto>[]
  subcategories: readonly Readonly<AdminPromptSubcategoryDto>[]
  loadingReferences?: boolean
  disabled?: boolean
  uploadExampleImage: (file: File) => Promise<string>
}

const props = withDefaults(defineProps<Props>(), {
  loadingReferences: false,
  disabled: false,
})

const emit = defineEmits<{
  titleChange: [value: string]
  promptTextChange: [value: string]
  llmChange: [value: string]
  exampleImageFilenameChange: [value: string | null]
  categoryIdChange: [value: number | null]
  subcategoryIdChange: [value: number | null]
  activeChange: [value: boolean]
  archivedChange: [value: boolean]
  slotVariantIdsChange: [value: number[]]
  exampleImageSelection: []
  uploadingChange: [value: boolean]
}>()

const { t } = useI18n()
const NONE_VALUE = 'none'
const MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024
const ACCEPTED_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp']

const imageError = shallowRef<string | null>(null)
const imagePreviewFailed = shallowRef(false)
const isUploadingImage = shallowRef(false)
let uploadEpoch = 0

const filteredSubcategories = computed(() => {
  if (props.form.categoryId === null) {
    return []
  }

  return props.subcategories.filter(
    (subcategory) => subcategory.categoryId === props.form.categoryId,
  )
})

const categorySelectValue = computed({
  get: () => props.form.categoryId?.toString() ?? NONE_VALUE,
  set: (value: string) => emit('categoryIdChange', value === NONE_VALUE ? null : Number(value)),
})

const subcategorySelectValue = computed({
  get: () => props.form.subcategoryId?.toString() ?? NONE_VALUE,
  set: (value: string) => emit('subcategoryIdChange', value === NONE_VALUE ? null : Number(value)),
})

const slotVariantIdsModel = computed({
  get: () => [...props.form.slotVariantIds],
  set: (value: number[]) => emit('slotVariantIdsChange', value),
})

/**
 * A rejected example image is reported twice over: the pre-upload refuses the file itself, and a
 * write refuses a name that no longer names a stored image. Both belong under the upload controls.
 */
const exampleImageError = computed(
  () => imageError.value ?? props.fieldErrors.exampleImageFilename ?? null,
)

const exampleImagePreviewUrl = computed(() =>
  props.form.exampleImageFilename && !imagePreviewFailed.value
    ? promptExampleImageUrl(props.form.exampleImageFilename, 200)
    : null,
)

function setUploading(value: boolean) {
  isUploadingImage.value = value
  emit('uploadingChange', value)
}

async function onExampleImageSelected(files: File[]) {
  const file = files[0]
  if (!file) {
    return
  }

  imageError.value = null
  if (!ACCEPTED_IMAGE_TYPES.includes(file.type)) {
    imageError.value = t('admin.prompts.editor.image.typeError')
    return
  }
  if (file.size > MAX_IMAGE_SIZE_BYTES) {
    imageError.value = t('admin.prompts.editor.image.sizeError')
    return
  }

  emit('exampleImageSelection')
  const epoch = ++uploadEpoch
  setUploading(true)
  try {
    const filename = await props.uploadExampleImage(file)
    if (epoch === uploadEpoch) {
      imagePreviewFailed.value = false
      emit('exampleImageFilenameChange', filename)
    }
  } catch (error) {
    if (epoch === uploadEpoch) {
      imageError.value =
        error instanceof Error ? error.message : t('admin.prompts.editor.image.uploadError')
    }
  } finally {
    if (epoch === uploadEpoch) {
      setUploading(false)
    }
  }
}

function removeExampleImage() {
  uploadEpoch += 1
  setUploading(false)
  imageError.value = null
  imagePreviewFailed.value = false
  emit('exampleImageFilenameChange', null)
}

onBeforeUnmount(() => {
  uploadEpoch += 1
  if (isUploadingImage.value) {
    emit('uploadingChange', false)
  }
})
</script>

<template>
  <div class="min-w-0 space-y-6">
    <div class="grid min-w-0 grid-cols-[minmax(0,1fr)] gap-5">
      <FormField
        :label="t('admin.prompts.editor.fields.title')"
        for="prompt-title"
        :error="props.fieldErrors.title"
      >
        <Input
          id="prompt-title"
          class="min-w-0"
          :model-value="props.form.title"
          type="text"
          :maxlength="PROMPT_TITLE_MAX_LENGTH"
          :disabled="props.disabled"
          :placeholder="t('admin.prompts.editor.fields.titlePlaceholder')"
          @update:model-value="emit('titleChange', String($event))"
        />
      </FormField>

      <FormField :label="t('admin.prompts.editor.fields.llm')" for="prompt-llm">
        <Input
          id="prompt-llm"
          class="min-w-0"
          :model-value="props.form.llm"
          type="text"
          :disabled="props.disabled"
          :placeholder="t('admin.prompts.editor.fields.llmPlaceholder')"
          @update:model-value="emit('llmChange', String($event))"
        />
      </FormField>

      <div class="space-y-2 border-t border-border pt-5">
        <Label for="prompt-example-image">{{ t('admin.prompts.editor.image.label') }}</Label>
        <p class="text-sm text-muted-foreground">
          {{ t('admin.prompts.editor.image.help') }}
        </p>
        <div class="flex flex-col gap-4 sm:flex-row sm:items-center">
          <img
            v-if="exampleImagePreviewUrl"
            :src="exampleImagePreviewUrl"
            :alt="t('admin.prompts.editor.image.previewAlt')"
            class="size-20 shrink-0 rounded-lg border border-border bg-muted/20 object-contain"
            data-testid="prompt-example-image-preview"
            @error="imagePreviewFailed = true"
          />
          <div
            v-else
            class="flex size-20 shrink-0 items-center justify-center rounded-lg border border-dashed border-border text-xs text-muted-foreground"
          >
            {{ t('admin.prompts.editor.image.none') }}
          </div>
          <div class="flex flex-wrap items-center gap-2">
            <FileInput
              id="prompt-example-image"
              accept="image/png,image/jpeg,image/webp"
              button-test-id="prompt-example-image-upload"
              input-test-id="prompt-example-image-input"
              reset-on-select
              size="sm"
              variant="outline"
              :disabled="props.disabled || isUploadingImage"
              @change="onExampleImageSelected"
            >
              {{
                isUploadingImage
                  ? t('admin.prompts.editor.image.uploading')
                  : props.form.exampleImageFilename
                    ? t('admin.prompts.editor.image.replace')
                    : t('admin.prompts.editor.image.upload')
              }}
            </FileInput>
            <Button
              v-if="props.form.exampleImageFilename"
              type="button"
              variant="outline"
              size="sm"
              :disabled="props.disabled || isUploadingImage"
              data-testid="prompt-example-image-remove"
              @click="removeExampleImage"
            >
              {{ t('admin.prompts.editor.image.remove') }}
            </Button>
          </div>
        </div>
        <p v-if="exampleImageError" class="text-sm text-destructive">{{ exampleImageError }}</p>
      </div>

      <div class="grid min-w-0 grid-cols-[minmax(0,1fr)] gap-5 md:grid-cols-2">
        <FormField
          :label="t('admin.prompts.editor.fields.category')"
          for="prompt-category"
          :error="props.fieldErrors.categoryId"
        >
          <Select
            v-model="categorySelectValue"
            :disabled="props.disabled || props.loadingReferences || props.categories.length === 0"
          >
            <SelectTrigger
              id="prompt-category"
              class="min-w-0"
              :aria-invalid="props.fieldErrors.categoryId ? true : undefined"
            >
              <SelectValue :placeholder="t('admin.prompts.editor.fields.selectCategory')" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem :value="NONE_VALUE">
                {{ t('admin.prompts.editor.fields.selectCategory') }}
              </SelectItem>
              <SelectItem
                v-for="category in props.categories"
                :key="category.id"
                :value="category.id.toString()"
              >
                {{ category.name
                }}{{ category.active ? '' : ` (${t('admin.prompts.editor.inactive')})` }}
              </SelectItem>
            </SelectContent>
          </Select>
        </FormField>

        <FormField
          :label="t('admin.prompts.editor.fields.subcategory')"
          for="prompt-subcategory"
          :error="props.fieldErrors.subcategoryId"
        >
          <Select
            v-model="subcategorySelectValue"
            :disabled="
              props.disabled || props.form.categoryId === null || filteredSubcategories.length === 0
            "
          >
            <SelectTrigger
              id="prompt-subcategory"
              class="min-w-0"
              :aria-invalid="props.fieldErrors.subcategoryId ? true : undefined"
            >
              <SelectValue :placeholder="t('admin.prompts.editor.fields.noSubcategory')" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem :value="NONE_VALUE">
                {{ t('admin.prompts.editor.fields.noSubcategory') }}
              </SelectItem>
              <SelectItem
                v-for="subcategory in filteredSubcategories"
                :key="subcategory.id"
                :value="subcategory.id.toString()"
              >
                {{ subcategory.name
                }}{{ subcategory.active ? '' : ` (${t('admin.prompts.editor.inactive')})` }}
              </SelectItem>
            </SelectContent>
          </Select>
        </FormField>
      </div>

      <FormField
        :label="t('admin.prompts.editor.fields.promptText')"
        for="prompt-text"
        :error="props.fieldErrors.promptText"
      >
        <Textarea
          id="prompt-text"
          class="min-w-0"
          :model-value="props.form.promptText"
          rows="12"
          :disabled="props.disabled"
          :placeholder="t('admin.prompts.editor.fields.promptTextPlaceholder')"
          @update:model-value="emit('promptTextChange', String($event))"
        />
      </FormField>

      <div class="min-w-0 space-y-2">
        <Label>{{ t('admin.prompts.editor.slots.label') }}</Label>
        <p class="text-sm text-muted-foreground">
          {{ t('admin.prompts.editor.slots.help') }}
        </p>
        <AdminPromptSlotVariantPicker v-model="slotVariantIdsModel" :disabled="props.disabled" />
        <p v-if="props.fieldErrors.slotVariantIds" class="text-sm text-destructive">
          {{ props.fieldErrors.slotVariantIds }}
        </p>
      </div>
    </div>

    <div class="grid gap-4 md:grid-cols-2">
      <CheckboxCard
        id="prompt-active"
        :model-value="props.form.active"
        class="bg-muted/20"
        content-class="block space-y-1.5"
        :disabled="props.disabled"
        @update:model-value="emit('activeChange', $event)"
      >
        <span class="block font-medium text-foreground">
          {{ t('admin.prompts.editor.lifecycle.active') }}
        </span>
        <span class="block text-sm leading-6 text-muted-foreground">
          {{ t('admin.prompts.editor.lifecycle.activeHelp') }}
        </span>
      </CheckboxCard>

      <CheckboxCard
        id="prompt-archived"
        :model-value="props.form.archived"
        class="bg-muted/20"
        content-class="block space-y-1.5"
        :disabled="props.disabled"
        @update:model-value="emit('archivedChange', $event)"
      >
        <span class="block font-medium text-foreground">
          {{ t('admin.prompts.editor.lifecycle.archived') }}
        </span>
        <span class="block text-sm leading-6 text-muted-foreground">
          {{ t('admin.prompts.editor.lifecycle.archivedHelp') }}
        </span>
      </CheckboxCard>
    </div>
  </div>
</template>
