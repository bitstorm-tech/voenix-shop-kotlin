<script setup lang="ts">
import { computed, shallowRef } from 'vue'
import { Slider, SliderRange, SliderThumb, SliderTrack } from '@/components/ui/slider'

interface Props {
  beforeImage: string
  afterImage: string
  beforeAlt: string
  afterAlt: string
  beforeLabel: string
  afterLabel: string
  afterTag?: string
  hintLabel: string
  sliderAriaLabel: string
  imageWidth?: number
  imageHeight?: number
  initialPosition?: number
}

const props = withDefaults(defineProps<Props>(), {
  afterTag: '',
  imageWidth: 512,
  imageHeight: 512,
  initialPosition: 54,
})

const sliderPosition = shallowRef(props.initialPosition)
const sliderValue = computed(() => [sliderPosition.value])

const beforeOverlayStyle = computed(() => ({
  clipPath: `inset(0 ${100 - sliderPosition.value}% 0 0)`,
}))

const dividerStyle = computed(() => ({
  left: `${sliderPosition.value}%`,
}))

function clampPosition(position: number) {
  return Math.min(100, Math.max(0, position))
}

function handleSliderValueChange(value: number[] | undefined) {
  if (!value?.length) return

  sliderPosition.value = clampPosition(value[0] ?? sliderPosition.value)
}
</script>

