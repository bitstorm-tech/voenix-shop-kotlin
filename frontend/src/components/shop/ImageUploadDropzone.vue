<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import { Upload } from 'lucide-vue-next'
import { FileInput } from '@/components/ui/file-input'

type ImageUploadDropzoneLayout = 'stacked' | 'inline'
type ImageUploadDropzoneTone = 'adaptive' | 'light'

interface Props {
  title: string
  body?: string
  hint?: string
  actionLabel?: string
  accept?: string
  testId?: string
  inputTestId?: string
  layout?: ImageUploadDropzoneLayout
  tone?: ImageUploadDropzoneTone
}

const props = withDefaults(defineProps<Props>(), {
  body: '',
  hint: '',
  actionLabel: '',
  accept: 'image/jpeg,image/png,image/webp,image/gif',
  testId: undefined,
  inputTestId: undefined,
  layout: 'stacked',
  tone: 'adaptive',
})

const emit = defineEmits<{
  upload: [file: File]
}>()

const isDragging = shallowRef(false)

const dropzoneClasses = computed(() => [
  props.layout === 'stacked'
    ? 'image-upload-dropzone--stacked flex flex-col items-center gap-5 rounded-xl px-6 py-9 text-center sm:gap-6 sm:p-14'
    : 'image-upload-dropzone--inline grid min-h-64 grid-cols-1 items-center justify-items-center gap-4 rounded-xl p-5 text-center sm:grid-cols-[auto_minmax(0,1fr)_auto] sm:justify-items-stretch sm:text-left',
  `image-upload-dropzone--${props.tone}`,
  { 'image-upload-dropzone--active': isDragging.value },
])

function handleFiles(files: File[] | FileList | null | undefined) {
  const file = Array.from(files ?? [])[0]
  if (!file) return

  emit('upload', file)
}

function onDrop(event: DragEvent) {
  isDragging.value = false
  handleFiles(event.dataTransfer?.files ?? null)
}

function onDragEnter(event: DragEvent) {
  if (event.dataTransfer?.types.includes('Files')) {
    isDragging.value = true
  }
}

function onDragLeave(event: DragEvent) {
  const target = event.currentTarget as HTMLElement
  if (!target.contains(event.relatedTarget as Node)) {
    isDragging.value = false
  }
}
</script>

<template>
  <section class="image-upload grid gap-3" :data-testid="testId">
    <FileInput
      :accept="accept"
      :input-test-id="inputTestId"
      reset-on-select
      root-class="w-full"
      variant="ghost"
      class="image-upload-dropzone group relative h-auto w-full overflow-hidden whitespace-normal border-2 border-dashed text-foreground transition-[border-color,box-shadow,transform,background] duration-200 ease-[cubic-bezier(0.16,1,0.3,1)] motion-reduce:duration-[0.01ms]"
      :class="dropzoneClasses"
      @change="handleFiles"
      @drop.prevent="onDrop"
      @dragover.prevent
      @dragenter.prevent="onDragEnter"
      @dragleave="onDragLeave"
    >
      <span
        class="image-upload-dropzone__noise pointer-events-none absolute inset-0 bg-repeat bg-[length:150px_150px]"
        :class="props.tone === 'light' ? 'opacity-[0.16]' : 'opacity-[0.3]'"
        aria-hidden="true"
      />

      <span
        class="image-upload-dropzone__icon relative grid shrink-0 place-items-center rounded-full bg-brand-gradient text-white shadow-[0_2px_8px_oklch(0.61_0.19_35_/_0.25),0_8px_24px_oklch(0.61_0.19_35_/_0.15)] transition-[box-shadow,transform] duration-200 group-hover:-translate-y-0.5 group-hover:shadow-[0_4px_12px_oklch(0.61_0.19_35_/_0.3),0_12px_32px_oklch(0.61_0.19_35_/_0.18)] motion-reduce:transition-none motion-reduce:group-hover:translate-y-0"
        :class="props.layout === 'stacked' ? 'size-[4.5rem]' : 'size-16'"
        aria-hidden="true"
      >
        <slot name="icon">
          <Upload class="size-7" />
        </slot>
      </span>

      <span class="relative grid min-w-0 gap-[0.4rem]">
        <span
          class="image-upload-dropzone__title font-[750] leading-tight text-inherit"
          :class="props.layout === 'inline' ? 'text-[1.1rem]' : 'text-base'"
        >
          {{ title }}
        </span>
        <span
          v-if="body"
          class="text-[0.92rem] leading-[1.45]"
          :class="props.tone === 'light' ? 'text-[oklch(0.51_0.034_265)]' : 'text-muted-foreground'"
        >
          {{ body }}
        </span>
        <span
          v-if="$slots.hint || hint"
          class="image-upload-dropzone__hint text-sm leading-[1.45]"
          :class="props.tone === 'light' ? 'text-[oklch(0.51_0.034_265)]' : 'text-muted-foreground'"
        >
          <slot name="hint">{{ hint }}</slot>
        </span>
      </span>

      <span
        v-if="actionLabel"
        class="relative inline-flex items-center gap-[0.45rem] whitespace-nowrap text-[0.9rem] font-bold text-[oklch(0.55_0.17_35)]"
      >
        <slot name="action-icon">
          <Upload class="size-4" />
        </slot>
        {{ actionLabel }}
      </span>
    </FileInput>
  </section>
</template>

