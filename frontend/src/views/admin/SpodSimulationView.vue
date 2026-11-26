<script setup lang="ts">
import { onMounted, ref, shallowRef } from 'vue'
import { RefreshCw } from 'lucide-vue-next'
import { RouterLink } from 'vue-router'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { useToast } from '@/composables/useToast'
import { formatAdminStamp } from '@/lib/adminStamp'
import { fetchJson } from '@/lib/api'
import { orderNumber } from '@/lib/fulfillment'

const PATH = '/api/admin/production/spod-simulation'

/** A SPOD order of a staging destination, as the backend lists it. */
interface StagingSpodOrder {
  jobId: number
  orderId: number
  spodOrderId: string
  remoteState: string | null
  confirmedAt: string | null
  shippedAt: string | null
  createdAt: string
}

type SimulatedEvent = 'shipment-sent' | 'order-cancelled'

const EVENTS: { event: SimulatedEvent; label: string }[] = [
  { event: 'shipment-sent', label: 'Shipment sent' },
  { event: 'order-cancelled', label: 'Order cancelled' },
]

const { toast } = useToast()
const orders = ref<StagingSpodOrder[]>([])
const isLoading = shallowRef(false)
const error = shallowRef<string | null>(null)
/** `jobId:event` of the call that is running, so only its button waits. */
const running = shallowRef<string | null>(null)

async function reload() {
  isLoading.value = true
  error.value = null
  try {
    orders.value = await fetchJson<StagingSpodOrder[]>(PATH)
  } catch (err) {
    error.value = err instanceof Error ? err.message : 'The orders could not be loaded.'
  } finally {
    isLoading.value = false
  }
}

async function simulate(order: StagingSpodOrder, event: SimulatedEvent) {
  running.value = `${String(order.jobId)}:${event}`
  try {
    await fetchJson(`${PATH}/${String(order.jobId)}`, {
      method: 'POST',
      body: { event },
      responseType: 'void',
    })
    toast({
      title: `${event} requested for ${order.spodOrderId}`,
      description: 'Spreadconnect sends the webhook within seconds; reload to see the result.',
    })
  } catch (err) {
    toast({
      title: 'Simulation failed',
      description: err instanceof Error ? err.message : 'Unknown error',
      variant: 'destructive',
    })
  } finally {
    running.value = null
  }
}

function state(order: StagingSpodOrder): string {
  if (order.shippedAt !== null) return 'SHIPPED'
  return order.remoteState ?? (order.confirmedAt === null ? 'CREATED' : 'CONFIRMED')
}

onMounted(reload)
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader title="SPOD Simulation" breakpoint="lg">
      <template #actions>
        <Button variant="outline" size="sm" :disabled="isLoading" @click="reload">
          <RefreshCw :class="['size-4', isLoading && 'animate-spin']" />
          Reload
        </Button>
      </template>
    </AdminPageHeader>

    <p class="text-sm text-muted-foreground">
      SPOD orders of suppliers whose destination talks to the Spreadconnect staging installation. A
      simulation makes Spreadconnect send the webhook event as if it happened; the order at
      Spreadconnect itself does not change. "Needs action" cannot be simulated.
    </p>

    <Alert v-if="error" variant="destructive">{{ error }}</Alert>

    <Card
      v-else-if="orders.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      {{ isLoading ? 'Loading orders...' : 'No SPOD order of a staging destination.' }}
    </Card>

    <Card v-else class="overflow-hidden">
      <div class="overflow-x-auto">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Order</TableHead>
              <TableHead>Spreadconnect ID</TableHead>
              <TableHead>Created</TableHead>
              <TableHead>State</TableHead>
              <TableHead class="text-right">Simulate</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow v-for="order in orders" :key="order.jobId">
              <TableCell>
                <RouterLink
                  :to="`/admin/orders/${String(order.orderId)}`"
                  class="font-medium hover:underline"
                >
                  {{ orderNumber(order.orderId) }}
                </RouterLink>
                <span class="ml-1 text-muted-foreground">job {{ order.jobId }}</span>
              </TableCell>
              <TableCell class="font-mono">{{ order.spodOrderId }}</TableCell>
              <TableCell>{{ formatAdminStamp(order.createdAt) }}</TableCell>
              <TableCell
                ><Badge>{{ state(order) }}</Badge></TableCell
              >
              <TableCell class="space-x-2 text-right whitespace-nowrap">
                <Button
                  v-for="item in EVENTS"
                  :key="item.event"
                  variant="outline"
                  size="sm"
                  :disabled="running !== null"
                  :data-testid="`simulate-${item.event}-${order.jobId}`"
                  @click="simulate(order, item.event)"
                >
                  {{ running === `${order.jobId}:${item.event}` ? 'Sending...' : item.label }}
                </Button>
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </div>
    </Card>
  </section>
</template>
