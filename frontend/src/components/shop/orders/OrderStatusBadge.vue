<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Badge } from '@/components/ui/badge'
import type { OrderPaymentStatus, OrderShippingStatus, OrderStatus } from '@/stores/shop/orders'
import { orderDisplayStatus } from './orderDisplayStatus'

const props = withDefaults(
  defineProps<{
    status: OrderStatus
    /**
     * `null` is not a missing value: the order has no payment at all — it was free, or its checkout
     * was never started. The combined display status then rests on the order status alone.
     */
    paymentStatus: OrderPaymentStatus | null
    /** Shipping progress across production jobs; `null` before production is requested. */
    shippingStatus?: OrderShippingStatus | null
  }>(),
  {
    shippingStatus: null,
  },
)

const { t } = useI18n()

const displayStatus = computed(() =>
  orderDisplayStatus(props.status, props.paymentStatus, props.shippingStatus),
)

const label = computed(() => t(`orders.displayStatus.${displayStatus.value}`))

const classes = computed(() => {
  if (displayStatus.value === 'PAID' || displayStatus.value === 'SHIPPED') {
    return 'border-success-border bg-success-soft text-success-foreground'
  }

  if (displayStatus.value === 'PENDING' || displayStatus.value === 'PARTIALLY_SHIPPED') {
    return 'border-warning-border bg-warning-soft text-warning-foreground'
  }

  return 'border-destructive/30 bg-destructive/10 text-destructive'
})
</script>

<template>
  <Badge :class="['border normal-case tracking-normal', classes]" data-testid="order-status-badge">
    {{ label }}
  </Badge>
</template>
