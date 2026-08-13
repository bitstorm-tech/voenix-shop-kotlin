<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ShipJobDialog from '@/components/shared/ShipJobDialog.vue'
import SupplierJobCard from '@/components/supplier/SupplierJobCard.vue'
import { Alert } from '@/components/ui/alert'
import { Card } from '@/components/ui/card'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useToast } from '@/composables/useToast'
import { ApiError, type ApiFieldErrors } from '@/lib/api'
import { saveBlobAs } from '@/lib/download'
import {
  JobAlreadyShippedError,
  JobNotFoundError,
  JobNotReadyError,
  JobPdfUnavailableError,
  type PdfUnavailableCode,
  type ShipJobPayload,
  type SupplierJobStatus,
} from '@/lib/fulfillment'
import { type SupplierJob, useSupplierJobsStore } from '@/stores/supplier/jobs'

const jobsStore = useSupplierJobsStore()
const route = useRoute()
const router = useRouter()
const { toast } = useToast()

/** The tab is the URL: a reloaded or shared `?status=SHIPPED` shows the shipped list. */
const activeStatus = computed<SupplierJobStatus>(() =>
  route.query.status === 'SHIPPED' ? 'SHIPPED' : 'OPEN',
)

const shipDialogOpen = ref(false)
const jobToShip = ref<SupplierJob | null>(null)
const shipFieldErrors = ref<ApiFieldErrors>({})
const shipGeneralError = ref<string | null>(null)
/** A `409` describes the job, not the request — it is shown at the list, not inside the dialog. */
const listNotice = ref<string | null>(null)

const isShipping = computed(() => jobsStore.shippingJobId !== null)

const listError = computed(() =>
  jobsStore.error === null ? null : jobsStore.error.message || 'The jobs could not be loaded.',
)

const emptyMessage = computed(() =>
  activeStatus.value === 'OPEN'
    ? 'There is nothing to pack right now.'
    : 'No job has been shipped yet.',
)

const pdfUnavailableMessages: Record<PdfUnavailableCode, string> = {
  ARTIFACT_NOT_GENERATED: 'The production document has not been generated yet. Try again shortly.',
  ARTIFACT_MISSING:
    'The production document is not available any more. Please report this job to the shop.',
  ARTIFACT_DIGEST_MISMATCH:
    'The production document is not available. Please report this job to the shop.',
}

watch(
  activeStatus,
  (status) => {
    listNotice.value = null
    void jobsStore.fetchJobs(status)
  },
  { immediate: true },
)

function selectStatus(status: string) {
  if (status === activeStatus.value) {
    return
  }

  void router.replace({ query: { ...route.query, status } })
}

function openShipDialog(job: SupplierJob) {
  jobToShip.value = job
  shipFieldErrors.value = {}
  shipGeneralError.value = null
  listNotice.value = null
  shipDialogOpen.value = true
}

async function downloadPdf(job: SupplierJob) {
  try {
    const { blob, fileName } = await jobsStore.downloadPdf(job.jobId)
    saveBlobAs(blob, fileName)
  } catch (error) {
    toast({
      title: 'Failed to download the production document',
      description: downloadErrorMessage(error),
      variant: 'destructive',
    })
  }
}

function downloadErrorMessage(error: unknown): string {
  if (error instanceof JobPdfUnavailableError) {
    return pdfUnavailableMessages[error.code]
  }

  if (error instanceof JobNotFoundError) {
    return 'This job no longer exists.'
  }

  return error instanceof Error && error.message !== ''
    ? error.message
    : 'The production document could not be downloaded.'
}

async function shipJob(payload: ShipJobPayload) {
  const job = jobToShip.value
  if (job === null) {
    return
  }

  shipFieldErrors.value = {}
  shipGeneralError.value = null

  try {
    await jobsStore.ship(job.jobId, payload)
    shipDialogOpen.value = false
    jobToShip.value = null
    toast({ title: 'Marked as shipped', description: 'The customer has been notified by e-mail.' })
    await jobsStore.fetchJobs(activeStatus.value)
  } catch (error) {
    await handleShipError(error)
  }
}

/**
 * A rejected shipment splits three ways: the form was wrong (stay open, show the fields), the job
 * is in another state than the screen said (close, name the state, reload the tab), or something
 * unexpected happened (stay open, show the message).
 */
async function handleShipError(error: unknown) {
  if (error instanceof JobAlreadyShippedError) {
    await closeWithNotice('This job has already been shipped. The list has been reloaded.')
    return
  }

  if (error instanceof JobNotReadyError) {
    await closeWithNotice(
      'This job cannot be shipped before its production document has been generated. The list has been reloaded.',
    )
    return
  }

  if (error instanceof JobNotFoundError) {
    await closeWithNotice('This job no longer exists. The list has been reloaded.')
    return
  }

  if (error instanceof ApiError && error.status === 400) {
    shipFieldErrors.value = error.fieldErrors
    shipGeneralError.value =
      Object.keys(error.fieldErrors).length > 0 ? null : error.message || 'Invalid shipping data.'
    return
  }

  shipGeneralError.value =
    error instanceof Error && error.message !== ''
      ? error.message
      : 'The shipment could not be reported.'
}

async function closeWithNotice(message: string) {
  shipDialogOpen.value = false
  jobToShip.value = null
  listNotice.value = message
  await jobsStore.fetchJobs(activeStatus.value)
}
</script>

<template>
  <section class="space-y-4">
    <div>
      <h1 class="text-xl font-semibold text-foreground">Production jobs</h1>
      <p class="text-sm text-muted-foreground">
        Print the document, pack the items, and report the shipment.
      </p>
    </div>

    <Tabs :model-value="activeStatus" @update:model-value="selectStatus($event as string)">
      <TabsList>
        <TabsTrigger value="OPEN">Open</TabsTrigger>
        <TabsTrigger value="SHIPPED">Shipped</TabsTrigger>
      </TabsList>
    </Tabs>

    <Alert v-if="listNotice" variant="info">{{ listNotice }}</Alert>

    <Alert v-if="listError" variant="destructive">{{ listError }}</Alert>

    <Card
      v-else-if="jobsStore.isLoading"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      Loading production jobs...
    </Card>

    <Card
      v-else-if="jobsStore.jobs.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      {{ emptyMessage }}
    </Card>

    <div v-else class="space-y-3">
      <SupplierJobCard
        v-for="job in jobsStore.jobs"
        :key="job.jobId"
        :job="job"
        :downloading="jobsStore.downloadingJobId === job.jobId"
        :shipping="jobsStore.shippingJobId === job.jobId"
        @download="downloadPdf(job)"
        @ship="openShipDialog(job)"
      />
    </div>

    <ShipJobDialog
      v-model:open="shipDialogOpen"
      :job="jobToShip"
      :submitting="isShipping"
      :field-errors="shipFieldErrors"
      :general-error="shipGeneralError"
      @confirm="shipJob"
    />
  </section>
</template>