<style scoped>
/* CSS exceptions: tone gradients and scoped dark-mode FileInput descendant states. */
.image-upload :deep(.image-upload-dropzone--adaptive) {
  border-color: oklch(0.78 0.04 45 / 0.42);
  background:
    radial-gradient(circle at 50% 32%, oklch(0.61 0.19 35 / 0.08), transparent 34%),
    var(--surface-empty);
  box-shadow:
    0 1px 3px oklch(0 0 0 / 0.04),
    0 4px 16px oklch(0 0 0 / 0.03);
}

.image-upload :deep(.image-upload-dropzone--adaptive:hover) {
  border-color: oklch(0.61 0.19 35 / 0.4);
  background:
    radial-gradient(circle at 50% 32%, oklch(0.61 0.19 35 / 0.12), transparent 36%),
    linear-gradient(165deg, oklch(0.98 0.015 50 / 0.72) 0%, oklch(0.97 0.008 40 / 0.5) 100%);
  box-shadow:
    0 2px 8px oklch(0 0 0 / 0.06),
    0 8px 24px oklch(0 0 0 / 0.04);
}

.image-upload :deep(.image-upload-dropzone--adaptive.image-upload-dropzone--active) {
  border-color: oklch(0.61 0.19 35);
  background:
    radial-gradient(circle at 50% 32%, oklch(0.61 0.19 35 / 0.16), transparent 38%),
    linear-gradient(165deg, oklch(0.96 0.03 50 / 0.8) 0%, oklch(0.97 0.02 40 / 0.6) 100%);
  box-shadow:
    0 2px 8px oklch(0.61 0.19 35 / 0.08),
    0 8px 24px oklch(0.61 0.19 35 / 0.06);
  transform: scale(1.01);
}

.image-upload :deep(.image-upload-dropzone--light),
.dark .image-upload :deep(.image-upload-dropzone--light) {
  border-color: oklch(0.75 0.05 55 / 0.56);
  background:
    radial-gradient(circle at 12% 18%, oklch(0.75 0.14 75 / 0.12), transparent 36%),
    linear-gradient(145deg, oklch(0.99 0.006 85), oklch(0.97 0.008 70));
  color: oklch(0.24 0.014 285);
  box-shadow: none;
}

.image-upload :deep(.image-upload-dropzone--light:hover),
.image-upload :deep(.image-upload-dropzone--light.image-upload-dropzone--active),
.dark .image-upload :deep(.image-upload-dropzone--light:hover),
.dark .image-upload :deep(.image-upload-dropzone--light.image-upload-dropzone--active) {
  border-color: oklch(0.62 0.17 35);
  box-shadow: 0 12px 32px oklch(0.38 0.08 50 / 0.12);
  transform: translateY(-1px);
}

.image-upload-dropzone__noise {
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='0.04'/%3E%3C/svg%3E");
}

.dark .image-upload :deep(.image-upload-dropzone--adaptive) {
  border-color: oklch(0.58 0.026 285 / 0.56);
  background:
    radial-gradient(circle at 50% 32%, oklch(0.61 0.19 35 / 0.1), transparent 34%),
    linear-gradient(165deg, oklch(0.27 0.012 285 / 0.74) 0%, oklch(0.21 0.01 285 / 0.9) 100%);
  box-shadow:
    inset 0 1px 0 oklch(1 0 0 / 0.05),
    0 16px 48px oklch(0 0 0 / 0.22);
}

.dark .image-upload :deep(.image-upload-dropzone--adaptive:hover) {
  border-color: oklch(0.61 0.19 35 / 0.62);
  background:
    radial-gradient(circle at 50% 32%, oklch(0.61 0.19 35 / 0.14), transparent 34%),
    linear-gradient(165deg, oklch(0.25 0.014 285 / 0.94) 0%, oklch(0.18 0.01 285 / 0.98) 100%);
  box-shadow:
    inset 0 1px 0 oklch(1 0 0 / 0.04),
    0 18px 54px oklch(0 0 0 / 0.34),
    0 0 0 1px oklch(0.61 0.19 35 / 0.14);
}

.dark .image-upload :deep(.image-upload-dropzone--adaptive:hover) .image-upload-dropzone__title {
  color: oklch(0.985 0 0);
}

.dark .image-upload :deep(.image-upload-dropzone--adaptive:hover) .image-upload-dropzone__hint {
  color: oklch(0.84 0.01 285);
}

.dark .image-upload :deep(.image-upload-dropzone--adaptive.image-upload-dropzone--active) {
  border-color: oklch(0.61 0.19 35 / 0.7);
  background:
    radial-gradient(circle at 50% 32%, oklch(0.61 0.19 35 / 0.24), transparent 38%),
    linear-gradient(165deg, oklch(0.36 0.025 35 / 0.74) 0%, oklch(0.24 0.012 285 / 0.92) 100%);
  box-shadow:
    inset 0 1px 0 oklch(1 0 0 / 0.06),
    0 18px 54px oklch(0 0 0 / 0.28),
    0 0 0 1px oklch(0.61 0.19 35 / 0.12);
}

@media (prefers-reduced-motion: reduce) {
  .image-upload :deep(.image-upload-dropzone) {
    transition-duration: 0.01ms !important;
  }

  .image-upload :deep(.image-upload-dropzone:hover) .image-upload-dropzone__icon,
  .image-upload :deep(.image-upload-dropzone--active),
  .image-upload :deep(.image-upload-dropzone:hover) {
    transform: none;
  }
}
</style>
