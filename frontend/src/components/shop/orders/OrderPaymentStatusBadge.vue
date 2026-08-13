<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Badge } from '@/components/ui/badge'
import type { OrderPaymentStatus } from '@/stores/shop/orders'

const props = defineProps<{
  /**
   * `null` is not a missing value: the order has no payment at all — it was free, or its checkout
   * was never started. It gets its own label rather than an "unknown" default.
   */
  status: OrderPaymentStatus | null
}>()

const { t } = useI18n()

const label = computed(() =>
  props.status ? t(`orders.paymentStatus.${props.status}`) : t('orders.paymentStatus.none'),
)

const classes = computed(() => {
  if (props.status === 'PAID' || props.status === 'AUTHORIZED') {
    return 'border-success-border bg-success-soft text-success-foreground'
  }

  if (props.status === 'FAILED' || props.status === 'CANCELED' || props.status === 'EXPIRED') {
    return 'border-destructive/30 bg-destructive/10 text-destructive'
  }

  return 'border-border bg-muted/60 text-muted-foreground'
})
</script>

<template>
  <Badge :class="['border normal-case tracking-normal', classes]" data-testid="order-payment-badge">
    {{ label }}
  </Badge>
</template>
