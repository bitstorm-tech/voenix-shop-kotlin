<script setup lang="ts">
import { computed } from 'vue'
import { Download, Truck } from 'lucide-vue-next'
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
import type { AdminJob } from '@/stores/admin/fulfillment'
import { orderNumber, SHIPPING_CARRIER_LABELS, type ShippingCarrier } from '@/lib/fulfillment'

interface Props {
  jobs: AdminJob[]
  downloadingJobId?: number | null
  shippingJobId?: number | null
}

const props = withDefaults(defineProps<Props>(), {
  downloadingJobId: null,
  shippingJobId: null,
})

const emit = defineEmits<{
  (event: 'download', job: AdminJob): void
  (event: 'ship', job: AdminJob): void
}>()

/** The channel that produces its jobs through a partner API instead of an uploaded document. */
const CHANNEL_SPOD = 'SPOD'

interface Row {
  job: AdminJob
  customerName: string
  supplierName: string
  /** What the shipped line says, or `null` while the job is still open. */
  shippedNote: string | null
  /** Why a job has no document yet, worded for an operator. `null` once the PDF exists. */
  generationNote: string | null
  /** The partner's order id and last reported state, or `null` on a job without a remote order. */
  remoteNote: string | null
  /** Whether this job's document is something to wait for at all. */
  expectsPdf: boolean
}

const rows = computed<Row[]>(() =>
  props.jobs.map((job) => ({
    job,
    customerName: `${job.customerFirstName} ${job.customerLastName}`.trim(),
    // A job whose supplier the supplier module no longer knows still names its id, so the row stays
    // identifiable instead of showing an empty cell.
    supplierName: job.supplier.name ?? `Supplier #${job.supplier.id}`,
    shippedNote: shippedNote(job),
    // A SPOD job never produces a PDF, so "not generated yet" would be a permanent false alarm.
    generationNote: expectsPdf(job) && !job.pdfAvailable ? generationNote(job) : null,
    remoteNote: remoteNote(job),
    expectsPdf: expectsPdf(job),
  })),
)

function expectsPdf(job: AdminJob): boolean {
  return job.fulfillmentChannel !== CHANNEL_SPOD
}

/**
 * What the channel knows about the job at the partner: its order id, and the last state reported
 * for it. Both are `null` on a job this shop produces itself, and the line is then left out.
 */
function remoteNote(job: AdminJob): string | null {
  const parts = []
  if (job.externalReference !== null) parts.push(job.externalReference)
  if (job.remoteState !== null) parts.push(job.remoteState)

  return parts.length === 0 ? null : parts.join(' · ')
}

/**
 * The shipped line names who reported the shipment, because the two reporters mean different
 * things to an operator: a person clicked "Mark as shipped" here, or the channel told us. The
 * carrier the channel sent verbatim is shown next to the bounded one when the two are not the same
 * word — it is the string the partner used, not one this shop maps.
 */
function shippedNote(job: AdminJob): string | null {
  if (job.shippedAt === null) {
    return null
  }

  const carrier = job.shippingCarrier
  const carrierLabel =
    carrier === null ? null : (SHIPPING_CARRIER_LABELS[carrier as ShippingCarrier] ?? carrier)
  const parts = [job.shippedAt.slice(0, 10)]
  if (carrierLabel !== null) parts.push(carrierLabel)
  if (job.shippingCarrierReported !== null && job.shippingCarrierReported !== carrierLabel) {
    parts.push(`reported as ${job.shippingCarrierReported}`)
  }
  if (job.trackingNumber !== null) parts.push(job.trackingNumber)
  parts.push(
    job.shippedByChannel === null ? 'reported manually' : `reported by ${job.shippedByChannel}`,
  )

  return parts.join(' · ')
}

/**
 * The whole reason un-generated jobs are listed: an operator has to see whether a job is merely
 * young or stuck, and on what. The error code is shown verbatim — it is a backend code, not copy.
 */
function generationNote(job: AdminJob): string {
  const attempts = `${job.generationAttemptCount} attempt${job.generationAttemptCount === 1 ? '' : 's'}`

  return job.lastGenerationErrorCode === null
    ? `${attempts}, no error reported`
    : `${attempts}, last error ${job.lastGenerationErrorCode}`
}
</script>

<template>
  <Card class="overflow-hidden">
    <div class="overflow-x-auto">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Order</TableHead>
            <TableHead>Channel</TableHead>
            <TableHead>Supplier</TableHead>
            <TableHead>Customer</TableHead>
            <TableHead>Status</TableHead>
            <TableHead class="text-right">Actions</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <TableRow
            v-for="{
              job,
              customerName,
              supplierName,
              shippedNote,
              generationNote,
              remoteNote,
              expectsPdf,
            } in rows"
            :key="job.jobId"
          >
            <TableCell class="whitespace-nowrap text-foreground">
              <p class="font-medium">{{ orderNumber(job.orderId) }}</p>
              <p class="text-sm text-muted-foreground">{{ job.orderDate }}</p>
            </TableCell>
            <TableCell class="min-w-40">
              <Badge variant="muted" :data-testid="`job-channel-${job.jobId}`">
                {{ job.fulfillmentChannel }}
              </Badge>
              <p
                v-if="remoteNote"
                class="mt-1 text-sm text-muted-foreground"
                :data-testid="`job-remote-note-${job.jobId}`"
              >
                {{ remoteNote }}
              </p>
            </TableCell>
            <TableCell class="min-w-40 text-muted-foreground">{{ supplierName }}</TableCell>
            <TableCell class="min-w-40 text-muted-foreground">
              <p class="text-foreground">{{ customerName }}</p>
              <p class="text-sm">{{ job.shippingPostalCode }} {{ job.shippingCity }}</p>
            </TableCell>
            <TableCell class="min-w-48">
              <Badge v-if="job.shippedAt" variant="success">Shipped</Badge>
              <Badge v-else-if="expectsPdf && !job.pdfAvailable" variant="warning">
                PDF in preparation
              </Badge>
              <Badge v-else variant="muted">Open</Badge>
              <p v-if="shippedNote" class="mt-1 text-sm text-muted-foreground">
                {{ shippedNote }}
              </p>
              <p v-if="generationNote" class="mt-1 text-sm text-muted-foreground">
                {{ generationNote }}
              </p>
            </TableCell>
            <TableCell class="whitespace-nowrap text-right">
              <div class="flex flex-wrap items-center justify-end gap-2">
                <!-- A SPOD job has no document at all, so it gets no download button to disable. -->
                <Button
                  v-if="expectsPdf"
                  variant="outline"
                  size="sm"
                  :disabled="!job.pdfAvailable || downloadingJobId === job.jobId"
                  @click="emit('download', job)"
                >
                  <Download class="size-4" />
                  {{ downloadingJobId === job.jobId ? 'Downloading...' : 'PDF' }}
                </Button>
                <!--
                  Readiness of a channel-produced job is not in this answer, so the button stays
                  enabled and the backend refuses a job that is not prepared yet - the view shows
                  that refusal and reloads.
                -->
                <Button
                  v-if="!job.shippedAt"
                  size="sm"
                  :disabled="(expectsPdf && !job.pdfAvailable) || shippingJobId === job.jobId"
                  @click="emit('ship', job)"
                >
                  <Truck class="size-4" />
                  Mark as shipped
                </Button>
              </div>
            </TableCell>
          </TableRow>
        </TableBody>
      </Table>
    </div>
  </Card>
</template>
