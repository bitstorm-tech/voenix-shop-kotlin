<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Badge } from '@/components/ui/badge'
import { formatPrice } from '@/lib/formatPrice'

/**
 * One price the way a shop writes it: what the customer pays, and - when the article is discounted -
 * the struck-through price it was reduced from plus how much that saves.
 *
 * The component decides the discounted state itself, from the two amounts alone: a regular price
 * that is missing or not higher than the effective one is not a discount, so the caller may pass
 * whatever the backend answered (`regularPrice`, `regularSalesTotalGross`) without checking it.
 *
 * The discount is readable without colour: the strike-through, the announced "previous price", and
 * the badge text carry it. The badge shows the derived percentage, never a configured one - and
 * where that percentage would round to `0 %` (a saving of a few cents on an expensive article) it
 * shows the saved amount, which is the only honest number left.
 */
const props = withDefaults(
  defineProps<{
    cents: number
    regularCents?: number | null
    size?: 'sm' | 'md' | 'lg'
  }>(),
  {
    regularCents: null,
    size: 'md',
  },
)

const { t } = useI18n()

const SIZE_CLASSES = {
  sm: { price: 'text-[0.82rem]', regular: 'text-[0.72rem]' },
  md: { price: 'text-base', regular: 'text-sm' },
  lg: { price: 'text-xl', regular: 'text-base' },
} as const

const sizeClasses = computed(() => SIZE_CLASSES[props.size])

const isDiscounted = computed(
  () => typeof props.regularCents === 'number' && props.regularCents > props.cents,
)

const savingCents = computed(() =>
  isDiscounted.value ? (props.regularCents as number) - props.cents : 0,
)

const discountPercent = computed(() =>
  isDiscounted.value ? Math.round((savingCents.value / (props.regularCents as number)) * 100) : 0,
)

const badgeLabel = computed(() =>
  discountPercent.value > 0
    ? t('price.discountBadge', { percent: discountPercent.value })
    : `−${formatPrice(savingCents.value)}`,
)
</script>

<template>
  <span class="inline-flex flex-wrap items-baseline gap-x-2 gap-y-1">
    <span
      :class="['font-[850] leading-none text-[var(--price-accent)]', sizeClasses.price]"
      data-testid="product-price-effective"
    >
      {{ formatPrice(cents) }}
    </span>

    <template v-if="isDiscounted">
      <span
        :class="['leading-none text-muted-foreground', sizeClasses.regular]"
        data-testid="product-price-regular"
      >
        <span class="sr-only">{{ t('price.previousPrice') }}</span>
        <span class="line-through">{{ formatPrice(regularCents as number) }}</span>
      </span>

      <Badge
        variant="success"
        class="px-1.5 py-0.5 text-[0.7rem] leading-none tracking-normal"
        data-testid="product-price-badge"
      >
        {{ badgeLabel }}
      </Badge>

      <span
        v-if="discountPercent > 0"
        class="basis-full text-[0.72rem] leading-none text-muted-foreground"
        data-testid="product-price-saving"
      >
        {{ t('price.youSave', { amount: formatPrice(savingCents) }) }}
      </span>
    </template>
  </span>
</template>
