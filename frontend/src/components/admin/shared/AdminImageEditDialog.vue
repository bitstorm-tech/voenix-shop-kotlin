<script setup lang="ts">
import { onBeforeUnmount, shallowRef, watch } from 'vue'
import { Eraser } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import ImageCropDialog from '@/components/shop/wizard/ImageCropDialog.vue'
import { removeImageBackground } from '@/lib/backgroundRemoval'

/**
 * The shop's crop and rotate dialog with the admin's "Remove background" on top. `source` is a
 * newly chosen file or the URL of a stored image; "Apply" emits the edited image as a WebP file.
 */
const props = defineProps<{ source: Blob | string | null }>()
const open = defineModel<boolean>('open', { required: true })
const emit = defineEmits<{ apply: [file: File] }>()

// The admin crops from the whole image; the shop's 80 % default would cut edges off unasked.
const WHOLE_IMAGE = { rx: 0, ry: 0, rw: 1, rh: 1, rotation: 0 }

const image = shallowRef<Blob | string | null>(null)
const imageSrc = shallowRef('')
const removing = shallowRef(false)
const removalError = shallowRef<string | null>(null)
let objectUrl: string | null = null
// A removal still running when the dialog is reopened must not swap the new image.
let showing = 0

function show(next: Blob | string | null) {
  if (objectUrl) URL.revokeObjectURL(objectUrl)
  objectUrl = next instanceof Blob ? URL.createObjectURL(next) : null
  image.value = next
  imageSrc.value = objectUrl ?? (typeof next === 'string' ? next : '')
}

watch(
  [open, () => props.source],
  ([isOpen, source]) => {
    if (!isOpen) return
    showing++
    removing.value = false
    removalError.value = null
    show(source)
  },
  { immediate: true },
)

onBeforeUnmount(() => show(null))

async function removeBackground() {
  const current = image.value
  if (current === null || removing.value) return
  const run = showing
  removing.value = true
  removalError.value = null
  try {
    const original = typeof current === 'string' ? await (await fetch(current)).blob() : current
    const cutOut = await removeImageBackground(original)
    if (run === showing) show(cutOut)
  } catch (error) {
    if (run === showing) {
      removalError.value =
        error instanceof Error ? error.message : 'The background could not be removed.'
    }
  } finally {
    if (run === showing) removing.value = false
  }
}

function onCrop(blob: Blob) {
  emit('apply', new File([blob], 'edited-image.webp', { type: 'image/webp' }))
}
</script>

<template>
  <!-- Mounted only while open: every edit starts fresh, and a closed dialog costs nothing. -->
  <ImageCropDialog
    v-if="open && imageSrc !== ''"
    :open="true"
    :image-src="imageSrc"
    mime-type="image/webp"
    :initial-crop-state="WHOLE_IMAGE"
    :apply-disabled="removing"
    @update:open="open = $event"
    @crop="onCrop"
  >
    <template #tools>
      <Button
        variant="outline"
        size="sm"
        :disabled="removing"
        data-testid="image-edit-remove-background"
        @click="removeBackground"
      >
        <Eraser class="h-3.5 w-3.5" />
        {{ removing ? 'Removing background…' : 'Remove background' }}
      </Button>
      <p
        v-if="removalError"
        class="w-full text-center text-sm text-destructive"
        data-testid="image-edit-error"
      >
        {{ removalError }}
      </p>
    </template>
  </ImageCropDialog>
</template>
