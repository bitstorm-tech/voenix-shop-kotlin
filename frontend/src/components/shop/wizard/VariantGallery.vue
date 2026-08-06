<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Check } from 'lucide-vue-next'
import { ThumbnailButton } from '@/components/ui/thumbnail-button'
import type { GeneratedImage } from '@/stores/shop/imageGeneration'

const props = defineProps<{
  images: GeneratedImage[]
  selectedImageId: string | null
}>()

const emit = defineEmits<{
  select: [id: string]
}>()

const { t } = useI18n()

const reversedImages = computed(() => [...props.images].reverse())
</script>

<template>
  <div
    class="vg-gallery scrollbar-hide flex gap-2 overflow-x-auto pb-2 sm:flex-wrap sm:overflow-x-visible sm:pb-0"
  >
    <ThumbnailButton
      v-for="(img, index) in reversedImages"
      :key="img.id"
      class="vg-thumb size-[72px] shrink-0 cursor-pointer overflow-hidden rounded-lg border-border motion-safe:animate-enter-scale motion-reduce:animate-none data-[state=selected]:[border-width:2.5px] data-[state=selected]:border-primary data-[state=selected]:shadow-[0_0_0_2px_oklch(0.61_0.19_35_/_0.2)] data-[state=selected]:ring-0 data-[state=unselected]:border-2 data-[state=unselected]:border-border data-[state=unselected]:opacity-70 data-[state=unselected]:transition-[border-color,opacity,transform] data-[state=unselected]:duration-200 data-[state=unselected]:[transition-timing-function:ease] data-[state=unselected]:hover:scale-105 data-[state=unselected]:hover:border-[oklch(0.8_0.02_0_/_0.8)] data-[state=unselected]:hover:opacity-100 motion-reduce:data-[state=unselected]:transition-none motion-reduce:data-[state=unselected]:hover:scale-100 dark:data-[state=unselected]:hover:border-[oklch(0.55_0.02_0_/_0.8)]"
      :src="img.url"
      :alt="
        t('mugConfigurator.steps.generate.variationAlt', {
          number: images.length - index,
        })
      "
      :selected="img.id === selectedImageId"
      :style="{ animationDelay: `${index * 60}ms` }"
      @click="emit('select', img.id)"
    >
      <template #overlay>
        <span
          v-if="img.id === selectedImageId"
          class="absolute bottom-[3px] right-[3px] flex size-[18px] items-center justify-center rounded-full bg-brand-gradient shadow-[0_1px_4px_oklch(0_0_0_/_0.2)] motion-safe:animate-enter-pop motion-reduce:animate-none"
        >
          <Check class="h-2.5 w-2.5 text-white" />
        </span>
      </template>
    </ThumbnailButton>
  </div>
</template>
