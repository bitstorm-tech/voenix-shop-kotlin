<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Minus, Plus, Shirt, Trash2 } from 'lucide-vue-next'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { formatPrice } from '@/lib/formatPrice'
import { variantExampleImageUrl } from '@/lib/variantExampleImage'
import { isTshirt, useCatalogStore } from '@/stores/shop/catalog'
import type { CartItem } from '@/stores/shop/cart'

const props = defineProps<{ item: CartItem }>()

const emit = defineEmits<{
  updateQuantity: [quantity: number]
  remove: []
}>()

const { t } = useI18n()
const catalogStore = useCatalogStore()

/** A line whose article the catalog no longer answers for renders without its master data. */
const articleName = computed(() => props.item.articleName ?? t('cart.unknownArticle'))
const printImageUrl = computed(() =>
  props.item.imageId === null ? null : `/api/images/guest/400/${props.item.imageId}`,
)
/** The catalog entry of the ordered line; the catalog store answers it, not the cart line itself. */
const catalogArticle = computed(() => catalogStore.getArticleById(props.item.articleId))

/** The catalog photo of the ordered variant. */
const variantImageUrl = computed(() => {
  const article = catalogArticle.value
  const variant = article?.variants.find((candidate) => candidate.id === props.item.variantId)
  return article && variant?.exampleImageFilename
    ? variantExampleImageUrl(article.articleType, variant.exampleImageFilename, 400)
    : null
})
const colorStyle = computed(() => ({
  backgroundColor: props.item.outsideColorCode ?? 'transparent',
  boxShadow: props.item.insideColorCode
    ? `inset 0 -16px 24px -8px ${props.item.insideColorCode}`
    : undefined,
}))
/**
 * Which shape the line falls back to while no variant photo is there. A mug is a circle of its two
 * colour codes, which a mug line carries itself; a shirt has no colour codes at all - its colour is
 * part of the composed variant name ("Black / M") and its hex only exists in the catalog, so the
 * silhouette is tinted from there and stays a grey outline when the catalog cannot answer.
 */
const isTshirtLine = computed(() => props.item.articleType === 'TSHIRT')
const shirtColorHex = computed(() => {
  const article = catalogArticle.value
  if (article === undefined || !isTshirt(article)) {
    return null
  }

  return (
    article.variants.find((candidate) => candidate.id === props.item.variantId)?.colorHex ?? null
  )
})
const lineTotal = computed(() =>
  formatPrice((props.item.price + props.item.promptPrice) * props.item.quantity),
)
</script>

<template>
  <Card
    as="li"
    class="flex flex-col overflow-hidden shadow-sm transition-shadow hover:shadow-md sm:flex-row"
    data-testid="cart-line-item"
  >
    <!-- Product photo and print motif side by side; on phones the pair tops the card -->
    <div class="flex shrink-0 divide-x divide-border/60">
      <div
        class="shrink-0 bg-muted/50 sm:aspect-auto sm:h-auto sm:w-32"
        :class="printImageUrl ? 'aspect-square w-1/2' : 'h-44 w-full'"
      >
        <img
          v-if="variantImageUrl"
          :src="variantImageUrl"
          :alt="articleName"
          data-testid="cart-line-variant-image"
          class="size-full object-cover"
        />
        <div v-else class="flex size-full items-center justify-center">
          <Shirt
            v-if="isTshirtLine"
            class="size-16 text-muted-foreground"
            data-testid="cart-line-shirt-fallback"
            :fill="shirtColorHex ?? 'none'"
          />
          <div
            v-else
            class="size-16 rounded-full shadow-inner"
            data-testid="cart-line-mug-fallback"
            :style="colorStyle"
          />
        </div>
      </div>
      <div
        v-if="printImageUrl"
        class="aspect-square w-1/2 shrink-0 bg-muted/50 sm:aspect-auto sm:w-32"
      >
        <img
          :src="printImageUrl"
          :alt="t('cart.preview.printAlt')"
          class="size-full object-cover"
        />
      </div>
    </div>

    <!-- Item details -->
    <div class="flex min-w-0 flex-1 flex-col p-4 sm:p-5">
      <div class="flex items-start justify-between gap-3">
        <div class="min-w-0">
          <h3 class="font-semibold sm:text-lg">{{ articleName }}</h3>
          <p v-if="item.variantName" class="mt-0.5 text-sm text-muted-foreground">
            {{ item.variantName }}
          </p>
          <div class="mt-2 flex flex-wrap items-center gap-2">
            <Badge
              v-if="item.promptId !== null"
              class="rounded-full border-none bg-primary/10 normal-case tracking-normal text-primary"
            >
              {{ t('cart.aiDesign') }}
            </Badge>
            <Badge
              v-if="!item.available"
              class="border border-destructive/30 bg-destructive/10 normal-case tracking-normal text-destructive"
              data-testid="cart-line-unavailable"
            >
              {{ t('cart.unavailable') }}
            </Badge>
          </div>
        </div>
        <Button
          variant="ghost"
          size="icon-sm"
          class="text-muted-foreground hover:text-destructive"
          :aria-label="t('cart.removeItem', { item: articleName })"
          @click="emit('remove')"
        >
          <Trash2 class="size-4" />
        </Button>
      </div>

      <div class="mt-auto flex items-center justify-between gap-3 pt-4">
        <!-- Quantity controls -->
        <div class="flex items-center gap-1 rounded-lg bg-muted/60 p-1">
          <Button
            variant="ghost"
            size="icon-sm"
            class="size-7 rounded-md bg-background shadow-sm"
            :disabled="item.quantity <= 1 || !item.available"
            :aria-label="t('cart.decreaseQuantity', { item: articleName })"
            @click="emit('updateQuantity', item.quantity - 1)"
          >
            <Minus class="size-3.5" />
          </Button>
          <span class="w-8 text-center text-sm font-semibold tabular-nums">
            {{ item.quantity }}
          </span>
          <Button
            variant="ghost"
            size="icon-sm"
            class="size-7 rounded-md bg-background shadow-sm"
            :disabled="item.quantity >= 99 || !item.available"
            :aria-label="t('cart.increaseQuantity', { item: articleName })"
            @click="emit('updateQuantity', item.quantity + 1)"
          >
            <Plus class="size-3.5" />
          </Button>
        </div>

        <span class="text-base font-bold tabular-nums sm:text-lg">{{ lineTotal }}</span>
      </div>
    </div>
  </Card>
</template>
