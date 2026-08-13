<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Badge } from '@/components/ui/badge'
import type { OrderStatus } from '@/stores/shop/orders'

const props = defineProps<{
  status: OrderStatus
}>()

const { t } = useI18n()

const label = computed(() => t(`orders.status.${props.status}`))

const classes = computed(() => {
  if (props.status === 'PAID') {
    return 'border-success-border bg-success-soft text-success-foreground'
  }

  if (props.status === 'CANCELLED') {
    return 'border-destructive/30 bg-destructive/10 text-destructive'
  }

  return 'border-warning-border bg-warning-soft text-warning-foreground'
})
</script>

<template>
  <Badge :class="['border normal-case tracking-normal', classes]" data-testid="order-status-badge">
    {{ label }}
  </Badge>
</template>
