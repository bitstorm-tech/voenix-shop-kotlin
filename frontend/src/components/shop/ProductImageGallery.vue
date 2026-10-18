<script setup lang="ts">
import { shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ChevronLeft, ChevronRight } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'

/**
 * The pictures of a product card, one at a time, filling the media box of its caller. With two or
 * more pictures the customer leafs through them with the arrows, the dots, a swipe, or the arrow
 * keys, and leafing wraps around at both ends. A caller with no picture shows its own placeholder
 * instead of this gallery.
 *
 * The card around it can itself be a button (the wizard's article grid), so every control stops
 * its event: leafing never selects the card.
 */
const props = defineProps<{
  images: string[]
  alt: string
}>()

const { t } = useI18n()

/** How far a finger has to travel sideways before a touch counts as a swipe, in pixels. */
const SWIPE_THRESHOLD_PX = 30

const currentIndex = shallowRef(0)

// Another colour or another article starts again at its first picture; a new list with the same
// pictures (another size of the same colour) keeps the current one.
watch(
  () => props.images,
  (images, previousImages) => {
    const unchanged =
      images.length === previousImages.length &&
      images.every((image, index) => image === previousImages[index])
    if (!unchanged) currentIndex.value = 0
  },
)

function show(index: number) {
  const count = props.images.length
  currentIndex.value = (index + count) % count
}

let pointerStartX: number | null = null
/** A mouse press that ends in a swipe still fires a click; that one must not reach the card. */
let swiped = false

function onPointerDown(event: PointerEvent) {
  pointerStartX = event.clientX
}

function onPointerUp(event: PointerEvent) {
  if (pointerStartX === null) return
  const distance = event.clientX - pointerStartX
  pointerStartX = null
  if (Math.abs(distance) < SWIPE_THRESHOLD_PX) return

  swiped = true
  show(currentIndex.value + (distance < 0 ? 1 : -1))
}

function onClick(event: MouseEvent) {
  if (!swiped) return
  swiped = false
  event.stopPropagation()
}

function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return

  event.preventDefault()
  show(currentIndex.value + (event.key === 'ArrowRight' ? 1 : -1))
}

/** On a device that can hover the arrows wait for hover or focus; on a touch screen they stay. */
const arrowClass =
  'absolute top-1/2 z-[3] -translate-y-1/2 rounded-full bg-background/85 backdrop-blur-md transition-opacity duration-200 [@media(hover:hover)]:opacity-0 group-hover/gallery:opacity-100 group-focus-within/gallery:opacity-100'
</script>

<template>
  <div
    class="group/gallery relative z-[2] size-full touch-pan-y"
    data-testid="product-image-gallery"
    @pointerdown.stop="onPointerDown"
    @pointerup.stop="onPointerUp"
    @keydown.stop="onKeydown"
    @click="onClick"
  >
    <img
      :src="images[currentIndex]"
      :alt="alt"
      class="size-full object-contain p-6 drop-shadow-[0_0.95rem_1.15rem_oklch(0_0_0_/_0.16)] transition-transform duration-300 group-hover:scale-105 motion-reduce:transition-none"
    />

    <template v-if="images.length > 1">
      <Button
        type="button"
        variant="icon"
        size="icon-sm"
        :class="[arrowClass, 'left-2']"
        :aria-label="t('productCard.previousImage')"
        data-testid="product-image-gallery-previous"
        @click.stop="show(currentIndex - 1)"
      >
        <ChevronLeft />
      </Button>
      <Button
        type="button"
        variant="icon"
        size="icon-sm"
        :class="[arrowClass, 'right-2']"
        :aria-label="t('productCard.nextImage')"
        data-testid="product-image-gallery-next"
        @click.stop="show(currentIndex + 1)"
      >
        <ChevronRight />
      </Button>

      <div class="absolute inset-x-0 bottom-1 z-[3] flex justify-center">
        <Button
          v-for="(_, index) in images"
          :key="index"
          type="button"
          variant="ghost"
          class="group/dot size-6 rounded-full p-0 hover:bg-transparent"
          :aria-label="t('productCard.imageOf', { current: index + 1, total: images.length })"
          :aria-current="index === currentIndex ? 'true' : undefined"
          data-testid="product-image-gallery-dot"
          @click.stop="show(index)"
        >
          <span
            class="size-2 rounded-full transition-colors"
            :class="
              index === currentIndex
                ? 'bg-primary'
                : 'bg-foreground/30 group-hover/dot:bg-foreground/60'
            "
          />
        </Button>
      </div>
    </template>
  </div>
</template>
