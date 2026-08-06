<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Check, Palette } from 'lucide-vue-next'
import { SwatchButton } from '@/components/ui/swatch-button'
import { variantExampleImageUrl } from '@/lib/variantExampleImage'
import type { MugDto, MugVariantDto } from '@/stores/shop/mugs'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    mug: MugDto
    activeVariant: MugVariantDto | null
    formattedPrice: string
    cardIndex?: number
    selected?: boolean
    as?: 'button' | 'article'
  }>(),
  {
    cardIndex: 0,
    selected: false,
    as: 'article',
  },
)

const emit = defineEmits<{
  click: []
  'select-variant': [variantId: number]
}>()

function variantSwatchColor(variant: MugVariantDto) {
  return `linear-gradient(135deg, ${variant.outsideColorCode} 0%, ${variant.outsideColorCode} 50%, ${variant.insideColorCode} 50%, ${variant.insideColorCode} 100%)`
}
</script>

<template>
  <component
    :is="props.as === 'button' ? 'article' : props.as"
    :role="props.as === 'button' ? 'button' : undefined"
    :tabindex="props.as === 'button' ? 0 : undefined"
    :aria-pressed="props.as === 'button' ? selected : undefined"
    class="mug-card group relative flex flex-row overflow-hidden rounded-xl border-[1.5px] border-border bg-surface-card text-left shadow-[0_1px_3px_oklch(0_0_0_/_0.04),0_4px_16px_oklch(0_0_0_/_0.03)] transition-all duration-300 [animation-delay:calc(var(--card-index,0)*60ms)] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-[3px] focus-visible:outline-[oklch(0.61_0.19_35_/_0.82)] motion-safe:animate-enter-lift motion-reduce:animate-none motion-reduce:transition-none sm:flex-col dark:shadow-[0_1px_3px_oklch(0_0_0_/_0.3),0_4px_16px_oklch(0_0_0_/_0.25)]"
    :class="[
      selected
        ? 'border-[oklch(0.61_0.19_35_/_0.7)] shadow-[0_0_0_1px_oklch(0.61_0.19_35_/_0.15),0_4px_12px_oklch(0.61_0.19_35_/_0.1),0_8px_24px_oklch(0.61_0.19_35_/_0.06)] hover:-translate-y-0.5 hover:shadow-[0_0_0_1px_oklch(0.61_0.19_35_/_0.2),0_6px_16px_oklch(0.61_0.19_35_/_0.12),0_12px_32px_oklch(0.61_0.19_35_/_0.08)] motion-reduce:hover:translate-y-0 dark:shadow-[0_0_0_1px_oklch(0.61_0.19_35_/_0.15),0_4px_12px_oklch(0.61_0.19_35_/_0.1),0_8px_24px_oklch(0.61_0.19_35_/_0.06)] dark:hover:shadow-[0_0_0_1px_oklch(0.61_0.19_35_/_0.2),0_6px_16px_oklch(0.61_0.19_35_/_0.12),0_12px_32px_oklch(0.61_0.19_35_/_0.08)]'
        : 'hover:-translate-y-1 hover:border-[var(--surface-card-hover-border)] hover:shadow-[0_7px_18px_oklch(0_0_0_/_0.07),0_18px_42px_oklch(0_0_0_/_0.08)] motion-reduce:hover:translate-y-0 dark:hover:shadow-[0_4px_12px_oklch(0_0_0_/_0.4),0_12px_32px_oklch(0_0_0_/_0.35)]',
      props.as === 'button' ? 'cursor-pointer' : 'cursor-default',
    ]"
    :style="{
      '--card-index': cardIndex,
      '--mug-outside-color': activeVariant?.outsideColorCode ?? '#cccccc',
      '--mug-inside-color': activeVariant?.insideColorCode ?? '#ffffff',
    }"
    @click="emit('click')"
    @keydown.enter.self.prevent="emit('click')"
    @keydown.space.self.prevent="emit('click')"
  >
    <div
      class="mug-card-noise pointer-events-none absolute inset-0 z-[1] bg-repeat bg-[length:150px_150px] opacity-[0.25]"
    />

    <div
      v-if="selected"
      class="absolute right-2 top-2 z-10 flex items-center gap-1 rounded-sm bg-[linear-gradient(135deg,oklch(0.61_0.19_35),oklch(0.68_0.18_45))] px-2 py-0.5 text-[11px] font-semibold text-white shadow-[0_2px_8px_oklch(0.61_0.19_35_/_0.3)] motion-safe:animate-enter-pop motion-reduce:animate-none sm:right-3 sm:top-3 sm:px-2.5 sm:py-1 sm:text-xs"
    >
      <Check class="size-3" />
      {{ t('mugCard.selected') }}
    </div>

    <div
      class="mug-card__media relative min-h-[10.75rem] w-[128px] shrink-0 overflow-hidden sm:aspect-[4/3] sm:min-h-0 sm:w-auto"
    >
      <div class="mug-card-image-bg absolute inset-0" />
      <div
        class="mug-card__halo absolute inset-x-6 bottom-4 top-8 z-[1] rounded-full opacity-[0.58] blur-[16px] sm:inset-x-10 sm:bottom-5"
      />
      <img
        v-if="activeVariant?.exampleImageFilename"
        :src="variantExampleImageUrl(activeVariant.exampleImageFilename, 400)"
        :alt="mug.name"
        class="absolute inset-0 z-[2] size-full object-contain px-3 py-4 drop-shadow-[0_0.95rem_1.15rem_oklch(0_0_0_/_0.16)] transition-transform duration-300 group-hover:scale-105 motion-reduce:transition-none sm:px-7 sm:py-5"
      />
      <div v-else class="absolute inset-0 z-[2] flex size-full items-center justify-center">
        <div
          class="size-[4.5rem] rounded-full shadow-inner transition-transform duration-300 group-hover:scale-110 motion-reduce:transition-none sm:size-[5.4rem]"
          :style="{
            backgroundColor: activeVariant?.outsideColorCode ?? '#cccccc',
            boxShadow: `inset 0 -20px 30px -10px ${activeVariant?.insideColorCode ?? '#ffffff'}`,
          }"
        />
      </div>
    </div>

    <div
      class="mug-card__content relative z-[2] flex min-w-0 flex-1 flex-col gap-[0.85rem] bg-[linear-gradient(180deg,oklch(1_0_0_/_0.18),transparent_48%),transparent] p-3 sm:gap-[0.92rem] sm:p-4"
    >
      <div class="grid gap-[0.38rem]">
        <div class="grid grid-cols-[minmax(0,1fr)_auto] items-start gap-3">
          <h3
            class="min-w-0 text-[0.98rem] font-[780] leading-[1.14] tracking-normal text-foreground sm:text-[1.05rem]"
          >
            {{ mug.name }}
          </h3>
          <p
            class="whitespace-nowrap rounded-sm border border-[oklch(0.61_0.19_35_/_0.16)] bg-[oklch(0.61_0.19_35_/_0.08)] px-2 py-[0.24rem] text-[0.82rem] font-[850] leading-none text-[var(--price-accent)]"
          >
            {{ formattedPrice }}
          </p>
        </div>
        <p
          class="line-clamp-2 min-h-[2.55em] overflow-hidden text-[0.78rem] leading-[1.45] text-muted-foreground"
        >
          {{ mug.descriptionShort }}
        </p>
      </div>

      <div v-if="mug.variants.length > 1" class="grid min-w-0 gap-2">
        <div
          class="flex items-center justify-between gap-[0.65rem] text-[0.72rem] font-[720] text-foreground-muted"
        >
          <span class="inline-flex items-center gap-[0.35rem]">
            <Palette class="size-3.5 text-primary" aria-hidden="true" />
            {{ t('mugCard.colors') }}
          </span>
          <span
            class="inline-grid h-[1.35rem] min-w-[1.35rem] place-items-center rounded-sm bg-black/6 text-[0.68rem] text-foreground-soft"
          >
            {{ mug.variants.length }}
          </span>
        </div>
        <div
          class="scrollbar-hide flex flex-wrap gap-[0.3rem] overflow-x-visible px-[0.15rem] pb-[0.3rem] pt-[0.15rem] [overscroll-behavior-x:contain] sm:flex-nowrap sm:overflow-x-auto"
        >
          <SwatchButton
            v-for="variant in mug.variants"
            :key="variant.id"
            class="size-4 p-0 data-[state=selected]:scale-110 sm:size-4"
            :color="variantSwatchColor(variant)"
            :title="variant.name"
            :label="variant.name"
            :selected="activeVariant?.id === variant.id"
            @click.stop="emit('select-variant', variant.id)"
          />
        </div>
      </div>

      <slot name="action" />
    </div>
  </component>
