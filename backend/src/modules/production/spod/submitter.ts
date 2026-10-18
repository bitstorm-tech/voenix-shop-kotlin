import type { SpodProductRef } from '@/modules/article'
import type { SpodAccess, SpodClient, SpodOneTimeItem, SpodOrderRequest } from '@/modules/spod'
import { createLogger, errorClass } from '@/platform/logging'
import { type OrderLoader, type ProductionItem, type ProductionOrder } from '../orders'
import { printImagePng } from './print-image'
import type { SpodOrderRepository } from './repository'

/** The calls of the partner's client the submission makes. */
export type SubmissionClient = Pick<
  SpodClient,
  'uploadDesign' | 'createOrder' | 'getOrder' | 'confirmOrder'
>

/** `ORD-{orderId}-JOB-{jobId}`: the reference both this shop and the partner's backoffice show. */
export const spodOrderReference = (orderId: number, jobId: number): string =>
  `ORD-${String(orderId)}-JOB-${String(jobId)}`

/**
 * The order and job a reference claims, or `null` when the string is not one of ours. Only a
 * claim: the webhook checks the job it finds against both numbers.
 */
export function parseSpodOrderReference(value: string): { orderId: number; jobId: number } | null {
  const match = /^ORD-(\d{1,15})-JOB-(\d{1,15})$/.exec(value.trim())
  if (match?.[1] === undefined || match[2] === undefined) return null
  return { orderId: Number(match[1]), jobId: Number(match[2]) }
}

/** One line of the job with the position `production_job_items` filed it under. */
interface SpodLine {
  position: number
  item: ProductionItem
  product: SpodProductRef
  hotspot: string
}

interface OpenSpodJob {
  id: number
  orderId: number
  supplierId: number
  spodOrderId: string | null
  attemptCount: number | null
}

const logger = createLogger('production-spod')

/**
 * The worker stage that prepares every SPOD job (SPOD fulfillment decision 4): upload the designs,
 * create the partner's order in state `NEW`, persist the id it answers with in its own transaction,
 * then read the order's state and confirm it while it is still `NEW`. The confirmation sets
 * `prepared_at`. One attempt per job per scan; a failure records a bounded code and the job waits
 * for the next scan. The partner has no idempotency key, so the order of these steps is the design.
 */
