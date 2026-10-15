import { Hono } from 'hono'
import type { Kysely } from 'kysely'
import type { Tables } from '@/platform/db/database'
import { invalid } from '@/platform/operation'
import type { PriceInput } from './calculation'
import { createPricingRepository } from './repository'
import { priceRoutes } from './routes'
import { createPriceService } from './service'
import { vatRoutes } from './vat-routes'

export type {
  CalculatedPrice,
  PriceAmount,
  PriceCalculationMode,
  PriceDiscount,
  PriceDiscountType,
  PriceInput,
  PurchaseActiveRow,
  SalesActiveRow,
} from './calculation'
export type { Vat } from './repository'
/** The price body schema; a module that owns a price embeds it in its own request schema. */
export { priceInput } from './routes'

/**
 * The pricing module: the price and VAT admin routes, and `priceCatalog`, the capability a module
 * that owns prices (Article, Prompt) uses. `prepare` validates against the current VAT and
 * calculates without writing, reporting its field errors under `price.`, the path an owner embeds
 * `priceInput` at (`price.salesVatId`); the three writes run on the caller's transaction, so the
 * owner and its price commit or roll back together; `find` reads and recalculates prices in one
 * batch.
 */
export function createPricingModule({ db }: { db: Kysely<Tables> }) {
  const repository = createPricingRepository(db)
  const prices = createPriceService(repository)
  const routes = new Hono()
  routes.route('/', priceRoutes(prices))
  routes.route('/', vatRoutes(repository))
  return {
    routes,
    priceCatalog: {
      prepare: async (input: PriceInput) => {
        const prepared = await prices.prepare(input)
        if (prepared.kind !== 'invalid') return prepared
        return invalid(
          Object.fromEntries(
            Object.entries(prepared.errors).map(([field, messages]) => [
              `price.${field}`,
              messages,
            ]),
          ),
        )
      },
      storeInTransaction: repository.insertPrice,
      replaceInTransaction: repository.updatePrice,
      deleteInTransaction: repository.deletePrice,
      find: prices.find,
    },
  }
}

export type PricingModule = ReturnType<typeof createPricingModule>
export type PriceCatalog = PricingModule['priceCatalog']
