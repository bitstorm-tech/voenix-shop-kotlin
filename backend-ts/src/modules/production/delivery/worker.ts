import { setTimeout as sleep } from 'node:timers/promises'
import type { Kysely } from 'kysely'
import type { EmailModule } from '@/modules/email'
import type { Tables } from '@/platform/db/database'
import { createLogger, errorClass } from '@/platform/logging'
import { type OrderLoader, type ProductionItem, type ProductionOrders } from '../orders'
import type { ArtifactStore } from '../pdf/artifact-store'
import type { SpodOrderRepository } from '../spod/repository'
import { createSubmissionStage, type SubmissionClient } from '../spod/submitter'
import { createDeliveryStage } from './deliverer'
import { createGenerationStage } from './generation'
import { createRequestRepository, type RequestRepository } from './requests'

const logger = createLogger('production-worker')
const POLL_INTERVAL_MILLIS = 60_000

/** One scan of a stage; it stops between rows once `signal` is aborted. */
type Stage = (signal?: AbortSignal) => Promise<void>

/**
 * The split stage: every open request becomes one job per involved supplier (suppliers in
 * first-appearance order, lines in order inside a supplier) in one transaction. An item without a
 * supplier keeps the whole request open with `ITEM_WITHOUT_SUPPLIER`: a partial split could attach
 * that line later to a job whose artifact already exists, and it would never be produced.
 */
function createSplitStage(requests: RequestRepository, loadOrder: OrderLoader): Stage {
  return async (signal) => {
    for (const request of await requests.openRequests()) {
      if (signal?.aborted === true) return
      if (!(await requests.startAttempt(request.id))) continue
      const loaded = await loadOrder(request.orderId)
      if (loaded.kind === 'failed') {
        await requests.recordFailure(request.id, loaded.code)
        continue
      }
      const { items } = loaded.order
      const routed = items.filter(
        (item): item is ProductionItem & { supplierId: number } => item.supplierId !== null,
      )
      if (routed.length < items.length) {
        await requests.recordFailure(request.id, 'ITEM_WITHOUT_SUPPLIER')
        continue
      }
      const itemsBySupplier: Map<number, ProductionItem[]> = Map.groupBy(
        routed,
        (item) => item.supplierId,
      )
      let withoutDestination: number[]
      try {
        withoutDestination = await requests.completeSplit(
          request.id,
          request.orderId,
          itemsBySupplier,
        )
      } catch (error) {
        logger.error(
          { requestId: request.id, errorClass: errorClass(error) },
          'Production request split failed',
        )
        await requests.recordFailure(request.id, 'SPLIT_FAILED')
        continue
      }
      for (const supplierId of withoutDestination) {
        logger.warn(
          { requestId: request.id, supplierId },
          'Supplier has no enabled destination; job created without deliveries',
        )
      }
      logger.info(
        { requestId: request.id, jobs: itemsBySupplier.size, attempt: request.attemptCount + 1 },
        'Production request split',
      )
    }
  }
}

/**
 * The one production worker, modelled on the email worker: every minute it runs the four
 * idempotent stages in order (split, generation, delivery, submission), one attempt per open
 * row and scan, unbounded attempts with bounded codes. One instance per database: a second worker
 * could create a second paid order at the print-on-demand partner.
 */
export function createProductionWorker({
  db,
  loadOrder,
  artifacts,
  outbox,
  orders,
  spodOrders,
  spod,
}: {
  db: Kysely<Tables>
  loadOrder: OrderLoader
  artifacts: ArtifactStore
  outbox: EmailModule['outbox']
  orders: Pick<ProductionOrders, 'fulfillmentOrders'>
  spodOrders: SpodOrderRepository
  spod: SubmissionClient
}) {
  const stages = [
    createSplitStage(createRequestRepository(db), loadOrder),
    createGenerationStage({ db, loadOrder, artifacts }),
    createDeliveryStage({ db, artifacts, outbox, orders }),
    createSubmissionStage({ loadOrder, orders: spodOrders, client: spod }),
  ]
  const runOnce = async (signal?: AbortSignal) => {
    for (const stage of stages) {
      if (signal?.aborted === true) return
      await stage(signal)
    }
  }

  let controller: AbortController | null = null
  let loop: Promise<void> = Promise.resolve()

  return {
    runOnce,

    /** Scans now and then every interval, until `stop`. */
    start: () => {
      if (controller !== null) throw new Error('The production worker is already started')
      const { signal } = (controller = new AbortController())
      loop = (async () => {
        while (!signal.aborted) {
          try {
            await runOnce(signal)
          } catch (error) {
            logger.error({ errorClass: errorClass(error) }, 'Production worker scan failed')
          }
          await sleep(POLL_INTERVAL_MILLIS, undefined, { signal }).catch(() => undefined)
        }
      })()
    },

    /** Stops the loop and resolves once the row in progress is finished; open work stays open. */
    stop: async () => {
      controller?.abort()
      await loop
    },
  }
}
