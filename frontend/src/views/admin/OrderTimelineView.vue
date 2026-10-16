<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { CreditCard, RefreshCw } from 'lucide-vue-next'
import OperationsFindingsTable from '@/components/admin/operations/OperationsFindingsTable.vue'
import AdminProductionPdfSection from '@/components/admin/orders/AdminProductionPdfSection.vue'
import OrderTimelineOrder from '@/components/admin/orders/OrderTimelineOrder.vue'
import OrderTimelineProduction from '@/components/admin/orders/OrderTimelineProduction.vue'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import { Alert } from '@/components/ui/alert'
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
import { formatAdminStamp } from '@/lib/adminStamp'
import { formatPrice } from '@/lib/formatPrice'
import { orderNumber } from '@/lib/fulfillment'
import { OrderNotFoundError, type OrderEvent, useAdminOrdersStore } from '@/stores/admin/orders'

const props = defineProps<{ orderId: number }>()

const ordersStore = useAdminOrdersStore()

const timeline = computed(() => ordersStore.timeline)

const timelineError = computed(() => {
  const error = ordersStore.timelineError
  if (error === null) return null
  if (error instanceof OrderNotFoundError) return 'No order exists for this ID.'
  return error.message || 'The order could not be loaded.'
})

/** The open findings in the shape of the operations table, which links each to its order. */
const findings = computed(() =>
  (timeline.value?.findings ?? []).map((finding) => ({ ...finding, orderId: props.orderId })),
)

/** Who moved the order: the actor type, and the user behind an admin or supplier event. */
function actor(event: OrderEvent): string {
  return event.actorId === null ? event.actorType : `${event.actorType} #${event.actorId}`
}

/** The few bounded values of an event, as `key: value` pairs. */
function details(event: OrderEvent): string {
  return Object.entries(event.details ?? {})
    .map(([key, value]) => `${key}: ${value ?? '—'}`)
    .join(', ')
}

function recheckPayment() {
  void ordersStore.recheckPayment(props.orderId)
}

function reload() {
  void ordersStore.fetchTimeline(props.orderId)
  void ordersStore.fetchProductionPdfs(props.orderId)
}

