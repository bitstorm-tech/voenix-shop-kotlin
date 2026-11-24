<script setup lang="ts" generic="T extends { id: number }">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  shallowRef,
  useTemplateRef,
  watch,
} from 'vue'
import { ChevronLeft, ChevronRight, RefreshCw } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'

const props = defineProps<{
  items: T[]
  isLoading: boolean
  error: string | null
  /** The i18n prefix of the state, navigation, and pagination copy, e.g. `landing.styles`. */
  i18nKey: string
}>()

defineSlots<{
  item(props: { item: T }): unknown
}>()

const emit = defineEmits<{
  retry: []
}>()

const { t } = useI18n()
const trackRef = useTemplateRef<HTMLDivElement>('track')
const activePage = shallowRef(0)
const pageCount = shallowRef(1)
const pageOffsets = shallowRef([0])
const canScrollPrev = shallowRef(false)
const canScrollNext = shallowRef(false)
const skeletonCards = [0, 1, 2, 3]
let resizeObserver: ResizeObserver | null = null

const dots = computed(() => Array.from({ length: pageCount.value }, (_, index) => index))
const showPagination = computed(
  () => !props.isLoading && !props.error && props.items.length > 0 && pageCount.value > 1,
)

function getPageOffsets(viewportWidth: number, maxScrollLeft: number) {
  if (maxScrollLeft <= 1) return [0]

  const offsets = [0]
  for (let offset = viewportWidth; offset < maxScrollLeft - 1; offset += viewportWidth) {
    offsets.push(offset)
  }
  offsets.push(maxScrollLeft)
  return offsets
}

function getClosestPage(scrollLeft: number, offsets: number[]) {
  return offsets.reduce((closestPage, offset, index) => {
    const closestOffset = offsets[closestPage] ?? 0
    return Math.abs(offset - scrollLeft) < Math.abs(closestOffset - scrollLeft)
      ? index
      : closestPage
  }, 0)
}

function updateCarouselState() {
  const track = trackRef.value

  if (!track) {
    activePage.value = 0
    pageCount.value = 1
    pageOffsets.value = [0]
    canScrollPrev.value = false
    canScrollNext.value = false
    return
  }

  const viewportWidth = Math.max(track.clientWidth, 1)
  const maxScrollLeft = Math.max(track.scrollWidth - track.clientWidth, 0)
  const offsets = getPageOffsets(viewportWidth, maxScrollLeft)

  pageOffsets.value = offsets
  pageCount.value = offsets.length
  activePage.value = getClosestPage(track.scrollLeft, offsets)
  canScrollPrev.value = track.scrollLeft > 1
  canScrollNext.value = track.scrollLeft < maxScrollLeft - 1
}

function scrollToPage(page: number) {
  const track = trackRef.value
  if (!track) return

  const nextPage = Math.min(Math.max(page, 0), pageCount.value - 1)
  track.scrollTo({
    left: pageOffsets.value[nextPage] ?? 0,
    behavior: 'smooth',
  })
}

function scrollByPage(direction: -1 | 1) {
  scrollToPage(activePage.value + direction)
}

function retry() {
  emit('retry')
}

onMounted(() => {
  void nextTick(updateCarouselState)
  window.addEventListener('resize', updateCarouselState)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', updateCarouselState)
  resizeObserver?.disconnect()
  resizeObserver = null
})

watch(
  trackRef,
  (track) => {
    resizeObserver?.disconnect()
    resizeObserver = null

    if (typeof ResizeObserver !== 'undefined' && track) {
      resizeObserver = new ResizeObserver(updateCarouselState)
      resizeObserver.observe(track)
    }
  },
  { immediate: true, flush: 'post' },
)

watch(
  () => [props.items.length, props.isLoading, props.error],
  () => {
    void nextTick(updateCarouselState)
  },
  { flush: 'post' },
)
</script>

