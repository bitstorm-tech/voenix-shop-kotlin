<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, shallowRef, watch } from 'vue'
import { ImagePlus, Trash2 } from 'lucide-vue-next'
import ConfirmDeleteDialog from '@/components/admin/shared/ConfirmDeleteDialog.vue'
import FormField from '@/components/admin/shared/FormField.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { CheckboxCard } from '@/components/ui/checkbox-card'
import {
  Dialog,
  DialogFooter,
  DialogHeader,
  DialogContent,
  DialogTitle,
} from '@/components/ui/dialog'
import { FileInput } from '@/components/ui/file-input'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { useDialogForm } from '@/composables/useDialogForm'
import { useFormErrors } from '@/composables/useFormErrors'
import { optionalText } from '@/lib/forms'
import type { AdminArticleCategoryDto } from '@/stores/admin/articleCategories'
import type {
  AdminArticleSubcategoryDto,
  CreateAdminArticleSubcategoryRequest,
  UpdateAdminArticleSubcategoryRequest,
} from '@/stores/admin/articleSubcategories'

interface Props {
  subcategory: AdminArticleSubcategoryDto | null
  categories: AdminArticleCategoryDto[]
  initialCategoryId?: number | null
  saving?: boolean
  deleting?: boolean
  categoryError?: string | null
  nameError?: string | null
  generalError?: string | null
}

const props = withDefaults(defineProps<Props>(), {
  initialCategoryId: null,
  saving: false,
  deleting: false,
  categoryError: null,
  nameError: null,
  generalError: null,
})

const open = defineModel<boolean>('open', { required: true })

const emit = defineEmits<{
  (
    event: 'save',
    payload: CreateAdminArticleSubcategoryRequest | UpdateAdminArticleSubcategoryRequest,
  ): void
  (event: 'delete'): void
  (event: 'clearErrors'): void
}>()

const MAX_NAME_LENGTH = 200
const MAX_DESCRIPTION_LENGTH = 1000
const MAX_EXAMPLE_IMAGE_SIZE_BYTES = 10 * 1024 * 1024
const ACCEPTED_EXAMPLE_IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp'])
const ACCEPTED_EXAMPLE_IMAGE_EXTENSIONS = ['.jpg', '.jpeg', '.png', '.webp']
const NONE_CATEGORY_VALUE = 'none'

interface FormState {
  articleCategoryId: number | null
  name: string
  description: string
  active: boolean
}

const form = reactive<FormState>({
  articleCategoryId: null,
  name: '',
  description: '',
  active: true,
})
const { fieldErrors, clearFieldErrors } = useFormErrors<
  'articleCategoryId' | 'name' | 'description' | 'exampleImage'
>()
const selectedExampleImage = shallowRef<File | null>(null)
const selectedExampleImagePreviewUrl = shallowRef<string | null>(null)
const removeExampleImage = shallowRef(false)

const isEditMode = computed(() => props.subcategory !== null)
const title = computed(() =>
  isEditMode.value ? 'Edit Article Subcategory' : 'New Article Subcategory',
)
const categoryErrorMessage = computed(() => fieldErrors.articleCategoryId ?? props.categoryError)
const nameErrorMessage = computed(() => fieldErrors.name ?? props.nameError)
const currentExampleImageUrl = computed(() => {
  const filename = props.subcategory?.exampleImageFilename
  return filename ? `/api/images/public/400/articles/subcategory-example-images/${filename}` : null
})
const exampleImagePreviewUrl = computed(() => {
  if (selectedExampleImagePreviewUrl.value) {
    return selectedExampleImagePreviewUrl.value
  }

  return removeExampleImage.value ? null : currentExampleImageUrl.value
})
const exampleImagePreviewLabel = computed(
  () => selectedExampleImage.value?.name ?? `Example image for ${form.name || 'subcategory'}`,
)
const exampleImageStatusLabel = computed(() => {
  if (selectedExampleImage.value) {
    return selectedExampleImage.value.name
  }

  if (removeExampleImage.value) {
    return 'Image will be removed on save.'
  }

  return currentExampleImageUrl.value ? 'Current image' : 'No example image'
})
const imageUploadLabel = computed(() =>
  exampleImagePreviewUrl.value ? 'Replace Image' : 'Upload Image',
)

const categorySelectValue = computed({
  get: () => form.articleCategoryId?.toString() ?? NONE_CATEGORY_VALUE,
  set: (value: string) => {
    form.articleCategoryId = value === NONE_CATEGORY_VALUE ? null : Number(value)
    fieldErrors.articleCategoryId = undefined
    emit('clearErrors')
  },
})

function hasCategory(categoryId: number | null | undefined) {
  return (
    typeof categoryId === 'number' &&
    props.categories.some((category) => category.id === categoryId)
  )
}