<template>
  <article
    class="comparison-card overflow-hidden rounded-lg border border-border bg-background-soft shadow-[0_18px_40px_rgba(17,24,39,0.08)]"
  >
    <div
      class="comparison-media group relative aspect-square overflow-hidden select-none [touch-action:pan-y]"
    >
      <img
        :src="afterImage"
        :alt="afterAlt"
        class="block size-full object-cover saturate-[1.05]"
        :width="imageWidth"
        :height="imageHeight"
        draggable="false"
        loading="eager"
        decoding="async"
      />

      <div class="comparison-before" :style="beforeOverlayStyle" aria-hidden="true">
        <img
          :src="beforeImage"
          alt=""
          class="block size-full object-cover"
          :width="imageWidth"
          :height="imageHeight"
          draggable="false"
          loading="eager"
          decoding="async"
        />
      </div>

      <div
        class="comparison-badges pointer-events-none absolute inset-0 flex items-start justify-between gap-3 p-[0.85rem] max-md:p-[0.7rem]"
        aria-hidden="true"
      >
        <span
          class="inline-flex max-w-[calc(50%-0.375rem)] items-center gap-[0.4rem] rounded-full border border-[rgba(255,255,255,0.16)] bg-[rgba(17,24,39,0.76)] px-[0.6rem] py-[0.4rem] text-[0.68rem] font-bold leading-[1.2] tracking-[0.01em] text-[rgba(255,255,255,0.98)] shadow-[0_10px_24px_rgba(17,24,39,0.22)] [backdrop-filter:blur(14px)] [text-shadow:0_1px_2px_rgba(0,0,0,0.35)] max-md:text-[0.62rem]"
        >
          {{ beforeLabel }}
        </span>
        <span
          class="inline-flex max-w-[calc(50%-0.375rem)] items-center justify-end gap-[0.4rem] rounded-full border border-[rgba(255,255,255,0.16)] bg-[rgba(17,24,39,0.76)] px-[0.6rem] py-[0.4rem] text-right text-[0.68rem] font-bold leading-[1.2] tracking-[0.01em] text-[rgba(255,255,255,0.98)] shadow-[0_10px_24px_rgba(17,24,39,0.22)] [backdrop-filter:blur(14px)] [text-shadow:0_1px_2px_rgba(0,0,0,0.35)] max-md:text-[0.62rem]"
        >
          <span>{{ afterLabel }}</span>
          <span
            v-if="afterTag"
            class="rounded-full bg-primary px-[0.42rem] py-[0.15rem] text-[0.56rem] font-extrabold uppercase tracking-[0.06em] text-primary-foreground"
          >
            {{ afterTag }}
          </span>
        </span>
      </div>

      <div
        class="comparison-divider pointer-events-none absolute bottom-0 top-0 w-0 -translate-x-1/2"
        :style="dividerStyle"
        aria-hidden="true"
      >
        <span class="absolute left-1/2 top-1/2 -translate-x-1/2 -translate-y-1/2">
          <span
            class="grid h-[2.08rem] w-[1.42rem] place-items-center rounded-full bg-[linear-gradient(180deg,#fb923c,#ea580c)] shadow-[0_6px_14px_rgba(249,115,22,0.26),inset_0_1px_0_rgba(255,255,255,0.28),inset_0_-1px_0_rgba(124,45,18,0.2)] transition-[transform,box-shadow] duration-[180ms] ease-in-out group-hover:scale-[1.04] group-hover:shadow-[0_8px_18px_rgba(249,115,22,0.3),inset_0_1px_0_rgba(255,255,255,0.28),inset_0_-1px_0_rgba(124,45,18,0.2)] group-focus-within:scale-[1.04] group-focus-within:shadow-[0_8px_18px_rgba(249,115,22,0.3),inset_0_1px_0_rgba(255,255,255,0.28),inset_0_-1px_0_rgba(124,45,18,0.2)] max-md:h-[1.9rem] max-md:w-[1.3rem]"
          >
            <span
              class="h-[0.96rem] w-[0.12rem] rounded-full bg-[rgba(255,255,255,0.94)] shadow-[-0.28rem_0_0_rgba(255,255,255,0.8),0.28rem_0_0_rgba(255,255,255,0.8)]"
            />
          </span>
        </span>
      </div>

      <Slider
        :model-value="sliderValue"
        class="absolute inset-0 h-full w-full cursor-ew-resize items-stretch bg-transparent"
        :min="0"
        :max="100"
        :step="0.1"
        :aria-label="sliderAriaLabel"
        @update:model-value="handleSliderValueChange"
      >
        <SliderTrack class="h-full rounded-none bg-transparent">
          <SliderRange class="bg-transparent" />
        </SliderTrack>
        <SliderThumb class="size-11 cursor-ew-resize border-0 bg-transparent shadow-none" />
      </Slider>
    </div>

    <div
      class="flex items-center justify-between gap-3 border-t border-border px-[0.8rem] py-[0.65rem] text-[0.72rem] font-bold text-foreground-soft max-md:text-[0.68rem]"
    >
      <span>{{ hintLabel }}</span>
      <span class="text-[0.95rem] text-primary" aria-hidden="true">↔</span>
    </div>
  </article>
</template>

<style scoped>
/* CSS exceptions: visual wash gradient and pseudo-element overlay/divider glow. */
.comparison-media {
  background:
    radial-gradient(circle at 18% 18%, rgba(255, 255, 255, 0.85), transparent 24%),
    linear-gradient(135deg, #fff6ee, #f7efe8 46%, #ece5df);
}

.comparison-before {
  position: absolute;
  inset: 0;
  overflow: hidden;
}

.comparison-before::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, transparent 88%, rgba(255, 255, 255, 0.3));
  pointer-events: none;
}

.comparison-divider::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: 50%;
  width: 2px;
  transform: translateX(-50%);
  background: rgba(255, 255, 255, 0.95);
  box-shadow: 0 0 0 1px rgba(17, 24, 39, 0.08);
  transition:
    background-color 180ms ease,
    box-shadow 180ms ease;
}

.comparison-media:hover .comparison-divider::before,
.comparison-media:focus-within .comparison-divider::before {
  background: rgba(255, 255, 255, 0.98);
  box-shadow:
    0 0 0 1px rgba(17, 24, 39, 0.08),
    0 0 18px rgba(255, 255, 255, 0.38);
}
</style>
