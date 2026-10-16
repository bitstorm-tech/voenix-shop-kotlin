<script setup lang="ts">
import { Badge } from '@/components/ui/badge'
import { Card } from '@/components/ui/card'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { formatAdminStamp } from '@/lib/adminStamp'
import { formatPrice } from '@/lib/formatPrice'
import { orderNumber } from '@/lib/fulfillment'
import type { AdminOrderListEntry } from '@/stores/admin/orders'

defineProps<{ orders: AdminOrderListEntry[] }>()
</script>

<template>
  <Card class="overflow-hidden">
    <div class="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Order</TableHead>
            <TableHead>Placed</TableHead>
            <TableHead>Status</TableHead>
            <TableHead>Payment</TableHead>
            <TableHead>Total</TableHead>
            <TableHead>E-mail</TableHead>
            <TableHead>Open findings</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <TableRow v-for="order in orders" :key="order.orderId" data-testid="order-row">
            <TableCell class="whitespace-nowrap">
              <RouterLink
                :to="{ name: 'admin-order', params: { orderId: order.orderId } }"
                class="font-medium text-primary underline-offset-4 hover:underline"
                data-testid="order-link"
              >
                {{ orderNumber(order.orderId) }}
              </RouterLink>
            </TableCell>
            <TableCell class="whitespace-nowrap text-muted-foreground">
              {{ formatAdminStamp(order.createdAt) }}
            </TableCell>
            <TableCell
              ><Badge variant="muted">{{ order.status }}</Badge></TableCell
            >
            <TableCell class="text-muted-foreground">{{ order.paymentStatus ?? '—' }}</TableCell>
            <TableCell class="whitespace-nowrap">{{ formatPrice(order.total) }}</TableCell>
            <TableCell class="text-muted-foreground">{{ order.email }}</TableCell>
            <TableCell>
              <Badge v-if="order.openFindings > 0" variant="warning">
                {{ order.openFindings }}
              </Badge>
              <span v-else class="text-muted-foreground">0</span>
            </TableCell>
          </TableRow>
        </TableBody>
      </Table>
    </div>
  </Card>
</template>