onMounted(() => {
  // A recheck answer belongs to the order it was asked for, not to the next one opened.
  ordersStore.recheck = null
  ordersStore.recheckError = null
  reload()
})
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader :title="orderNumber(orderId)" breakpoint="lg">
      <template #actions>
        <!-- Asks Mollie now, the way the webhook would have; recorded with the admin's user id. -->
        <Button
          variant="outline"
          size="sm"
          :disabled="ordersStore.isRechecking"
          data-testid="recheck-payment"
          @click="recheckPayment"
        >
          <CreditCard class="size-4" />
          {{ ordersStore.isRechecking ? 'Asking Mollie...' : 'Recheck payment' }}
        </Button>
        <Button
          variant="outline"
          size="sm"
          :disabled="ordersStore.isTimelineLoading"
          @click="reload"
        >
          <RefreshCw :class="['size-4', ordersStore.isTimelineLoading && 'animate-spin']" />
          Reload
        </Button>
      </template>
    </AdminPageHeader>

    <Alert v-if="ordersStore.recheck" variant="info" data-testid="recheck-result">
      Mollie was asked: the order is {{ ordersStore.recheck.status }}, the payment
      {{ ordersStore.recheck.paymentStatus }}.
    </Alert>
    <Alert v-if="ordersStore.recheckError" variant="destructive" data-testid="recheck-error">
      {{ ordersStore.recheckError.message || 'The payment could not be rechecked.' }}
    </Alert>

    <Alert v-if="timelineError" variant="destructive">{{ timelineError }}</Alert>

    <Card
      v-else-if="timeline === null"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      Loading the order...
    </Card>

    <template v-else>
      <OrderTimelineOrder :order="timeline.order" />

      <h2 class="pt-2 text-lg font-semibold">Payments</h2>
      <Card v-if="timeline.payments.length === 0" class="p-4 text-sm text-muted-foreground">
        No payment was started.
      </Card>
      <Card v-else class="overflow-x-auto">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Payment</TableHead>
              <TableHead>Mollie id</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Amount</TableHead>
              <TableHead>Started</TableHead>
              <TableHead>Last changed</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow
              v-for="payment in timeline.payments"
              :key="payment.paymentId"
              data-testid="timeline-payment"
            >
              <TableCell>#{{ payment.paymentId }}</TableCell>
              <TableCell class="font-mono text-xs">{{ payment.molliePaymentId }}</TableCell>
              <TableCell>{{ payment.status }}</TableCell>
              <TableCell class="whitespace-nowrap">{{ formatPrice(payment.amount) }}</TableCell>
              <TableCell class="whitespace-nowrap text-muted-foreground">
                {{ formatAdminStamp(payment.createdAt) }}
              </TableCell>
              <TableCell class="whitespace-nowrap text-muted-foreground">
                {{ formatAdminStamp(payment.updatedAt) }}
              </TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </Card>

      <h2 class="pt-2 text-lg font-semibold">Production</h2>
      <OrderTimelineProduction :production="timeline.production" />

      <h2 class="pt-2 text-lg font-semibold">Mails</h2>
      <Card v-if="timeline.emails.length === 0" class="p-4 text-sm text-muted-foreground">
        No mail was queued for this order.
      </Card>
      <Card v-else class="overflow-x-auto">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Mail</TableHead>
              <TableHead>Recipient</TableHead>
              <TableHead>Queued</TableHead>
              <TableHead>Sent</TableHead>
              <TableHead>Attempts</TableHead>
              <TableHead>Error code</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow
              v-for="email in timeline.emails"
              :key="email.emailJobId"
              data-testid="timeline-email"
            >
              <TableCell>{{ email.kind }}</TableCell>
              <TableCell class="text-muted-foreground">{{ email.recipient }}</TableCell>
              <TableCell class="whitespace-nowrap text-muted-foreground">
                {{ formatAdminStamp(email.createdAt) }}
              </TableCell>
              <TableCell class="whitespace-nowrap text-muted-foreground">
                {{ formatAdminStamp(email.sentAt) ?? 'Not yet' }}
              </TableCell>
              <TableCell class="text-muted-foreground">{{ email.attemptCount }}</TableCell>
              <TableCell class="text-muted-foreground">{{ email.lastErrorCode ?? '—' }}</TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </Card>

      <h2 class="pt-2 text-lg font-semibold">History</h2>
      <Card v-if="timeline.events.length === 0" class="p-4 text-sm text-muted-foreground">
        No event was recorded for this order.
      </Card>
      <Card v-else class="overflow-x-auto">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>When</TableHead>
              <TableHead>Event</TableHead>
              <TableHead>Actor</TableHead>
              <TableHead>Cause</TableHead>
              <TableHead>Details</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow v-for="event in timeline.events" :key="event.id" data-testid="timeline-event">
              <TableCell class="whitespace-nowrap text-muted-foreground">
                {{ formatAdminStamp(event.occurredAt) }}
              </TableCell>
              <TableCell class="font-medium">{{ event.kind }}</TableCell>
              <TableCell>{{ actor(event) }}</TableCell>
              <TableCell class="text-muted-foreground">{{ event.cause ?? '—' }}</TableCell>
              <TableCell class="text-muted-foreground">{{ details(event) || '—' }}</TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </Card>

      <h2 class="pt-2 text-lg font-semibold">Open findings</h2>
      <Card v-if="findings.length === 0" class="p-4 text-sm text-muted-foreground">
        Nothing about this order is stuck.
      </Card>
      <OperationsFindingsTable v-else :findings="findings" />
    </template>

    <h2 class="pt-2 text-lg font-semibold">Production documents</h2>
    <AdminProductionPdfSection />
  </section>
</template>
