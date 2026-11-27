import { setTimeout as sleep } from 'node:timers/promises'
import type { SpodCatalogSource, SpodStock } from '@/modules/article'
import type { SpodAccess, SpodClient } from '@/modules/spod'
import { createLogger, errorDetails } from '@/platform/logging'

const SCAN_INTERVAL_MILLIS = 15 * 60_000
const PAGE_LIMIT = 100
/** 10 000 SKUs, far beyond any catalog; a listing that keeps going is not trusted as complete. */
const MAX_PAGES = 100
/** How long a checkout waits for the partner's stock before it goes on with the stored one. */
const CHECKOUT_BUDGET_MILLIS = 5_000

export type StockClient = Pick<SpodClient, 'stock' | 'stockOf'>

/**
 * The partner's stock on our SPOD variants. The worker reads every SPOD destination's whole stock
 * listing now and then every 15 minutes; `refresh` reads the SKUs of a cart's lines right before
 * its checkout. Both hand what they read to the article module, whose buy rule then hides and
 * refuses what is sold out. A partner that does not answer changes nothing: the stored stock stays,
 * so an outage of the partner never empties the shop or blocks a checkout.
 */
export function createSpodStockSync({
  destinations,
  spod,
  stock,
}: {
  destinations: {
    spodDestinationIds: () => Promise<number[]>
    catalogSource: (id: number) => Promise<SpodCatalogSource | 'notFound' | 'notSyncable'>
  }
  spod: StockClient
  stock: Pick<SpodStock, 'record' | 'keysOf'>
}) {
  const logger = createLogger('spod-stock')

  /** The whole listing and whether it is complete; `null` when a page was not answered. */
  const readListing = async (access: SpodAccess) => {
    const listing = new Map<string, number>()
    for (let page = 0; page < MAX_PAGES; page++) {
      const answer = await spod.stock(access, PAGE_LIMIT, page * PAGE_LIMIT)
      if (answer.kind === 'failed') return null
      const items = Object.entries(answer.value.items)
      for (const [sku, quantity] of items) listing.set(sku, quantity)
      if (items.length === 0 || (page + 1) * PAGE_LIMIT >= answer.value.count) {
        return { listing, complete: listing.size === answer.value.count }
      }
    }
    return { listing, complete: false }
  }

  const scan = async (signal?: AbortSignal) => {
    for (const id of await destinations.spodDestinationIds()) {
      if (signal?.aborted === true) return
      const source = await destinations.catalogSource(id)
      if (typeof source === 'string') continue
      const read = await readListing(source.access)
      if (read === null) {
        logger.warn({ destinationId: id }, 'SPOD stock not answered; the stored stock stays')
        continue
      }
      if (!read.complete) {
        logger.warn({ destinationId: id }, 'SPOD stock listing incomplete; no SKU counts as gone')
      }
      const changed = await stock.record(id, source.access.environment, read.listing, read.complete)
      if (changed > 0) logger.info({ destinationId: id, changed }, 'SPOD stock updated')
    }
  }

  let controller: AbortController | null = null
  let loop: Promise<void> = Promise.resolve()

  return {
    scan,

    /**
     * Reads the stock of the SPOD variants among `variantIds` from the partner and stores it,
     * within a few seconds altogether; whatever is not answered by then keeps its stored stock.
     */
    refresh: async (variantIds: readonly number[]) => {
      const keys = await stock.keysOf(variantIds)
      if (keys.length === 0) return
      const signal = AbortSignal.timeout(CHECKOUT_BUDGET_MILLIS)
      for (const destinationId of new Set(keys.map((key) => key.destinationId))) {
        const source = await destinations.catalogSource(destinationId)
        if (typeof source === 'string') continue
        const read = new Map<string, number>()
        for (const key of keys) {
          // An article of the other environment is swept inactive by the next sync anyway.
          if (key.destinationId !== destinationId || key.environment !== source.access.environment)
            continue
          const answer = await spod.stockOf(source.access, key.sku, signal)
          if (answer.kind === 'answered') read.set(key.sku, answer.value)
        }
        await stock.record(destinationId, source.access.environment, read, false)
      }
    },

    /** Scans now and then every 15 minutes, until `stop`. */
    start: () => {
      if (controller !== null) throw new Error('The SPOD stock worker is already started')
      const { signal } = (controller = new AbortController())
      loop = (async () => {
        while (!signal.aborted) {
          try {
            await scan(signal)
          } catch (error) {
            logger.error({ error: errorDetails(error) }, 'SPOD stock scan failed')
          }
          await sleep(SCAN_INTERVAL_MILLIS, undefined, { signal }).catch(() => undefined)
        }
      })()
    },

    /** Stops the loop and resolves once the scan in progress is finished. */
    stop: async () => {
      controller?.abort()
      await loop
    },
  }
}
