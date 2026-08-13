import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { fetchJson } from '@/lib/api'
import {
  jobPdfFileName,
  type ShipJobPayload,
  type SupplierJobItem,
  type SupplierJobStatus,
  toPdfError,
  toShipBody,
  toShipError,
} from '@/lib/fulfillment'

/**
 * One production job as an administrator sees it, mirroring the backend's `AdminJobView`
 * (`backend/modules/production/src/shop/voenix/production/fulfillment/AdminJobView.kt`).
 *
 * It is the supplier's view of a job plus the two things only an operator needs: whose job it is,
 * and how the document generation is doing. `generationAttemptCount` and `lastGenerationErrorCode`
 * are the reason un-generated jobs are listed at all — a job that never produced its PDF has to be
 * diagnosable instead of merely late.
 *
 * The type is written out rather than derived from `SupplierJob`: the two are separate contracts on
 * the backend as well, and a shared base would invite widening one of them to serve both.
 */
export interface AdminJob {
  jobId: number
  orderId: number
  orderDate: string
  supplier: AdminJobSupplier
  customerFirstName: string
  customerLastName: string
  shippingStreet: string
  shippingHouseNumber: string
  shippingPostalCode: string
  shippingCity: string
  shippingCountry: string
  items: SupplierJobItem[]
  pdfAvailable: boolean
  generationAttemptCount: number
  lastGenerationErrorCode: string | null
  shippedAt: string | null
  shippedByUserId: number | null
  shippingCarrier: string | null
  trackingNumber: string | null
}

/**
 * The supplier a job belongs to. `name` is `null` only when the supplier module no longer knows the
 * id — the row still names its supplier rather than dropping out of the list.
 */
export interface AdminJobSupplier {
  id: number
  name: string | null
}

/** The filter of the list: one supplier, or `null` for every supplier. */
export type AdminJobSupplierFilter = number | null

function jobsPath(status: SupplierJobStatus, supplierId: AdminJobSupplierFilter): string {
  // An absent `supplierId` means "every supplier". It is left out rather than sent empty: the
  // backend rejects a present but unusable id with `400`, which is the right answer for a typo and
  // the wrong one for "no filter".
  const supplierQuery = supplierId === null ? '' : `&supplierId=${supplierId}`
  return `/api/admin/production/jobs?status=${status}${supplierQuery}`
}

export const useAdminFulfillmentStore = defineStore('admin-fulfillment', () => {
  const jobs = ref<AdminJob[]>([])
  /** The status the current {@link jobs} were loaded for, or `null` before the first load. */
  const loadedStatus = shallowRef<SupplierJobStatus | null>(null)
  const isLoading = shallowRef(false)
  /** The failure of the last list load. The view decides how to word it. */
  const error = shallowRef<Error | null>(null)
  const shippingJobId = shallowRef<number | null>(null)
  const downloadingJobId = shallowRef<number | null>(null)
  /**
   * The number of the most recently issued list request. Switching the tab or the supplier filter
   * starts a second load before the first has answered, and the slower answer must not land: only
   * the request whose number is still the current one may write `jobs`, `loadedStatus`, `error` and
   * `isLoading`.
   */
  let latestFetchId = 0

  /**
   * The jobs of one tab, optionally narrowed to one supplier. The backend answers a bare array.
   *
   * An overtaken request still resolves, but writes nothing: its result is returned to its own
   * caller and the store keeps showing what the newest request asked for.
   */
  async function fetchJobs(
    status: SupplierJobStatus,
    supplierId: AdminJobSupplierFilter = null,
  ): Promise<AdminJob[]> {
    const fetchId = ++latestFetchId
    isLoading.value = true
    error.value = null

    try {
      const loaded = await fetchJson<AdminJob[]>(jobsPath(status, supplierId))

      if (fetchId === latestFetchId) {
        jobs.value = loaded
        loadedStatus.value = status
        isLoading.value = false
      }

      return loaded
    } catch (err) {
      if (fetchId === latestFetchId) {
        jobs.value = []
        loadedStatus.value = status
        error.value = err instanceof Error ? err : new Error('Unknown error')
        isLoading.value = false
      }

      return []
    }
  }

  /**
   * Reports a shipment on a supplier's behalf. Same write, same refusals as the supplier's own
   * route, so the error mapping of `lib/fulfillment.ts` is reused rather than copied. The
   * answer is not patched into the list: the row belongs to the other tab afterwards, so the caller
   * refetches the tab it is looking at.
   */
  async function ship(jobId: number, payload: ShipJobPayload = {}): Promise<AdminJob> {
    shippingJobId.value = jobId

    try {
      return await fetchJson<AdminJob>(`/api/admin/production/jobs/${jobId}/ship`, {
        method: 'POST',
        body: toShipBody(payload),
      })
    } catch (err) {
      throw toShipError(err)
    } finally {
      shippingJobId.value = null
    }
  }

  /** Downloads a job's document as a blob, together with the name it should be saved under. */
  async function downloadPdf(jobId: number): Promise<{ blob: Blob; fileName: string }> {
    downloadingJobId.value = jobId

    try {
      const job = jobs.value.find((candidate) => candidate.jobId === jobId)
      const blob = await fetchJson<Blob>(`/api/admin/production/jobs/${jobId}/pdf`, {
        responseType: 'blob',
      })
      return { blob, fileName: job ? jobPdfFileName(job.orderId) : `job-${jobId}.pdf` }
    } catch (err) {
      throw toPdfError(err)
    } finally {
      downloadingJobId.value = null
    }
  }

  return {
    jobs,
    loadedStatus,
    isLoading,
    error,
    shippingJobId,
    downloadingJobId,
    fetchJobs,
    ship,
    downloadPdf,
  }
})
