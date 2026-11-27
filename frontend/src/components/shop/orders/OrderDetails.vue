<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  Coffee,
  ExternalLink,
  Loader2,
  Package,
  Paintbrush,
  ShoppingCart,
  Truck,
} from 'lucide-vue-next'
import { Button } from '@/components/ui/button'
import { formatPrice } from '@/lib/formatPrice'
import type { Order, OrderItem } from '@/stores/shop/orders'

const props = withDefaults(
  defineProps<{
    order: Order
    /** The ordered line whose reorder or redesign is currently running, if any. */
    addingItemId?: number | null
    /**
     * Read-only mode for the permanent order link: the same lines and the same amounts, but no
     * print-image thumbnails and no buttons. The page is reachable without any session, so it shows
     * what was ordered and offers nothing that would need one (issue #110, Joe decision 4).
     */
    readonly?: boolean
  }>(),
  {
    addingItemId: null,
    readonly: false,
  },
)

const emit = defineEmits<{
  reorderItem: [item: OrderItem]
  redesignItem: [item: OrderItem]
}>()

const { t, locale } = useI18n()

/** The backend states the discount; it is never inferred from the other three amounts. */
const hasDiscount = computed(() => props.order.discountAmount > 0)
const hasShipments = computed(() => props.order.shipments.length > 0)

function formatDate(value: string) {
  return new Date(value).toLocaleDateString(locale.value === 'en' ? 'en-US' : 'de-DE', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

function isItemBusy(item: OrderItem) {
  return props.addingItemId === item.orderItemId
}

function getImageUrl(item: OrderItem) {
  return item.imageId ? `/api/images/guest/400/${item.imageId}` : ''
}

/**
 * The shape a line without a print image falls back to. The type is the line's own snapshot, so it
 * still says what was bought after the article was renamed, retyped, or deleted (issue #205).
 */
function getItemFallbackIcon(item: OrderItem) {
  return item.articleType === 'SPOD' ? Package : Coffee
}

function getItemUnitPrice(item: OrderItem) {
  return item.price + item.promptPrice
}

function getItemTotal(item: OrderItem) {
  return getItemUnitPrice(item) * item.quantity
}
</script>

<template>
  <div class="space-y-4">
    <div
      v-if="hasShipments"
      class="rounded-lg border border-border bg-background p-4"
      data-testid="order-shipments"
    >
      <div class="flex items-center gap-2 text-sm font-medium">
        <Truck class="size-4 text-muted-foreground" />
        <span>{{ t('orders.shipmentsTitle') }}</span>
      </div>
      <ul class="mt-3 divide-y divide-border text-sm">
        <li
          v-for="(shipment, index) in order.shipments"
          :key="`${shipment.shippedAt}-${index}`"
          class="flex flex-wrap items-center justify-between gap-x-6 gap-y-2 py-2 first:pt-0 last:pb-0"
          data-testid="order-shipment"
        >
          <div class="flex flex-wrap items-center gap-x-4 gap-y-1">
            <span class="font-medium">
              {{ t('orders.shippedOn', { date: formatDate(shipment.shippedAt) }) }}
            </span>
            <span v-if="shipment.carrierName" class="text-muted-foreground">
              {{ t('orders.carrier') }}:
              <span class="font-medium text-foreground">{{ shipment.carrierName }}</span>
            </span>
            <span v-if="shipment.trackingNumber" class="text-muted-foreground">
              {{ t('orders.trackingNumber') }}:
              <span class="font-mono text-xs text-foreground">{{ shipment.trackingNumber }}</span>
            </span>
          </div>
          <a
            v-if="shipment.trackingUrl"
            :href="shipment.trackingUrl"
            target="_blank"
            rel="noopener noreferrer"
            class="inline-flex items-center gap-1.5 font-medium text-primary underline-offset-4 hover:underline"
            data-testid="order-tracking-link"
          >
            {{ t('orders.trackShipment') }}
            <ExternalLink class="size-3.5" />
          </a>
        </li>
      </ul>
    </div>

    <ul class="divide-y divide-border rounded-lg border border-border bg-background">
      <li
        v-for="item in order.items"
        :key="item.orderItemId"
        :class="[
          'grid gap-4 p-4',
          readonly ? '' : 'sm:grid-cols-[88px_minmax(0,1fr)_auto] sm:items-start',
        ]"
      >
        <div
          v-if="!readonly"
          class="flex size-20 items-center justify-center overflow-hidden rounded-lg bg-muted/50"
        >
          <img
            v-if="item.imageId"
            :src="getImageUrl(item)"
            :alt="item.articleName"
            class="size-full object-cover"
          />
          <component
            :is="getItemFallbackIcon(item)"
            v-else
            class="size-6 text-muted-foreground"
            data-testid="order-item-fallback-icon"
          />
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
                {{ formatPrice(getItemUnitPrice(item)) }}
              </dd>
            </div>
            <div>
              <dt class="text-muted-foreground">{{ t('orders.itemTotal') }}</dt>
              <dd class="font-semibold tabular-nums">{{ formatPrice(getItemTotal(item)) }}</dd>
            </div>
          </dl>
        </div>

        <div v-if="!readonly" class="flex flex-col items-stretch gap-2 sm:min-w-40 sm:items-end">
          <Button
            v-if="item.imageId"
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
            v-if="item.imageId"
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
        <span class="font-medium tabular-nums">{{ formatPrice(order.subtotal) }}</span>
      </div>
      <div class="flex justify-between gap-6 sm:min-w-64">
        <span class="text-muted-foreground">{{ t('orders.shipping') }}</span>
        <span class="font-medium tabular-nums">
          {{
            order.shippingCost === 0 ? t('orders.shippingFree') : formatPrice(order.shippingCost)
          }}
        </span>
      </div>
      <div v-if="hasDiscount" class="flex justify-between gap-6 sm:min-w-64">
        <span class="text-muted-foreground">{{ t('orders.discount') }}</span>
        <span class="font-medium tabular-nums" data-testid="order-discount">
          -{{ formatPrice(order.discountAmount) }}
        </span>
      </div>
      <div class="flex justify-between gap-6 border-t border-border pt-2 font-semibold sm:min-w-64">
        <span>{{ t('orders.total') }}</span>
        <span class="tabular-nums">{{ formatPrice(order.total) }}</span>
      </div>
    </div>
  </div>
</template>
