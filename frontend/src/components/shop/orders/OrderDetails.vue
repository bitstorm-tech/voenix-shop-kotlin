<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ImageIcon, Loader2, Paintbrush, ShoppingCart } from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { formatPrice } from '@/lib/formatPrice'
import type { Order, OrderItem } from '@/stores/shop/orders'

const props = defineProps<{
  order: Order
  addingItemId: number | null
}>()

const emit = defineEmits<{
  reorderItem: [item: OrderItem]
  redesignItem: [item: OrderItem]
}>()

const { t } = useI18n()

const orderSubtotal = computed(() =>
  props.order.items.reduce((sum, item) => sum + getItemTotal(item), 0),
)

function isItemBusy(item: OrderItem) {
  return props.addingItemId === item.orderItemId
}

function getImageUrl(item: OrderItem) {
  return item.generatedEditedImageId ? `/api/images/guest/320/${item.generatedEditedImageId}` : ''
}

function getItemTotal(item: OrderItem) {
  return (item.priceAtTime + item.promptPriceAtTime) * item.quantity
}
</script>

<template>
  <div class="space-y-4">
    <ul class="divide-y divide-border rounded-lg border border-border bg-background">
      <li
        v-for="item in order.items"
        :key="item.orderItemId"
        class="grid gap-4 p-4 sm:grid-cols-[88px_minmax(0,1fr)_auto] sm:items-start"
      >
        <div
          class="flex size-20 items-center justify-center overflow-hidden rounded-lg bg-muted/50"
        >
          <img
            v-if="item.generatedEditedImageId"
            :src="getImageUrl(item)"
            :alt="item.articleName"
            class="size-full object-cover"
          />
          <ImageIcon v-else class="size-6 text-muted-foreground" />
        </div>

        <div class="min-w-0">
          <h3 class="font-medium leading-tight">{{ item.articleName }}</h3>
          <p class="mt-1 text-sm text-muted-foreground">
            {{ t('cart.variant') }}: {{ item.variantName }}
          </p>
          <dl class="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 text-sm sm:flex sm:flex-wrap">
            <div>
              <dt class="text-muted-foreground">{{ t('orders.quantity') }}</dt>
              <dd class="font-medium tabular-nums">{{ item.quantity }}</dd>
            </div>
            <div>
              <dt class="text-muted-foreground">{{ t('orders.unitPrice') }}</dt>
              <dd class="font-medium tabular-nums">
                {{ formatPrice(item.priceAtTime + item.promptPriceAtTime) }}
              </dd>
            </div>
            <div>
              <dt class="text-muted-foreground">{{ t('orders.itemTotal') }}</dt>
              <dd class="font-semibold tabular-nums">{{ formatPrice(getItemTotal(item)) }}</dd>
            </div>
          </dl>
        </div>

        <div class="flex flex-col items-stretch gap-2 sm:min-w-40 sm:items-end">
          <Button
            v-if="item.generatedEditedImageId"
            size="sm"
            class="w-full sm:w-auto"
            :disabled="isItemBusy(item)"
            @click="emit('reorderItem', item)"
          >
            <Loader2 v-if="isItemBusy(item)" class="size-4 animate-spin" />
            <ShoppingCart v-else class="size-4" />
            {{ t('orders.reorderItem') }}
          </Button>
          <Button
            v-if="item.generatedEditedImageId"
            variant="outline"
            size="sm"
            class="w-full sm:w-auto"
            :disabled="isItemBusy(item)"
            @click="emit('redesignItem', item)"
          >
            <Loader2 v-if="isItemBusy(item)" class="size-4 animate-spin" />
            <Paintbrush v-else class="size-4" />
            {{ t('orders.redesignItem') }}
          </Button>
          <p v-else class="m-0 max-w-40 text-xs text-muted-foreground">
            {{ t('orders.noGeneratedImage') }}
          </p>
        </div>
      </li>
    </ul>

    <div class="flex flex-col gap-2 text-sm sm:items-end">
      <div class="flex justify-between gap-6 sm:min-w-64">
        <span class="text-muted-foreground">{{ t('orders.subtotal') }}</span>
        <span class="font-medium tabular-nums">{{ formatPrice(orderSubtotal) }}</span>
      </div>
      <div class="flex justify-between gap-6 sm:min-w-64">
        <span class="text-muted-foreground">{{ t('orders.shipping') }}</span>
        <span class="font-medium tabular-nums">{{ formatPrice(order.shippingCostInCents) }}</span>
      </div>
      <div class="flex justify-between gap-6 border-t border-border pt-2 font-semibold sm:min-w-64">
        <span>{{ t('orders.total') }}</span>
        <span class="tabular-nums">{{ formatPrice(order.totalAmountInCents) }}</span>
      </div>
    </div>
  </div>
</template>
