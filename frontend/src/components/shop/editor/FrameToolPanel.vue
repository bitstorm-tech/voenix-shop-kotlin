<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Ban } from 'lucide-vue-next'
import { ThumbnailButton } from '@/components/ui/thumbnail-button'
import { IMAGE_FRAME_IDS, imageFrameMaskStyle, type ImageFrameId } from '@/lib/imageFrames'

const props = defineProps<{
  imageUrl: string
  selectedFrame: ImageFrameId | null
}>()

const emit = defineEmits<{
  select: [frame: ImageFrameId | null]
}>()

const { t } = useI18n()

/** Each frame previewed on the customer's own image, so the choice shows what it does to the design. */
const frameOptions = computed(() =>
  IMAGE_FRAME_IDS.map((id) => ({
    id,
    previewStyle: {
      backgroundImage: `url("${props.imageUrl}")`,
      ...imageFrameMaskStyle(id, 1),
    },
  })),
)

const thumbClass =
  'frame-thumb size-[72px] cursor-pointer rounded-lg data-[state=selected]:border-primary md:size-[76px] md:rounded-xl'
</script>

<template>
  <div class="frame-tool-panel grid gap-3" data-testid="editor-frame-tool-panel">
    <p class="frame-tool-hint m-0 text-sm text-muted-foreground">
      {{ t('editor.frames.hint') }}
    </p>
    <div class="flex flex-wrap gap-3">
      <div class="grid justify-items-center gap-1.5">
        <ThumbnailButton
          :class="thumbClass"
          :selected="selectedFrame === null"
          :aria-label="t('editor.frames.none')"
          data-testid="editor-frame-option-none"
          @click="emit('select', null)"
        >
          <Ban class="size-5" aria-hidden="true" />
        </ThumbnailButton>
        <span class="text-xs text-muted-foreground">{{ t('editor.frames.none') }}</span>
      </div>

      <div v-for="frame in frameOptions" :key="frame.id" class="grid justify-items-center gap-1.5">
        <ThumbnailButton
          :class="thumbClass"
          :selected="selectedFrame === frame.id"
          :aria-label="t(`editor.frames.options.${frame.id}`)"
          :data-testid="`editor-frame-option-${frame.id}`"
          @click="emit('select', frame.id)"
        >
          <span
            class="absolute inset-0 bg-cover bg-center"
            aria-hidden="true"
            :style="frame.previewStyle"
          />
        </ThumbnailButton>
        <span class="text-xs text-muted-foreground">
          {{ t(`editor.frames.options.${frame.id}`) }}
        </span>
      </div>
    </div>
  </div>
</template>