<template>
  <div class="landing-carousel relative">
    <div
      v-if="isLoading"
      class="landing-carousel__track scrollbar-hide flex snap-x snap-mandatory gap-3 overflow-x-auto px-0.5 pb-4 pt-0.5 [scroll-padding-inline:0.1rem]"
      aria-live="polite"
    >
      <span class="landing-carousel__sr sr-only">{{ t(`${i18nKey}.loading`) }}</span>
      <div
        v-for="item in skeletonCards"
        :key="item"
        class="landing-carousel__item landing-carousel__skeleton shrink-0 grow-0 basis-[82%] snap-start overflow-hidden rounded-lg border bg-card sm:basis-[calc((100%_-_1.6rem)/3)] lg:basis-[calc((100%_-_2.4rem)/4)]"
        data-testid="style-skeleton"
      >
        <div class="landing-carousel__skeleton-media aspect-square bg-muted" />
        <div class="landing-carousel__skeleton-body grid min-h-[4.5rem] gap-2 p-3">
          <span class="block h-3 w-[78%] rounded-full bg-muted" />
          <span class="block h-3 w-[48%] rounded-full bg-muted" />
        </div>
      </div>
    </div>

    <div
      v-else-if="error"
      class="landing-carousel__state grid justify-items-center gap-3 rounded-lg border border-dashed bg-background-soft px-4 py-8 text-center"
      role="status"
    >
      <p class="text-sm font-bold text-foreground-muted">{{ t(`${i18nKey}.error`) }}</p>
      <Button
        type="button"
        variant="outline"
        size="sm"
        class="landing-carousel__retry"
        @click="retry"
      >
        <RefreshCw aria-hidden="true" />
        {{ t(`${i18nKey}.retry`) }}
      </Button>
    </div>

    <div
      v-else-if="items.length === 0"
      class="landing-carousel__state grid justify-items-center gap-3 rounded-lg border border-dashed bg-background-soft px-4 py-8 text-center"
      role="status"
    >
      <p class="text-sm font-bold text-foreground-muted">{{ t(`${i18nKey}.empty`) }}</p>
    </div>

    <div v-else class="landing-carousel__shell relative">
      <div class="landing-carousel__viewport relative">
        <div
          ref="track"
          class="landing-carousel__track scrollbar-hide flex snap-x snap-mandatory gap-3 overflow-x-auto px-0.5 pb-4 pt-0.5 [scroll-padding-inline:0.1rem]"
          data-testid="style-carousel-track"
          @scroll.passive="updateCarouselState"
        >
          <div
            v-for="item in items"
            :key="item.id"
            class="landing-carousel__item flex shrink-0 grow-0 basis-[82%] snap-start sm:basis-[calc((100%_-_1.6rem)/3)] lg:basis-[calc((100%_-_2.4rem)/4)]"
          >
            <slot name="item" :item="item" />
          </div>
        </div>

        <div
          class="landing-carousel__controls pointer-events-none absolute bottom-4 left-1 right-1 top-0 z-10 flex items-center justify-between gap-2 sm:left-0 sm:right-0"
          aria-hidden="false"
        >
          <Button
            type="button"
            variant="icon"
            size="icon"
            class="landing-carousel__arrow landing-carousel__arrow--prev pointer-events-auto size-10 rounded-lg border-0 bg-card text-primary shadow-xl shadow-black/20 hover:bg-primary hover:text-primary-foreground disabled:cursor-not-allowed disabled:text-foreground-faint disabled:opacity-60 sm:-translate-x-1/2 sm:hover:-translate-x-1/2 motion-safe:hover:-translate-y-0.5 motion-reduce:transition-none"
            :aria-label="t(`${i18nKey}.previous`)"
            :disabled="!canScrollPrev"
            @click="scrollByPage(-1)"
          >
            <ChevronLeft aria-hidden="true" />
          </Button>
          <Button
            type="button"
            variant="icon"
            size="icon"
            class="landing-carousel__arrow landing-carousel__arrow--next pointer-events-auto size-10 rounded-lg border-0 bg-card text-primary shadow-xl shadow-black/20 hover:bg-primary hover:text-primary-foreground disabled:cursor-not-allowed disabled:text-foreground-faint disabled:opacity-60 sm:translate-x-1/2 sm:hover:translate-x-1/2 motion-safe:hover:-translate-y-0.5 motion-reduce:transition-none"
            :aria-label="t(`${i18nKey}.next`)"
            :disabled="!canScrollNext"
            @click="scrollByPage(1)"
          >
            <ChevronRight aria-hidden="true" />
          </Button>
        </div>
      </div>

      <div v-if="showPagination" class="landing-carousel__dots mt-1 flex justify-center gap-2">
        <Button
          v-for="page in dots"
          :key="page"
          type="button"
          variant="ghost"
          class="landing-carousel__dot h-[0.45rem] min-w-[0.45rem] rounded-full border-0 px-0 py-0 transition-all duration-200 hover:bg-primary/70 focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 motion-reduce:transition-none"
          :class="
            page === activePage
              ? 'landing-carousel__dot--active w-5 bg-primary opacity-100'
              : 'w-[0.45rem] bg-foreground-faint/40 opacity-70'
          "
          :aria-label="
            t(`${i18nKey}.page`, {
              page: page + 1,
              total: pageCount,
            })
          "
          :aria-current="page === activePage ? 'page' : undefined"
          @click="scrollToPage(page)"
        />
      </div>
    </div>
  </div>
</template>