function resetForm() {
  const initialCategoryId = props.subcategory?.articleCategory.id ?? props.initialCategoryId ?? null
  form.articleCategoryId = hasCategory(initialCategoryId) ? initialCategoryId : null
  form.name = props.subcategory?.name ?? ''
  form.description = props.subcategory?.description ?? ''
  form.active = props.subcategory?.active ?? true
  clearSelectedExampleImage()
  removeExampleImage.value = false
  clearFieldErrors()
}

const { isDeleteDialogOpen } = useDialogForm({
  open,
  resetKeys: () => [props.subcategory?.id, props.initialCategoryId],
  resetForm,
})

function updateName(value: string | number) {
  form.name = String(value)
  fieldErrors.name = undefined
  emit('clearErrors')
}

function updateDescription(value: string | number) {
  form.description = String(value)
  fieldErrors.description = undefined
  emit('clearErrors')
}

function clearMetadataErrors() {
  fieldErrors.articleCategoryId = undefined
  fieldErrors.name = undefined
  fieldErrors.description = undefined
}

function clearSelectedExampleImage() {
  if (selectedExampleImagePreviewUrl.value) {
    URL.revokeObjectURL(selectedExampleImagePreviewUrl.value)
  }

  selectedExampleImage.value = null
  selectedExampleImagePreviewUrl.value = null
}

function hasAcceptedExampleImageExtension(file: File) {
  const lowerName = file.name.toLowerCase()
  return ACCEPTED_EXAMPLE_IMAGE_EXTENSIONS.some((extension) => lowerName.endsWith(extension))
}

function validateExampleImage(file: File) {
  if (!ACCEPTED_EXAMPLE_IMAGE_TYPES.has(file.type) || !hasAcceptedExampleImageExtension(file)) {
    return 'Choose a JPG, PNG, or WebP image.'
  }

  if (file.size > MAX_EXAMPLE_IMAGE_SIZE_BYTES) {
    return 'Image must be at most 10 MB.'
  }

  return null
}

function onExampleImageChange(files: File[]) {
  const file = files[0] ?? null

  if (file === null) {
    return
  }

  const validationError = validateExampleImage(file)
  if (validationError) {
    clearSelectedExampleImage()
    fieldErrors.exampleImage = validationError
    return
  }

  clearSelectedExampleImage()
  fieldErrors.exampleImage = undefined
  selectedExampleImage.value = file
  selectedExampleImagePreviewUrl.value = URL.createObjectURL(file)
  removeExampleImage.value = false
  emit('clearErrors')
}

function removeImage() {
  clearSelectedExampleImage()
  fieldErrors.exampleImage = undefined
  removeExampleImage.value = currentExampleImageUrl.value !== null
  emit('clearErrors')
}

function validate() {
  clearMetadataErrors()

  let ok = true
  const trimmedName = form.name.trim()
  const trimmedDescription = form.description.trim()

  if (form.articleCategoryId === null) {
    fieldErrors.articleCategoryId = 'Category is required.'
    ok = false
  }

  if (trimmedName === '') {
    fieldErrors.name = 'Name is required.'
    ok = false
  } else if (trimmedName.length > MAX_NAME_LENGTH) {
    fieldErrors.name = `Name must be at most ${MAX_NAME_LENGTH} characters.`
    ok = false
  }

  if (trimmedDescription.length > MAX_DESCRIPTION_LENGTH) {
    fieldErrors.description = `Description must be at most ${MAX_DESCRIPTION_LENGTH} characters.`
    ok = false
  }

  if (fieldErrors.exampleImage) {
    ok = false
  }

  return ok
}

function saveSubcategory() {
  if (props.saving || props.deleting || !validate() || form.articleCategoryId === null) {
    return
  }

  emit('save', {
    articleCategoryId: form.articleCategoryId,
    name: form.name.trim(),
    description: optionalText(form.description),
    active: form.active,
    ...(selectedExampleImage.value ? { exampleImage: selectedExampleImage.value } : {}),
    ...(removeExampleImage.value ? { removeExampleImage: true } : {}),
  })
}

function deleteSubcategory() {
  if (props.saving || props.deleting) {
    return
  }

  emit('delete')
}

watch(open, (isOpen) => {
  if (isOpen) {
    return
  }

  clearSelectedExampleImage()
  removeExampleImage.value = false
  fieldErrors.exampleImage = undefined
})

