import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchJson } from '@/lib/api'
import type { OperationsFinding } from '@/stores/admin/operations'
import type { Order, OrderPaymentStatus, OrderStatus } from '@/stores/shop/orders'

/**
 * One row of `GET /api/admin/orders`: the newest 50 orders, optionally of exactly one e-mail
 * address, each with the number of its open operations findings. `total` is in integer cents.
 */
export interface AdminOrderListEntry {
  orderId: number
  createdAt: string
  status: OrderStatus
  paymentStatus: OrderPaymentStatus | null
  total: number
  email: string
  openFindings: number
}

/** The answer of a payment recheck: the order's status and its payment's, read after the recheck. */
export interface PaymentRecheck {
  status: OrderStatus
  paymentStatus: OrderPaymentStatus
}

/** A postal address as the order stored it. */
export interface AdminOrderAddress {
  firstName: string
  lastName: string
  street: string
  houseNumber: string
  postalCode: string
  city: string
  country: string
}

/**
 * Who moved an order: the shop itself, the customer, a signed-in admin or supplier, or a
 * fulfillment channel. `actorId` is the user of an `ADMIN` or `SUPPLIER` event.
 */
export type OrderEventActorType = 'SYSTEM' | 'CUSTOMER' | 'ADMIN' | 'SUPPLIER' | 'CHANNEL'

/** One entry of the order history; `details` holds a few bounded values of the event. */
export interface OrderEvent {
  id: number
  occurredAt: string
  kind: string
  actorType: OrderEventActorType
  actorId: number | null
  cause: string | null
  details: Record<string, string | number | null> | null
}

export interface OrderTimelineDelivery {
  deliveryId: number
  destinationId: number
  attemptCount: number
  lastErrorCode: string | null
  deliveredAt: string | null
}

export interface OrderTimelineSpod {
  externalReference: string | null
  createState: string
  createAmbiguousCount: number
  attemptCount: number
  lastErrorCode: string | null
  confirmedAt: string | null
  remoteState: string | null
}

export interface OrderTimelineJob {
  jobId: number
  supplierId: number
  fulfillmentChannel: string
  generationAttemptCount: number
  lastGenerationErrorCode: string | null
  generatedAt: string | null
  preparedAt: string | null
  shippedAt: string | null
  shippedByUserId: number | null
  shippedByChannel: string | null
  shippingCarrier: string | null
  trackingNumber: string | null
  deliveries: OrderTimelineDelivery[]
  /** The remote order of a SPOD job; `null` on an SFTP job. */
  spod: OrderTimelineSpod | null
}

/**
 * Everything `GET /api/admin/orders/{orderId}/timeline` answers about one order: the order with
 * its contact data, every payment attempt, the production request and jobs, the mails, the order
 * history, and the open findings. Timestamps are ISO strings; amounts are integer cents.
 */
export interface OrderTimeline {
  order: Order & {
    email: string
    phone: string | null
    shippingAddress: AdminOrderAddress
    billingAddress: AdminOrderAddress
    cartId: number
    promotionId: number | null
    updatedAt: string
  }
  payments: {
    paymentId: number
    molliePaymentId: string
    status: OrderPaymentStatus
    amount: number
    createdAt: string
    updatedAt: string
  }[]
  production: {
    /** `null` until the order is paid. */
    request: {
      id: number
      attemptCount: number
      lastErrorCode: string | null
      createdAt: string
      processedAt: string | null
    } | null
    jobs: OrderTimelineJob[]
  }
  emails: {
    emailJobId: number
    kind: string
    recipient: string
    attemptCount: number
    lastErrorCode: string | null
    createdAt: string
    sentAt: string | null
  }[]
  events: OrderEvent[]
  findings: Omit<OperationsFinding, 'orderId' | 'resolvedAt'>[]
}

/**
 * One production document of an order, as `GET /api/admin/orders/{orderId}/production-pdfs` names
 * it. An order yields **one document per involved supplier**, so this list is the only way to learn
 * which downloads exist (`docs/migration/order-post-migration.md`, deviation D2).
 *
 * `fileName` is the producer-facing `ORD-{orderId}.pdf` the SFTP delivery uses as well. It is
 * unique per destination, not per order, and therefore **repeats** across the suppliers of one
 * order — a client that saves several documents of one order has to disambiguate them itself, see
 * {@link productionPdfDownloadName}.
 */
export interface ProductionPdfInfo {
  supplierId: number
  fileName: string
}

/** A downloaded document together with the name it should be saved under. */
export interface ProductionPdfDownload {
  blob: Blob
  fileName: string
}

