<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RefreshCw } from 'lucide-vue-next'
import AdminJobsTable from '@/components/admin/logistics/AdminJobsTable.vue'
import AdminPageHeader from '@/components/admin/shared/AdminPageHeader.vue'
import ShipJobDialog from '@/components/shared/ShipJobDialog.vue'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { useToast } from '@/composables/useToast'
import { ApiError, type ApiFieldErrors } from '@/lib/api'
import { saveBlobAs } from '@/lib/download'
import { type AdminJob, useAdminFulfillmentStore } from '@/stores/admin/fulfillment'
import { useAdminSuppliersStore } from '@/stores/admin/suppliers'
import {
  JobAlreadyShippedError,
  JobNotFoundError,
  JobNotReadyError,
  JobPdfUnavailableError,
  type PdfUnavailableCode,
  type ShipJobPayload,
  type SupplierJobStatus,
} from '@/stores/supplier/jobs'

const fulfillmentStore = useAdminFulfillmentStore()
const suppliersStore = useAdminSuppliersStore()
const { toast } = useToast()

/** The value the supplier filter carries while it filters nothing. The wire has no id then. */
const ALL_SUPPLIERS = 'all'

const activeStatus = ref<SupplierJobStatus>('OPEN')
const supplierFilter = ref<string>(ALL_SUPPLIERS)

const shipDialogOpen = ref(false)
const jobToShip = ref<AdminJob | null>(null)
const shipFieldErrors = ref<ApiFieldErrors>({})
const shipGeneralError = ref<string | null>(null)
/** A `409` describes the job, not the request — it is shown at the list, not inside the dialog. */
const listNotice = ref<string | null>(null)

const selectedSupplierId = computed<number | null>(() =>
  supplierFilter.value === ALL_SUPPLIERS ? null : Number(supplierFilter.value),
)

const isShipping = computed(() => fulfillmentStore.shippingJobId !== null)

const listError = computed(() =>
  fulfillmentStore.error === null
    ? null
    : fulfillmentStore.error.message || 'The production jobs could not be loaded.',
)

const emptyMessage = computed(() =>
  activeStatus.value === 'OPEN'
    ? 'No production job is waiting to be shipped.'
    : 'No production job has been shipped yet.',
)

const shipSupplierName = computed(() => jobToShip.value?.supplier.name ?? null)

const pdfUnavailableMessages: Record<PdfUnavailableCode, string> = {
  ARTIFACT_NOT_GENERATED: 'The production document has not been generated yet. Try again shortly.',
  ARTIFACT_MISSING: 'The production document is not available any more.',
  ARTIFACT_DIGEST_MISMATCH:
    'The stored production document does not match its recorded checksum and was not handed out.',
}

function reload() {
  listNotice.value = null
  void fulfillmentStore.fetchJobs(activeStatus.value, selectedSupplierId.value)
}

watch([activeStatus, selectedSupplierId], reload)

onMounted(async () => {
  reload()
  // The filter needs supplier names, and only the names — the list itself already carries the
  // supplier of every row.
  await suppliersStore.fetchSuppliers()
})

function selectStatus(status: string) {
  activeStatus.value = status === 'SHIPPED' ? 'SHIPPED' : 'OPEN'
}

function openShipDialog(job: AdminJob) {
  jobToShip.value = job
  shipFieldErrors.value = {}
  shipGeneralError.value = null
  listNotice.value = null
  shipDialogOpen.value = true
}

async function downloadPdf(job: AdminJob) {
  try {
    const { blob, fileName } = await fulfillmentStore.downloadPdf(job.jobId)
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
    await fulfillmentStore.ship(job.jobId, payload)
    shipDialogOpen.value = false
    jobToShip.value = null
    toast({
      title: 'Marked as shipped',
      description: 'The customer has been notified by e-mail.',
    })
    await fulfillmentStore.fetchJobs(activeStatus.value, selectedSupplierId.value)
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
  await fulfillmentStore.fetchJobs(activeStatus.value, selectedSupplierId.value)
}
</script>

<template>
  <section class="space-y-4">
    <AdminPageHeader title="Logistics" breakpoint="lg">
      <template #actions>
        <Button variant="outline" size="sm" :disabled="fulfillmentStore.isLoading" @click="reload">
          <RefreshCw :class="['size-4', fulfillmentStore.isLoading && 'animate-spin']" />
          Reload
        </Button>
      </template>
    </AdminPageHeader>

    <p class="text-sm text-muted-foreground">
      Every supplier's production jobs. Download a document, or report a shipment on a supplier's
      behalf — the customer is notified either way.
    </p>

    <div class="flex flex-wrap items-end justify-between gap-4">
      <Tabs :model-value="activeStatus" @update:model-value="selectStatus($event as string)">
        <TabsList>
          <TabsTrigger value="OPEN">Open</TabsTrigger>
          <TabsTrigger value="SHIPPED">Shipped</TabsTrigger>
        </TabsList>
      </Tabs>

      <div class="w-full space-y-2 sm:w-64">
        <Label for="logistics-supplier-filter">Supplier</Label>
        <Select v-model="supplierFilter">
          <SelectTrigger id="logistics-supplier-filter">
            <SelectValue placeholder="All suppliers" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem :value="ALL_SUPPLIERS">All suppliers</SelectItem>
            <SelectItem
              v-for="supplier in suppliersStore.suppliers"
              :key="supplier.id"
              :value="String(supplier.id)"
            >
              {{ supplier.name }}
            </SelectItem>
          </SelectContent>
        </Select>
      </div>
    </div>

    <Alert v-if="listNotice" variant="info">{{ listNotice }}</Alert>

    <Alert v-if="listError" variant="destructive">{{ listError }}</Alert>

    <Card
      v-else-if="fulfillmentStore.isLoading && fulfillmentStore.jobs.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      Loading production jobs...
    </Card>

    <Card
      v-else-if="fulfillmentStore.jobs.length === 0"
      class="px-4 py-12 text-center text-sm text-muted-foreground"
    >
      {{ emptyMessage }}
    </Card>

    <AdminJobsTable
      v-else
      :jobs="fulfillmentStore.jobs"
      :downloading-job-id="fulfillmentStore.downloadingJobId"
      :shipping-job-id="fulfillmentStore.shippingJobId"
      @download="downloadPdf"
      @ship="openShipDialog"
    />

    <ShipJobDialog
      v-model:open="shipDialogOpen"
      :job="jobToShip"
      :supplier-name="shipSupplierName"
      :submitting="isShipping"
      :field-errors="shipFieldErrors"
      :general-error="shipGeneralError"
      @confirm="shipJob"
    />
  </section>
</template>
