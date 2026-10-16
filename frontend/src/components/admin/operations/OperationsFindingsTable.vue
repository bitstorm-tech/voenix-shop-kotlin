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
import { orderNumber } from '@/lib/fulfillment'
import type { FindingKind, OperationsFinding } from '@/stores/admin/operations'

interface Props {
  findings: OperationsFinding[]
  /** Whether the list is the resolved one, which adds the column of when a finding resolved. */
  resolved?: boolean
}

withDefaults(defineProps<Props>(), { resolved: false })

/** What each kind means, worded for an operator; the code itself is shown underneath. */
const KIND_LABELS: Record<FindingKind, string> = {
  PAYMENT_UNRESOLVED: 'Payment unresolved for over an hour',
  PAYMENT_PAID_ORDER_UNPAID: 'Payment paid, order not paid',
  PRODUCTION_REQUEST_STALLED: 'Production request not split',
  JOB_GENERATION_STALLED: 'Production document not generated',
  DELIVERY_STALLED: 'Document not delivered',
  SPOD_SUBMISSION_STALLED: 'SPOD order not submitted',
  SPOD_QUARANTINED: 'SPOD job quarantined',
  SPOD_REMOTE_ATTENTION: 'SPOD order needs attention',
  SHIPMENT_OVERDUE: 'Not shipped for seven days',
  EMAIL_STALLED: 'Mail not sent',
}

function rowKey(finding: OperationsFinding): string {
  return `${finding.kind}-${finding.subject.type}-${finding.subject.id}-${finding.firstSeenAt}`
}
</script>

<template>
  <Card class="overflow-hidden">
    <div class="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Kind</TableHead>
            <TableHead>Subject</TableHead>
            <TableHead>Order</TableHead>
            <TableHead>Since</TableHead>
            <TableHead>Attempts</TableHead>
            <TableHead>Error code</TableHead>
            <TableHead>Alerted</TableHead>
            <TableHead v-if="resolved">Resolved</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <TableRow v-for="finding in findings" :key="rowKey(finding)" data-testid="finding-row">
            <TableCell class="min-w-48">
              <p class="font-medium text-foreground">{{ KIND_LABELS[finding.kind] }}</p>
              <p class="text-sm text-muted-foreground">{{ finding.kind }}</p>
            </TableCell>
            <TableCell class="whitespace-nowrap">
              <Badge variant="muted">{{ finding.subject.type }}</Badge>
              #{{ finding.subject.id }}
            </TableCell>
            <TableCell class="whitespace-nowrap">
              <!-- The order list until the order timeline page exists. -->
              <RouterLink
                v-if="finding.orderId !== null"
                :to="{ name: 'admin-orders' }"
                class="font-medium text-primary underline-offset-4 hover:underline"
                data-testid="finding-order-link"
              >
                {{ orderNumber(finding.orderId) }}
              </RouterLink>
              <span v-else class="text-muted-foreground">—</span>
            </TableCell>
            <TableCell class="whitespace-nowrap text-muted-foreground">
              {{ formatAdminStamp(finding.since) }}
            </TableCell>
            <TableCell class="text-muted-foreground">{{ finding.attemptCount ?? '—' }}</TableCell>
            <TableCell class="text-muted-foreground">{{ finding.lastErrorCode ?? '—' }}</TableCell>
            <TableCell class="whitespace-nowrap text-muted-foreground">
              {{ formatAdminStamp(finding.alertedAt) ?? 'No' }}
            </TableCell>
            <TableCell v-if="resolved" class="whitespace-nowrap text-muted-foreground">
              {{ formatAdminStamp(finding.resolvedAt) }}
            </TableCell>
          </TableRow>
        </TableBody>
      </Table>
    </div>
  </Card>
</template>
