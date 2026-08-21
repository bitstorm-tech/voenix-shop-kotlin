<script setup lang="ts">
import { computed, reactive, shallowRef, watch } from 'vue'
import FormField from '@/components/admin/shared/FormField.vue'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { ColorInput } from '@/components/ui/color-input'
import {
  Dialog,
  DialogFooter,
  DialogHeader,
  DialogContent,
  DialogTitle,
} from '@/components/ui/dialog'
import { FileInput } from '@/components/ui/file-input'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { variantExampleImageUrl } from '@/lib/variantExampleImage'
import { InvalidArticleRequestError, useAdminArticlesStore } from '@/stores/admin/articles'
import type { MugVariantFormValue } from './mugVariantForm'

interface Props {
  variant: MugVariantFormValue | null
  isOnlyVariant?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  isOnlyVariant: false,
})

const open = defineModel<boolean>('open', { required: true })

const emit = defineEmits<{
  (event: 'save', payload: MugVariantFormValue): void
}>()

const MAX_NAME_LENGTH = 255
const MAX_COLOR_LENGTH = 255
const MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024
const ACCEPTED_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp']

interface FieldErrors {
  name?: string
  insideColorCode?: string
  outsideColorCode?: string
}

const articlesStore = useAdminArticlesStore()

const form = reactive<MugVariantFormValue>({
  name: '',
  insideColorCode: '#ffffff',
  outsideColorCode: '#ffffff',
  isDefault: false,
  active: true,
  exampleImageFilename: null,
})
const fieldErrors = reactive<FieldErrors>({})
const imageError = shallowRef<string | null>(null)
const isUploadingImage = shallowRef(false)

// Invalidates in-flight uploads when the form is reset, so a slow response
// cannot land in a dialog that has since been reopened for another variant.
let uploadEpoch = 0

const exampleImagePreviewUrl = computed(() =>
  form.exampleImageFilename ? variantExampleImageUrl('MUG', form.exampleImageFilename, 200) : null,
)

const isEditMode = computed(() => props.variant !== null)
const title = computed(() => (isEditMode.value ? 'Edit Variant' : 'New Variant'))
const defaultLocked = computed(() => props.isOnlyVariant || (props.variant?.isDefault ?? false))

function isValidColor(value: string) {
  return /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(value)
}

function resetForm() {
  uploadEpoch += 1
  form.name = props.variant?.name ?? ''
  form.insideColorCode = props.variant?.insideColorCode ?? '#ffffff'
  form.outsideColorCode = props.variant?.outsideColorCode ?? '#ffffff'
  form.isDefault = props.variant?.isDefault ?? props.isOnlyVariant
  form.active = props.variant?.active ?? true
  form.exampleImageFilename = props.variant?.exampleImageFilename ?? null
  fieldErrors.name = undefined
  fieldErrors.insideColorCode = undefined
  fieldErrors.outsideColorCode = undefined
  imageError.value = null
  isUploadingImage.value = false
}

async function onExampleImageSelected(files: File[]) {
  const file = files[0]

  if (!file) {
    return
  }

  imageError.value = null

  if (!ACCEPTED_IMAGE_TYPES.includes(file.type)) {
    imageError.value = 'Example image must be a PNG, JPEG, or WebP file.'
    return
  }

  if (file.size > MAX_IMAGE_SIZE_BYTES) {
    imageError.value = 'Example image must be at most 10 MB.'
    return
  }

  const epoch = uploadEpoch
  isUploadingImage.value = true
  try {
    const filename = await articlesStore.uploadVariantExampleImage(file)
    if (epoch === uploadEpoch) {
      form.exampleImageFilename = filename
    }
  } catch (error) {
    if (epoch === uploadEpoch) {
      // A rejected pre-upload is a `400` whose message sits on the `file` field of the request.
      imageError.value =
        error instanceof InvalidArticleRequestError
          ? (error.fieldError('file') ?? error.message)
          : error instanceof Error
            ? error.message
            : 'Failed to upload the example image.'
    }
  } finally {
    if (epoch === uploadEpoch) {
      isUploadingImage.value = false
    }
  }
}

function removeExampleImage() {
  form.exampleImageFilename = null
  imageError.value = null
}

function validate() {
  fieldErrors.name = undefined
  fieldErrors.insideColorCode = undefined
  fieldErrors.outsideColorCode = undefined

  let ok = true

  if (form.name.trim() === '') {
    fieldErrors.name = 'Name is required.'
    ok = false
  } else if (form.name.trim().length > MAX_NAME_LENGTH) {
    fieldErrors.name = `Name must be at most ${MAX_NAME_LENGTH} characters.`
    ok = false
  }

  if (form.insideColorCode.trim() === '') {
    fieldErrors.insideColorCode = 'Inside color is required.'
    ok = false
  } else if (form.insideColorCode.trim().length > MAX_COLOR_LENGTH) {
    fieldErrors.insideColorCode = `Inside color must be at most ${MAX_COLOR_LENGTH} characters.`
    ok = false
  }

  if (form.outsideColorCode.trim() === '') {
    fieldErrors.outsideColorCode = 'Outside color is required.'
    ok = false
  } else if (form.outsideColorCode.trim().length > MAX_COLOR_LENGTH) {
    fieldErrors.outsideColorCode = `Outside color must be at most ${MAX_COLOR_LENGTH} characters.`
    ok = false
  }

  return ok
}

