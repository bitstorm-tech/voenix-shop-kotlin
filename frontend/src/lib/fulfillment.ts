import { ApiError } from '@/lib/api'

/**
 * The contract the two ship surfaces share: the supplier's own job list (`/supplier/*`) and the
 * administrator's logistics list (`/admin/logistics`), plus the one dialog both of them open.
 *
 * It lives outside `stores/` because a shared component may not depend on an area store: the
 * wire-level types, the carrier list, the error mapping and the wording helpers are stateless and
 * belong to neither area. The Pinia stores stay in their area folders and import from here.
 */

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
 * The little a shipment report needs to know about the job it is reporting: the order it belongs
 * to, which is what the dialog puts in its title. Both the supplier view of a job and the admin
 * view of one satisfy it, which is how the two surfaces share one dialog.
 */
export interface ShippableJob {
  orderId: number
}

/**
 * The two lists a surface has: what still has to go out, and what already went. The status is
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
 * Both ship routes — the supplier's own and the admin's on-behalf one — send this same body.
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