onBeforeUnmount(() => {
  clearSelectedExampleImage()
})
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent class="w-[calc(100%-2rem)] max-w-xl rounded-xl">
      <DialogHeader>
        <DialogTitle>{{ title }}</DialogTitle>
      </DialogHeader>

      <form class="space-y-5" @submit.prevent="saveSubcategory">
        <Alert v-if="generalError" variant="destructive">
          {{ generalError }}
        </Alert>

        <div class="grid gap-5">
          <FormField
            label="Category"
            for="article-subcategory-category"
            :error="categoryErrorMessage"
          >
            <Select v-model="categorySelectValue" :disabled="saving || deleting">
              <SelectTrigger
                id="article-subcategory-category"
                :aria-invalid="categoryErrorMessage ? true : undefined"
              >
                <SelectValue placeholder="Select category" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem :value="NONE_CATEGORY_VALUE">Select category</SelectItem>
                <SelectItem
                  v-for="category in categories"
                  :key="category.id"
                  :value="category.id.toString()"
                >
                  {{ category.name }}{{ category.active ? '' : ' (Inactive)' }}
                </SelectItem>
              </SelectContent>
            </Select>
          </FormField>

          <FormField label="Name" for="article-subcategory-name" :error="nameErrorMessage">
            <Input
              id="article-subcategory-name"
              :model-value="form.name"
              type="text"
              placeholder="e.g. Espresso"
              :maxlength="MAX_NAME_LENGTH"
              :aria-invalid="nameErrorMessage ? true : undefined"
              @update:model-value="updateName"
            />
          </FormField>

          <FormField
            label="Description"
            for="article-subcategory-description"
            :error="fieldErrors.description"
            hint="Optional admin context for this subcategory."
          >
            <Textarea
              id="article-subcategory-description"
              :model-value="form.description"
              rows="4"
              placeholder="Optional description"
              :maxlength="MAX_DESCRIPTION_LENGTH"
              :aria-invalid="fieldErrors.description ? true : undefined"
              @update:model-value="updateDescription"
            />
          </FormField>
        </div>

        <CheckboxCard id="article-subcategory-active" v-model="form.active" class="bg-muted/20">
          <span class="block font-medium text-foreground">Active</span>
          <span class="mt-1 block text-sm leading-6 text-muted-foreground">
            Active subcategories and their eligible articles are available in the storefront.
          </span>
        </CheckboxCard>

        <FormField
          label="Example image"
          for="article-subcategory-example-image"
          :error="fieldErrors.exampleImage"
          hint="Optional preview shown in the storefront super menu. JPG, PNG, or WebP, max 10 MB."
        >
          <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div class="flex min-w-0 items-center gap-3">
              <div
                class="grid size-20 shrink-0 place-items-center overflow-hidden rounded-lg border border-border bg-muted/40"
                data-testid="subcategory-example-image-preview"
              >
                <img
                  v-if="exampleImagePreviewUrl"
                  class="h-full w-full object-cover"
                  :src="exampleImagePreviewUrl"
                  :alt="exampleImagePreviewLabel"
                />
                <ImagePlus v-else class="size-6 text-muted-foreground" aria-hidden="true" />
              </div>
              <p class="min-w-0 truncate text-sm font-medium text-foreground">
                {{ exampleImageStatusLabel }}
              </p>
            </div>

            <div class="flex flex-wrap items-center gap-2">
              <FileInput
                id="article-subcategory-example-image"
                accept=".jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp"
                button-test-id="subcategory-example-image-upload"
                input-test-id="subcategory-example-image-input"
                reset-on-select
                size="sm"
                variant="outline"
                :disabled="saving || deleting"
                @change="onExampleImageChange"
              >
                <ImagePlus class="size-4" />
                {{ imageUploadLabel }}
              </FileInput>
              <Button
                v-if="selectedExampleImage || currentExampleImageUrl"
                type="button"
                variant="outline"
                size="sm"
                :disabled="saving || deleting"
                data-testid="subcategory-example-image-remove"
                @click="removeImage"
              >
                <Trash2 class="size-4" />
                Remove
              </Button>
            </div>
          </div>
        </FormField>

        <DialogFooter class="gap-2 border-t border-border pt-5">
          <template v-if="isEditMode">
            <Button
              type="button"
              variant="destructive"
              class="sm:mr-auto"
              :disabled="saving || deleting"
              @click="isDeleteDialogOpen = true"
            >
              <Trash2 class="size-4" />
              Delete Subcategory
            </Button>
            <ConfirmDeleteDialog
              v-model:open="isDeleteDialogOpen"
              title="Delete article subcategory?"
              :description="`This permanently deletes ${form.name || 'this article subcategory'}. This action cannot be undone.`"
              confirm-label="Delete Subcategory"
              :deleting="deleting"
              confirm-test-id="confirm-delete-article-subcategory"
              @confirm="deleteSubcategory"
            />
          </template>

          <Button
            type="button"
            variant="outline"
            :disabled="saving || deleting"
            @click="open = false"
          >
            Cancel
          </Button>
          <Button type="submit" :disabled="saving || deleting">
            {{ saving ? 'Saving...' : 'Save Subcategory' }}
          </Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