export function createSubmissionStage({
  loadOrder,
  orders,
  client,
}: {
  loadOrder: OrderLoader
  orders: SpodOrderRepository
  client: SubmissionClient
}) {
  /**
   * The job's lines, or `null` with the reason recorded. The live share of this supplier must be
   * exactly the snapshotted set (a line moved to another supplier is gone, not trailing), each line
   * must carry today's partner ids, today's variant name must equal the snapshot (the tripwire of
   * decision 8), and the line must carry the hotspot it was ordered with: the snapshot from the
   * order line, never today's catalog value and never a fallback (#260 decisions 8 and 14).
   */
  const resolveLines = async (job: OpenSpodJob, order: ProductionOrder) => {
    const snapshot = await orders.itemVariantNames(job.id)
    const share = order.items.filter((item) => item.supplierId === job.supplierId)
    const covered =
      share.length > 0 &&
      share.length === snapshot.size &&
      share.every((_, index) => snapshot.has(index + 1))
    const refuse = async (code: string) => {
      await orders.recordFailure(job.id, code)
      return null
    }
    if (!covered) return refuse('ITEM_SET_CHANGED')
    const lines: SpodLine[] = []
    for (const [index, item] of share.entries()) {
      const position = index + 1
      const { spodProduct: product, spodHotspot: hotspot } = item
      if (product === null) return refuse('ITEM_WITHOUT_SPOD_PRODUCT')
      if (snapshot.get(position) !== item.catalogVariantName) return refuse('SPOD_MAPPING_CHANGED')
      if (hotspot === null) return refuse('PLACEMENT_UNAVAILABLE')
      lines.push({ position, item, product, hotspot })
    }
    return lines
  }

  /**
   * The design id of every line, uploading what no earlier scan uploaded, one upload per distinct
   * print image. What was uploaded is recorded even when a later upload fails.
   */
  const uploadDesigns = async (job: OpenSpodJob, access: SpodAccess, lines: SpodLine[]) => {
    const designs = await orders.designs(job.id)
    const uploaded = new Map<number, string>()
    const pending = Map.groupBy(
      lines.filter((line) => !designs.has(line.position)),
      (line) => line.item.imagePath,
    )
    let failure: string | null = null
    for (const [path, group] of pending) {
      const first = group[0]
      if (first === undefined) continue
      const converted = await printImagePng(path)
      if (converted.kind === 'failed') {
        failure = converted.error
        break
      }
      const fileName = `${spodOrderReference(job.orderId, job.id)}-${String(first.position)}.png`
      const answer = await client.uploadDesign(access, fileName, converted.png)
      if (answer.kind === 'failed') {
        failure = answer.error
        break
      }
      for (const line of group) uploaded.set(line.position, answer.value)
    }
    await orders.recordDesigns(job.id, uploaded)
    if (failure !== null) {
      await orders.recordFailure(job.id, failure)
      return null
    }
    return new Map([...designs, ...uploaded])
  }

  /** Creates the order once; a stated refusal holds the job, an ambiguous outcome is counted. */
  const create = async (
    job: OpenSpodJob,
    order: ProductionOrder,
    access: SpodAccess,
    lines: SpodLine[],
  ): Promise<string | null> => {
    const phone = order.customerPhone?.trim() ?? ''
    if (phone === '') {
      await orders.recordFailure(job.id, 'PHONE_MISSING')
      return null
    }
    const designs = await uploadDesigns(job, access, lines)
    if (designs === null) return null
    const created = await client.createOrder(access, request(job, order, lines, designs, phone))
    if (created.kind === 'failed') {
      if (created.ambiguous) {
        const count = await orders.recordAmbiguousCreate(job.id, created.error)
        logger.error({ jobId: job.id, count }, 'SPOD order creation outcome unknown')
      } else {
        await orders.recordFailure(
          job.id,
          created.error === 'REFUSED' ? 'ORDER_CREATE_REJECTED' : created.error,
        )
      }
      return null
    }
    if (!(await orders.recordCreatedOrder(job.id, created.value))) {
      logger.error({ jobId: job.id }, 'The created SPOD order id could not be stored')
      await orders.recordFailure(job.id, 'ORDER_ID_NOT_STORED')
      return null
    }
    return created.value
  }

  /** Reads the state first and confirms only a `NEW` order; `CONFIRMED` is the crash recovery. */
  const confirm = async (job: OpenSpodJob, access: SpodAccess, spodOrderId: string) => {
    const state = await client.getOrder(access, spodOrderId)
    if (state.kind === 'failed') return orders.recordFailure(job.id, state.error)
    if (state.value === 'NEW') {
      const confirmed = await client.confirmOrder(access, spodOrderId)
      if (confirmed.kind === 'failed') {
        return orders.recordFailure(
          job.id,
          confirmed.error === 'REFUSED' ? 'ORDER_CONFIRM_FAILED' : confirmed.error,
        )
      }
    } else if (state.value !== 'CONFIRMED') {
      return orders.recordFailure(job.id, 'ORDER_STATE_UNEXPECTED')
    }
    await orders.completeConfirmation(job.id)
    logger.info({ jobId: job.id, attempt: (job.attemptCount ?? 0) + 1 }, 'SPOD job prepared')
  }

  const submit = async (job: OpenSpodJob) => {
    const loaded = await loadOrder(job.orderId)
    if (loaded.kind === 'failed') return orders.recordFailure(job.id, loaded.code)
    const destination = await orders.destination(job.supplierId)
    if (destination.kind !== 'found') {
      return orders.recordFailure(
        job.id,
        destination.kind === 'missing' ? 'DESTINATION_MISSING' : 'DESTINATION_DISABLED',
      )
    }
    const lines = await resolveLines(job, loaded.order)
    if (lines === null) return
    // A stored id is the crash-recovery case: re-enter at the confirmation, never create twice.
    const spodOrderId =
      job.spodOrderId ?? (await create(job, loaded.order, destination.access, lines))
    if (spodOrderId !== null) await confirm(job, destination.access, spodOrderId)
  }

  return async (signal?: AbortSignal): Promise<void> => {
    for (const job of await orders.openJobs()) {
      if (signal?.aborted === true) return
      await orders.startAttempt(job.id)
      try {
        await submit(job)
      } catch (error) {
        logger.error(
          { jobId: job.id, errorClass: errorClass(error) },
          'SPOD job submission failed unexpectedly',
        )
        await orders.recordFailure(job.id, 'SUBMISSION_FAILED')
      }
    }
  }
}

/**
 * The order the job becomes: always `NEW` (the client adds the state), the customer's e-mail and
 * phone, the deterministic reference, and one one-time item per (product type, design, hotspot,
 * unit price) with a quantity line per size and colour, placed on the front with the snapshotted
 * hotspot and priced at what the customer paid per piece.
 */
function request(
  job: OpenSpodJob,
  order: ProductionOrder,
  lines: SpodLine[],
  designs: Map<number, string>,
  phone: string,
): SpodOrderRequest {
  const items = new Map<string, SpodOneTimeItem>()
  for (const line of lines) {
    const designId = designs.get(line.position) ?? ''
    const { unitPriceCents } = line.item
    const key = JSON.stringify([line.product.productTypeId, designId, line.hotspot, unitPriceCents])
    const entry = items.get(key) ?? {
      productTypeId: line.product.productTypeId,
      quantityItems: [],
      configurations: [{ image: { designId }, view: 'FRONT', hotspot: line.hotspot }],
      customerPricePerItem: { amount: unitPriceCents / 100, currency: 'EUR' },
    }
    entry.quantityItems.push({
      quantity: line.item.quantity,
      sizeId: line.product.sizeId,
      appearanceId: line.product.appearanceId,
    })
    items.set(key, entry)
  }
  const houseNumber = order.shippingHouseNumber.trim()
  return {
    externalOrderReference: spodOrderReference(order.orderId, job.id),
    email: order.customerEmail,
    phone,
    shipping: {
      address: {
        firstName: order.shippingFirstName,
        lastName: order.shippingLastName,
        street:
          houseNumber === '' ? order.shippingStreet : `${order.shippingStreet} ${houseNumber}`,
        city: order.shippingCity,
        country: order.shippingCountry,
        zipCode: order.shippingPostalCode,
      },
    },
    oneTimeItems: [...items.values()],
  }
}
