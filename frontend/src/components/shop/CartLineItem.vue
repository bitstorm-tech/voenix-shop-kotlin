<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Minus, Plus, Trash2 } from 'lucide-vue-next'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import CartItemPreviewDialog from '@/components/shop/CartItemPreviewDialog.vue'
import { formatPrice } from '@/lib/formatPrice'
import type { CartItem } from '@/stores/shop/cart'

const props = defineProps<{ item: CartItem }>()

const emit = defineEmits<{
  updateQuantity: [quantity: number]
  remove: []
}>()

const { t } = useI18n()

/** A line whose article the catalog no longer answers for renders without its master data. */
const articleName = computed(() => props.item.articleName ?? t('cart.unknownArticle'))
const printImageUrl = computed(() =>
  props.item.imageId === null ? null : `/api/images/guest/200/${props.item.imageId}`,
)
const colorStyle = computed(() => ({
  backgroundColor: props.item.outsideColorCode ?? 'transparent',
  boxShadow: props.item.insideColorCode
    ? `inset 0 -16px 24px -8px ${props.item.insideColorCode}`
    : undefined,
}))
const lineTotal = computed(() =>
  formatPrice((props.item.price + props.item.promptPrice) * props.item.quantity),
)
</script>

<template>
  <li class="flex gap-4 py-6 first:pt-0 last:pb-0" data-testid="cart-line-item">
    <!-- Image preview or color circle -->
    <div
      class="flex size-20 shrink-0 items-center justify-center overflow-hidden rounded-xl bg-muted/50 sm:size-24"
    >
      <img
        v-if="printImageUrl"
        :src="printImageUrl"
        :alt="articleName"
        class="size-full object-cover"
      />
      <div v-else class="size-12 rounded-full shadow-inner sm:size-16" :style="colorStyle" />
    </div>

    <!-- Item details -->
    <div class="flex min-w-0 flex-1 flex-col justify-between">
      <div>
        <h3 class="font-medium leading-tight">{{ articleName }}</h3>
        <p v-if="item.variantName" class="mt-0.5 text-sm text-muted-foreground">
          {{ t('cart.variant') }}: {{ item.variantName }}
        </p>
        <Badge
          v-if="!item.available"
          class="mt-2 border border-destructive/30 bg-destructive/10 normal-case tracking-normal text-destructive"
          data-testid="cart-line-unavailable"
        >
          {{ t('cart.unavailable') }}
        </Badge>
        <div v-if="item.imageId !== null" class="mt-3">
          <CartItemPreviewDialog :item="item" />
        </div>
      </div>

      <div class="mt-3 flex items-center justify-between gap-4">
        <!-- Quantity controls -->
        <div class="flex items-center gap-1">
          <Button
            variant="outline"
            size="icon-sm"
            :disabled="item.quantity <= 1 || !item.available"
            :aria-label="t('cart.decreaseQuantity', { item: articleName })"
            @click="emit('updateQuantity', item.quantity - 1)"
          >
            <Minus class="size-3.5" />
          </Button>
          <span class="w-9 text-center text-sm font-medium tabular-nums">
            {{ item.quantity }}
          </span>
          <Button
            variant="outline"
            size="icon-sm"
            :disabled="item.quantity >= 99 || !item.available"
            :aria-label="t('cart.increaseQuantity', { item: articleName })"
            @click="emit('updateQuantity', item.quantity + 1)"
          >
            <Plus class="size-3.5" />
          </Button>
        </div>

        <!-- Price + Remove -->
        <div class="flex items-center gap-3">
          <span class="text-sm font-semibold tabular-nums">{{ lineTotal }}</span>
          <Button
            variant="destructive"
            size="icon-sm"
            :aria-label="t('cart.removeItem', { item: articleName })"
            @click="emit('remove')"
          >
            <Trash2 class="size-4" />
          </Button>
        </div>
      </div>
    </div>
  </li>
</template>
