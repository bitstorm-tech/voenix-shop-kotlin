import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchJson } from '@/lib/api'

/**
 * One packing line of a job, exactly as `SupplierJobView.items` sends it.
 *
 * There is no price and no article id in it: a supplier packs what the PDF prints, and what the
 * customer paid is none of the packing station's business. The list stays empty while the job's
 * document is still being generated.
 */
export interface SupplierJobItem {
  articleName: string
  variantName: string
  supplierArticleNumber: string | null
  quantity: number
}

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

/**
 * The little a shipment report needs to know about the job it is reporting: the order it belongs
 * to, which is what the dialog puts in its title. Both the supplier view of a job and the admin
 * view of one satisfy it, which is how the two surfaces share one dialog.
 */
export interface ShippableJob {
  orderId: number
}

/** Who the signed-in supplier login acts for, as `GET /api/supplier/me` answers it. */
export interface SupplierIdentity {
  supplierId: number
  supplierName: string
}

/**
 * The two lists the surface has: what still has to go out, and what already went. The status is
 * derived from the shipping timestamp on the backend, so there is no third state to land in.
 */
export type SupplierJobStatus = 'OPEN' | 'SHIPPED'

/**
 * The carriers a shipment may be reported with. The list is bounded on purpose (decision J2 of
 * issue #119): the tracking link of the customer's notification mail is built on the server from
 * this name, so a client can never supply a URL. `OTHER` is the honest end of the list — the
 * shipment has a number and the shop has no page to point at.
 */
export const SHIPPING_CARRIERS = [
  'DHL',
  'DPD',
  'GLS',
  'HERMES',
  'UPS',
  'DEUTSCHE_POST',
  'OTHER',
] as const

export type ShippingCarrier = (typeof SHIPPING_CARRIERS)[number]

/** How a carrier is named on screen. The backend prints its own wording in the customer's mail. */
export const SHIPPING_CARRIER_LABELS: Record<ShippingCarrier, string> = {
  DHL: 'DHL',
  DPD: 'DPD',
  GLS: 'GLS',
  HERMES: 'Hermes',
  UPS: 'UPS',
  DEUTSCHE_POST: 'Deutsche Post',
  OTHER: 'Other',
}

/**
 * What a supplier optionally knows about the package it just handed over. Both fields are optional
 * and independent — the endpoint still requires a JSON body, so an empty report is sent as `{}`.
 */
export interface ShipJobPayload {
  carrier?: ShippingCarrier | null
  trackingNumber?: string | null
}

/**
 * An unknown job id — and a job of another supplier, which answers exactly the same `404`. The two
 * are indistinguishable by design, so this error must never be worded as "someone else's job".
 */
export class JobNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'JobNotFoundError'
  }
}

/** `409 ALREADY_SHIPPED`: the job is already on its way. Shipping is not repeatable. */
export class JobAlreadyShippedError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'JobAlreadyShippedError'
  }
}

/** `409 NOT_READY`: the job's document does not exist yet, so it cannot be shipped (decision J1). */
export class JobNotReadyError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'JobNotReadyError'
  }
}

/**
 * The three `409` codes of the download route. They say *why* the document is not there — not yet,
 * not any more, or not the one that was generated — and none of them is the caller's fault.
 */
export const PDF_UNAVAILABLE_CODES = [
  'ARTIFACT_NOT_GENERATED',
  'ARTIFACT_MISSING',
  'ARTIFACT_DIGEST_MISMATCH',
] as const

export type PdfUnavailableCode = (typeof PDF_UNAVAILABLE_CODES)[number]

/** A job whose document cannot be handed out right now, carrying the reason as its `code`. */
export class JobPdfUnavailableError extends Error {
  readonly code: PdfUnavailableCode

  constructor(message: string, code: PdfUnavailableCode) {
    super(message)
    this.name = 'JobPdfUnavailableError'
    this.code = code
  }
}

function isPdfUnavailableCode(code: string | null): code is PdfUnavailableCode {
  return PDF_UNAVAILABLE_CODES.some((known) => known === code)
}

/**
 * The `ApiError` → store error mapping of the ship route. A `400` is *not* mapped: its field errors
 * belong at the form fields, so the original {@link ApiError} is passed through unchanged.
 */
export function toShipError(error: unknown): Error {
  const message = error instanceof Error ? error.message : 'Unknown error'

  if (!(error instanceof ApiError)) {
    return error instanceof Error ? error : new Error(message)
  }

  if (error.status === 404) {
    return new JobNotFoundError(message)
  }

  if (error.status === 409 && error.code === 'ALREADY_SHIPPED') {
    return new JobAlreadyShippedError(message)
  }

  if (error.status === 409 && error.code === 'NOT_READY') {
    return new JobNotReadyError(message)
  }

  return error
}

/** The `ApiError` → store error mapping of the PDF download route. */
export function toPdfError(error: unknown): Error {
  const message = error instanceof Error ? error.message : 'Unknown error'

  if (!(error instanceof ApiError)) {
    return error instanceof Error ? error : new Error(message)
  }

  if (error.status === 404) {
    return new JobNotFoundError(message)
  }

  if (error.status === 409 && isPdfUnavailableCode(error.code)) {
    return new JobPdfUnavailableError(message, error.code)
  }

  return error
}

/** The producer-facing name of a job's document, the same one the admin download uses. */
export function jobPdfFileName(orderId: number): string {
  return `ORD-${orderId}.pdf`
}

/** The order number a supplier sees on screen and on the printed document. */
export function orderNumber(orderId: number): string {
  return `ORD-${orderId}`
}

/**
 * Only the fields the supplier actually filled in. The route needs a JSON body even when nothing is
 * known, so an empty report is sent as `{}` rather than as no body at all.
 *
 * Exported because the admin's ship-on-behalf store sends the very same body to its own route.
 */
export function toShipBody(payload: ShipJobPayload): Record<string, string> {
  const body: Record<string, string> = {}
  const carrier = payload.carrier ?? null
  const trackingNumber = payload.trackingNumber?.trim() ?? ''

  if (carrier !== null) {
    body.carrier = carrier
  }

  if (trackingNumber !== '') {
    body.trackingNumber = trackingNumber
  }

  return body
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

  /** The jobs of one tab. The backend answers a bare array. */
  async function fetchJobs(status: SupplierJobStatus): Promise<SupplierJob[]> {
    isLoading.value = true
    error.value = null

    try {
      const loaded = await fetchJson<SupplierJob[]>(
        `/api/supplier/production-jobs?status=${status}`,
      )
      jobs.value = loaded
      loadedStatus.value = status
      return loaded
    } catch (err) {
      jobs.value = []
      loadedStatus.value = status
      error.value = err instanceof Error ? err : new Error('Unknown error')
      return []
    } finally {
      isLoading.value = false
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
