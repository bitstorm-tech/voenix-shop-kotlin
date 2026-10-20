<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Ban } from 'lucide-vue-next'
import { Label } from '@/components/ui/label'
import { Slider, SliderRange, SliderThumb, SliderTrack } from '@/components/ui/slider'
import { ThumbnailButton } from '@/components/ui/thumbnail-button'
import {
  IMAGE_FRAME_IDS,
  IMAGE_FRAME_INTENSITY_DEFAULT,
  IMAGE_FRAME_INTENSITY_MAX,
  IMAGE_FRAME_INTENSITY_MIN,
  imageFrameMaskStyle,
  type ImageFrame,
  type ImageFrameId,
} from '@/lib/imageFrames'

const props = defineProps<{
  imageUrl: string
  selectedFrame: ImageFrame | null
}>()

const emit = defineEmits<{
  select: [frame: ImageFrame | null]
}>()

const { t } = useI18n()

const intensity = computed(() => props.selectedFrame?.intensity ?? IMAGE_FRAME_INTENSITY_DEFAULT)

/**
 * Each frame previewed on the customer's own image at the current intensity, so the choice shows
 * what it does to the design.
 */
const frameOptions = computed(() =>
  IMAGE_FRAME_IDS.map((id) => ({
    id,
    previewStyle: {
      backgroundImage: `url("${props.imageUrl}")`,
      ...imageFrameMaskStyle({ id, intensity: intensity.value }, 1),
    },
  })),
)

/** Switching the frame keeps the chosen intensity. */
function selectFrame(id: ImageFrameId) {
  emit('select', { id, intensity: intensity.value })
}

function onIntensityChange(value: number[] | undefined) {
  if (!props.selectedFrame || value?.[0] === undefined) return
  emit('select', { id: props.selectedFrame.id, intensity: value[0] })
}

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
          :selected="selectedFrame?.id === frame.id"
          :aria-label="t(`editor.frames.options.${frame.id}`)"
          :data-testid="`editor-frame-option-${frame.id}`"
          @click="selectFrame(frame.id)"
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

    <div v-if="selectedFrame" class="frame-tool-intensity">
      <div class="mb-2 flex items-center gap-3">
        <Label class="mb-0 text-sm">{{ t('editor.frames.intensity') }}</Label>
        <span class="text-xs text-muted-foreground">{{ selectedFrame.intensity }} %</span>
      </div>
      <Slider
        :model-value="[selectedFrame.intensity]"
        :min="IMAGE_FRAME_INTENSITY_MIN"
        :max="IMAGE_FRAME_INTENSITY_MAX"
        :step="5"
        class="w-full"
        data-testid="editor-frame-intensity"
        @update:model-value="onIntensityChange"
      >
        <SliderTrack>
          <SliderRange />
        </SliderTrack>
        <SliderThumb :aria-label="t('editor.frames.intensity')" />
      </Slider>
    </div>
  </div>
</template>