</template>

<style scoped>
/* CSS exceptions: texture data URL, color-mixed media effects, pseudo shadow, and slot alignment. */
.mug-card-noise {
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='0.04'/%3E%3C/svg%3E");
}

.mug-card-image-bg {
  background:
    radial-gradient(
      circle at 50% 78%,
      color-mix(in oklch, var(--mug-outside-color) 24%, transparent) 0%,
      transparent 48%
    ),
    linear-gradient(180deg, oklch(1 0 0 / 0.08), transparent 35%), var(--surface-image-bg);
}

.mug-card__media::after {
  content: '';
  position: absolute;
  left: 14%;
  right: 14%;
  bottom: 0.9rem;
  z-index: 1;
  height: 1.25rem;
  border-radius: 999px;
  background: oklch(0 0 0 / 0.14);
  filter: blur(12px);
  opacity: 0.62;
}

.mug-card__halo {
  background:
    radial-gradient(
      circle at 44% 42%,
      color-mix(in oklch, var(--mug-inside-color) 32%, white 20%) 0%,
      transparent 56%
    ),
    radial-gradient(
      circle at 58% 62%,
      color-mix(in oklch, var(--mug-outside-color) 42%, transparent) 0%,
      transparent 64%
    );
}

.mug-card__content :slotted(*) {
  margin-top: auto;
}
</style>