/**
 * The three `409` codes that describe the order's own production data: an ordered item with no
 * usable production image, an image that cannot be read, and production data no document can be
 * laid out from. All three are repairable by an admin and the document exists once they are — they
 * are not server faults. None of them means a missing supplier assignment; that case has no code at
 * all (`backend/modules/order/src/shop/voenix/order/OrderRoutes.kt`).
 */
export const PRODUCTION_PDF_DATA_ERROR_CODES = [
  'PRODUCTION_PDF_MISSING_IMAGE',
  'PRODUCTION_PDF_UNREADABLE_IMAGE',
  'PRODUCTION_PDF_INVALID_SOURCE',
] as const

export type ProductionPdfDataErrorCode = (typeof PRODUCTION_PDF_DATA_ERROR_CODES)[number]

/** The one `500` code of the two routes. Its details are in the server log and never in the body. */
export const PRODUCTION_PDF_RENDER_FAILURE_CODE = 'PRODUCTION_PDF_RENDER_FAILURE'

/** An unknown order id — and, on the download route, a supplier the order has no document for. */
export class OrderNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'OrderNotFoundError'
  }
}

/** Repairable order data: a `409` with one of {@link PRODUCTION_PDF_DATA_ERROR_CODES}. */
export class ProductionPdfDataError extends Error {
  readonly code: ProductionPdfDataErrorCode

  constructor(message: string, code: ProductionPdfDataErrorCode) {
    super(message)
    this.name = 'ProductionPdfDataError'
    this.code = code
  }
}

/** A renderer fault: the `500 PRODUCTION_PDF_RENDER_FAILURE`. Nothing an admin can repair. */
export class ProductionPdfRenderError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ProductionPdfRenderError'
  }
}

function isDataErrorCode(code: string | null): code is ProductionPdfDataErrorCode {
  return PRODUCTION_PDF_DATA_ERROR_CODES.some((known) => known === code)
}

/**
 * The `ApiError` → store error mapping of both production-PDF routes. Everything the two routes can
 * answer besides the document itself is one of these three; anything else stays a plain `Error`
 * carrying the backend's message.
 */
export function toProductionPdfError(error: unknown): Error {
  const message = error instanceof Error ? error.message : 'Unknown error'

  if (!(error instanceof ApiError)) {
    return new Error(message)
  }

  if (error.status === 404) {
    return new OrderNotFoundError(message)
  }

  if (error.status === 409 && isDataErrorCode(error.code)) {
    return new ProductionPdfDataError(message, error.code)
  }

  if (error.status === 500 && error.code === PRODUCTION_PDF_RENDER_FAILURE_CODE) {
    return new ProductionPdfRenderError(message)
  }

  return new Error(message)
}

/**
 * The name a saved document gets in the browser.
 *
 * The backend's `Content-Disposition` name repeats across the suppliers of one order, so saving two
 * of them under the server name would overwrite one another (or pile up as `ORD-42 (1).pdf`). The
 * supplier suffix is the disambiguation the migration note asks the client for; the producer-facing
 * name stays visible in the list.
 */
export function productionPdfDownloadName(orderId: number, supplierId: number): string {
  return `ORD-${orderId}-supplier-${supplierId}.pdf`
}

