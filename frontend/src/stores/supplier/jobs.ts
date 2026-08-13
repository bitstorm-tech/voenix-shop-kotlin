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
 * One production job of the signed-in supplier, mirroring the backend's `SupplierJobView` field by
 * field (`backend/modules/production/src/shop/voenix/production/fulfillment/SupplierJobView.kt`).
 *
 * `orderDate` is the ISO `yyyy-MM-dd` Berlin order date the PDF prints. `pdfAvailable` is `false`
 * while the document is still being generated — the job is listed anyway, because a job that cannot
 * produce its PDF has to be visible rather than silently absent. The three shipping fields are
 * `null` until the job is shipped.
 */
export interface SupplierJob {
  jobId: number
  orderId: number
  orderDate: string
  customerFirstName: string
  customerLastName: string
  shippingStreet: string
  shippingHouseNumber: string
  shippingPostalCode: string
  shippingCity: string
  shippingCountry: string
  items: SupplierJobItem[]
  pdfAvailable: boolean
  shippedAt: string | null
  shippingCarrier: string | null
  trackingNumber: string | null
}

/** Who the signed-in supplier login acts for, as `GET /api/supplier/me` answers it. */
export interface SupplierIdentity {
  supplierId: number
  supplierName: string
}

export const useSupplierJobsStore = defineStore('supplier-jobs', () => {
  const identity = shallowRef<SupplierIdentity | null>(null)
  const jobs = ref<SupplierJob[]>([])
  /** The status the current {@link jobs} were loaded for, or `null` before the first load. */
  const loadedStatus = shallowRef<SupplierJobStatus | null>(null)
  const isLoading = shallowRef(false)
  /** The typed failure of the last list load. The view decides how to word it. */
  const error = shallowRef<Error | null>(null)
  const shippingJobId = shallowRef<number | null>(null)
  const downloadingJobId = shallowRef<number | null>(null)
  /**
   * The number of the most recently issued list request. Switching tabs starts a second load before
   * the first has answered, and the slower answer must not land: only the request whose number is
   * still the current one may write `jobs`, `loadedStatus`, `error` and `isLoading`.
   */
  let latestFetchId = 0

  /**
   * Who this login acts for. The layout asks once for its header; the answer is not repeated on
   * every job row.
   */
  async function fetchIdentity(): Promise<SupplierIdentity | null> {
    try {
      identity.value = await fetchJson<SupplierIdentity>('/api/supplier/me')
    } catch {
      // A header without a supplier name is a cosmetic loss: the route protection has already
      // decided that this login may be here, so nothing is gated on this answer.
      identity.value = null
    }

    return identity.value
  }

  /**
   * The jobs of one tab. The backend answers a bare array.
   *
   * An overtaken request still resolves, but writes nothing: its result is returned to its own
   * caller and the store keeps showing what the newest request asked for.
   */
  async function fetchJobs(status: SupplierJobStatus): Promise<SupplierJob[]> {
    const fetchId = ++latestFetchId
    isLoading.value = true
    error.value = null

    try {
      const loaded = await fetchJson<SupplierJob[]>(
        `/api/supplier/production-jobs?status=${status}`,
      )

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
   * Reports a shipment. The answer is the updated job, but the list is not patched from it: the row
   * belongs to the other tab afterwards, so the caller refetches the tab it is looking at.
   */
  async function ship(jobId: number, payload: ShipJobPayload = {}): Promise<SupplierJob> {
    shippingJobId.value = jobId

    try {
      return await fetchJson<SupplierJob>(`/api/supplier/production-jobs/${jobId}/ship`, {
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
      const blob = await fetchJson<Blob>(`/api/supplier/production-jobs/${jobId}/pdf`, {
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
    identity,
    jobs,
    loadedStatus,
    isLoading,
    error,
    shippingJobId,
    downloadingJobId,
    fetchIdentity,
    fetchJobs,
    ship,
    downloadPdf,
  }
})
