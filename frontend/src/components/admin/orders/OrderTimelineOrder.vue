<script setup lang="ts">
import { Badge } from '@/components/ui/badge'
import { Card } from '@/components/ui/card'
import { formatAdminStamp } from '@/lib/adminStamp'
import { formatPrice } from '@/lib/formatPrice'
import type { AdminOrderAddress, OrderTimeline } from '@/stores/admin/orders'

defineProps<{ order: OrderTimeline['order'] }>()

function addressLines(address: AdminOrderAddress): string[] {
  return [
    `${address.firstName} ${address.lastName}`,
    `${address.street} ${address.houseNumber}`,
    `${address.postalCode} ${address.city}`,
    address.country,
  ]
}
</script>

<template>
  <Card class="space-y-4 p-4" data-testid="timeline-order">
    <div class="flex flex-wrap items-center gap-2">
      <Badge variant="muted">{{ order.status }}</Badge>
      <span class="text-sm text-muted-foreground">
        Payment: {{ order.paymentStatus ?? 'none' }}
      </span>
    </div>

    <dl class="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2 lg:grid-cols-4">
      <div>
        <dt class="text-muted-foreground">Placed</dt>
        <dd>{{ formatAdminStamp(order.createdAt) }}</dd>
      </div>
      <div>
        <dt class="text-muted-foreground">Last changed</dt>
        <dd>{{ formatAdminStamp(order.updatedAt) }}</dd>
      </div>
      <div>
        <dt class="text-muted-foreground">Cart</dt>
        <dd>#{{ order.cartId }}</dd>
      </div>
      <div>
        <dt class="text-muted-foreground">Promotion</dt>
        <dd>{{ order.promotionId === null ? '—' : `#${order.promotionId}` }}</dd>
      </div>
      <div>
        <dt class="text-muted-foreground">E-mail</dt>
        <dd>{{ order.email }}</dd>
      </div>
      <div>
        <dt class="text-muted-foreground">Phone</dt>
        <dd>{{ order.phone ?? '—' }}</dd>
      </div>
      <div>
        <dt class="text-muted-foreground">Shipping address</dt>
        <dd v-for="line in addressLines(order.shippingAddress)" :key="line">{{ line }}</dd>
      </div>
      <div>
        <dt class="text-muted-foreground">Billing address</dt>
        <dd v-for="line in addressLines(order.billingAddress)" :key="line">{{ line }}</dd>
      </div>
    </dl>

    <ul class="divide-y text-sm">
      <li
        v-for="item in order.items"
        :key="item.orderItemId"
        class="flex justify-between gap-4 py-2"
      >
        <span>{{ item.quantity }}× {{ item.articleName }} ({{ item.variantName }})</span>
        <span class="whitespace-nowrap">{{ formatPrice(item.price + item.promptPrice) }}</span>
      </li>
    </ul>

    <dl class="ml-auto grid max-w-xs grid-cols-2 gap-y-1 text-sm">
      <dt class="text-muted-foreground">Subtotal</dt>
      <dd class="text-right">{{ formatPrice(order.subtotal) }}</dd>
      <dt class="text-muted-foreground">Shipping</dt>
      <dd class="text-right">{{ formatPrice(order.shippingCost) }}</dd>
      <dt class="text-muted-foreground">Discount</dt>
      <dd class="text-right">−{{ formatPrice(order.discountAmount) }}</dd>
      <dt class="font-medium">Total</dt>
      <dd class="text-right font-medium">{{ formatPrice(order.total) }}</dd>
    </dl>
  </Card>
</template>