function saveVariant() {
  if (!validate()) {
    return
  }

  emit('save', {
    name: form.name.trim(),
    insideColorCode: form.insideColorCode.trim(),
    outsideColorCode: form.outsideColorCode.trim(),
    isDefault: defaultLocked.value ? true : form.isDefault,
    active: form.active,
    exampleImageFilename: form.exampleImageFilename,
  })
  open.value = false
}

watch(
  () => open.value,
  (isOpen) => {
    if (isOpen) {
      resetForm()
    }
  },
  { immediate: true },
)
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent class="w-[calc(100%-2rem)] max-w-xl rounded-xl">
      <DialogHeader>
        <DialogTitle>{{ title }}</DialogTitle>
      </DialogHeader>

      <form class="space-y-5" @submit.prevent="saveVariant">
        <FormField label="Name" for="mug-variant-name" :error="fieldErrors.name">
          <Input
            id="mug-variant-name"
            v-model="form.name"
            type="text"
            placeholder="e.g. White / Black"
            :maxlength="MAX_NAME_LENGTH"
            :aria-invalid="fieldErrors.name ? true : undefined"
          />
        </FormField>

        <div class="grid gap-5 md:grid-cols-2">
          <FormField
            label="Inside color"
            for="mug-variant-inside-color"
            :error="fieldErrors.insideColorCode"
          >
            <div class="flex items-center gap-2">
              <ColorInput
                v-if="isValidColor(form.insideColorCode.trim())"
                v-model="form.insideColorCode"
                label="Pick inside color"
                input-class="size-9 w-9 shrink-0 cursor-pointer border-border bg-background"
              />
              <Input
                id="mug-variant-inside-color"
                v-model="form.insideColorCode"
                type="text"
                placeholder="#ffffff"
                :maxlength="MAX_COLOR_LENGTH"
                :aria-invalid="fieldErrors.insideColorCode ? true : undefined"
              />
            </div>
          </FormField>

          <FormField
            label="Outside color"
            for="mug-variant-outside-color"
            :error="fieldErrors.outsideColorCode"
          >
            <div class="flex items-center gap-2">
              <ColorInput
                v-if="isValidColor(form.outsideColorCode.trim())"
                v-model="form.outsideColorCode"
                label="Pick outside color"
                input-class="size-9 w-9 shrink-0 cursor-pointer border-border bg-background"
              />
              <Input
                id="mug-variant-outside-color"
                v-model="form.outsideColorCode"
                type="text"
                placeholder="#ffffff"
                :maxlength="MAX_COLOR_LENGTH"
                :aria-invalid="fieldErrors.outsideColorCode ? true : undefined"
              />
            </div>
          </FormField>
        </div>

        <div class="space-y-2 border-t border-border pt-5">
          <Label for="mug-variant-example-image">Example image</Label>
          <p class="text-sm text-muted-foreground">
            Product photo of this variant shown in the shop. PNG, JPEG, or WebP, max 10 MB.
          </p>
          <div class="flex items-center gap-4">
            <img
              v-if="exampleImagePreviewUrl"
              :src="exampleImagePreviewUrl"
              alt="Example image preview"
              class="size-20 shrink-0 rounded-lg border border-border bg-muted/20 object-contain"
              data-testid="variant-example-image-preview"
            />
            <div
              v-else
              class="flex size-20 shrink-0 items-center justify-center rounded-lg border border-dashed border-border text-xs text-muted-foreground"
            >
              No image
            </div>
            <div class="flex flex-wrap items-center gap-2">
              <FileInput
                id="mug-variant-example-image"
                accept="image/png,image/jpeg,image/webp"
                button-test-id="variant-example-image-upload"
                input-test-id="variant-example-image-input"
                reset-on-select
                size="sm"
                variant="outline"
                :disabled="isUploadingImage"
                @change="onExampleImageSelected"
              >
                {{
                  isUploadingImage
                    ? 'Uploading...'
                    : form.exampleImageFilename
                      ? 'Replace Image'
                      : 'Upload Image'
                }}
              </FileInput>
              <Button
                v-if="form.exampleImageFilename"
                type="button"
                variant="outline"
                size="sm"
                :disabled="isUploadingImage"
                data-testid="variant-example-image-remove"
                @click="removeExampleImage"
              >
                Remove
              </Button>
            </div>
          </div>
          <p v-if="imageError" class="text-sm text-destructive">{{ imageError }}</p>
        </div>

        <div class="grid gap-4 md:grid-cols-2">
          <div class="flex items-center gap-3">
            <Checkbox id="mug-variant-default" v-model="form.isDefault" :disabled="defaultLocked" />
            <div>
              <Label for="mug-variant-default">Default variant</Label>
              <p class="text-sm text-muted-foreground">
                {{
                  defaultLocked
                    ? 'This variant is the default. Mark another variant as default to change it.'
                    : 'Pre-selected variant in the shop.'
                }}
              </p>
            </div>
          </div>
          <div class="flex items-center gap-3">
            <Checkbox id="mug-variant-active" v-model="form.active" />
            <div>
              <Label for="mug-variant-active">Active</Label>
              <p class="text-sm text-muted-foreground">Visible and selectable in the shop.</p>
            </div>
          </div>
        </div>

        <DialogFooter class="gap-2 border-t border-border pt-5">
          <Button type="button" variant="outline" @click="open = false">Cancel</Button>
          <Button type="submit" :disabled="isUploadingImage">Save Variant</Button>
        </DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