export const useAdminOrdersStore = defineStore('admin-orders', () => {
  const documents = ref<ProductionPdfInfo[]>([])
  /** The order the current {@link documents} belong to, or `null` when nothing was looked up yet. */
  const loadedOrderId = shallowRef<number | null>(null)
  const isLoading = shallowRef(false)
  const downloadingSupplierId = shallowRef<number | null>(null)
  /** The typed failure of the last lookup. The view decides how to word it. */
  const error = shallowRef<Error | null>(null)

  const orderList = ref<AdminOrderListEntry[]>([])
  const isListLoading = shallowRef(false)
  /** The failure of the last list load. */
  const listError = shallowRef<Error | null>(null)

  const timeline = shallowRef<OrderTimeline | null>(null)
  const isTimelineLoading = shallowRef(false)
  /** {@link OrderNotFoundError} for an unknown order, otherwise the failure as it came. */
  const timelineError = shallowRef<Error | null>(null)

  /** The answer of the last payment recheck, or `null` before one. */
  const recheck = shallowRef<PaymentRecheck | null>(null)
  const isRechecking = shallowRef(false)
  /** The failure of the last recheck, such as the `409` of an order without a payment. */
  const recheckError = shallowRef<Error | null>(null)

  function reset() {
    documents.value = []
    loadedOrderId.value = null
    isLoading.value = false
    downloadingSupplierId.value = null
    error.value = null
  }

  /**
   * Lists the documents of one order. Both routes generate on demand and store nothing, so this
   * list is a statement about the order as it is right now: repair the order's data and the same
   * lookup succeeds.
   */
  async function fetchProductionPdfs(orderId: number): Promise<ProductionPdfInfo[]> {
    isLoading.value = true
    error.value = null
    documents.value = []
    loadedOrderId.value = null

    try {
      const items = await fetchJson<ProductionPdfInfo[]>(
        `/api/admin/orders/${orderId}/production-pdfs`,
      )
      documents.value = items
      loadedOrderId.value = orderId
      return items
    } catch (err) {
      error.value = toProductionPdfError(err)
      return []
    } finally {
      isLoading.value = false
    }
  }

  /**
   * Downloads one supplier's document as a blob. The same generation runs again, so a document that
   * was listed a moment ago can still fail with one of the `409` data codes.
   */
  async function downloadProductionPdf(
    orderId: number,
    supplierId: number,
  ): Promise<ProductionPdfDownload> {
    downloadingSupplierId.value = supplierId

    try {
      const blob = await fetchJson<Blob>(
        `/api/admin/orders/${orderId}/production-pdfs/${supplierId}`,
        { responseType: 'blob' },
      )
      return { blob, fileName: productionPdfDownloadName(orderId, supplierId) }
    } catch (err) {
      throw toProductionPdfError(err)
    } finally {
      downloadingSupplierId.value = null
    }
  }

  /** The newest orders; a non-blank `email` narrows them to exactly that address, in any case. */
  async function fetchOrders(email = ''): Promise<void> {
    isListLoading.value = true
    listError.value = null
    const filter = email.trim()

    try {
      orderList.value = await fetchJson<AdminOrderListEntry[]>(
        filter === ''
          ? '/api/admin/orders'
          : `/api/admin/orders?${new URLSearchParams({ email: filter })}`,
      )
    } catch (err) {
      orderList.value = []
      listError.value = err instanceof Error ? err : new Error('Unknown error')
    } finally {
      isListLoading.value = false
    }
  }

  /**
   * The number of the most recently issued timeline load. Opening another order starts a second
   * load before the first has answered; only the newest one may write the timeline state.
   */
  let latestTimelineFetchId = 0

  /** Everything known about one order; an unknown id ends in {@link OrderNotFoundError}. */
  async function fetchTimeline(orderId: number): Promise<void> {
    const fetchId = ++latestTimelineFetchId
    isTimelineLoading.value = true
    timelineError.value = null
    timeline.value = null

    try {
      const loaded = await fetchJson<OrderTimeline>(`/api/admin/orders/${orderId}/timeline`)
      if (fetchId === latestTimelineFetchId) timeline.value = loaded
    } catch (err) {
      if (fetchId === latestTimelineFetchId) {
        const message = err instanceof Error ? err.message : 'Unknown error'
        timelineError.value =
          err instanceof ApiError && err.status === 404
            ? new OrderNotFoundError(message)
            : new Error(message)
      }
    } finally {
      if (fetchId === latestTimelineFetchId) isTimelineLoading.value = false
    }
  }

  /**
   * Asks for the order's payment now, through the same path as the webhook, and reloads the
   * timeline, which then shows what the recheck changed. A timeline load started meanwhile, such
   * as opening another order, makes the answer stale: it is then neither kept nor reloaded.
   */
  async function recheckPayment(orderId: number): Promise<void> {
    const fetchId = latestTimelineFetchId
    isRechecking.value = true
    recheckError.value = null
    recheck.value = null

    try {
      const answer = await fetchJson<PaymentRecheck>(
        `/api/admin/orders/${orderId}/payment/recheck`,
        { method: 'POST' },
      )
      if (fetchId !== latestTimelineFetchId) return
      recheck.value = answer
      await fetchTimeline(orderId)
    } catch (err) {
      if (fetchId === latestTimelineFetchId) {
        recheckError.value = err instanceof Error ? err : new Error('Unknown error')
      }
    } finally {
      isRechecking.value = false
    }
  }

  return {
    orderList,
    isListLoading,
    listError,
    timeline,
    isTimelineLoading,
    timelineError,
    fetchOrders,
    fetchTimeline,
    recheck,
    isRechecking,
    recheckError,
    recheckPayment,
    documents,
    loadedOrderId,
    isLoading,
    downloadingSupplierId,
    error,
    reset,
    fetchProductionPdfs,
    downloadProductionPdf,
  }
})
