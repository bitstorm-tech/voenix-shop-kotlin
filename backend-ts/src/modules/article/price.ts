import type { Transaction } from 'kysely'
import type { CalculatedPrice, PriceCatalog, PriceInput } from '@/modules/pricing'
import type { Tables } from '@/platform/db/database'
import { invalid, ok, type OperationResult } from '@/platform/operation'

/**
 * The price an article owns. It is prepared before the article's transaction opens, because that
 * asks the pricing module, and written inside it, so an article and its price commit or roll back
 * together: a rejected price creates no article, a rejected article leaves no price row.
 */

/**
 * Validates and calculates the submitted price without writing; `null` means "keep what is
 * stored". The rules `prepare` answers (an unknown VAT, a discount that does not fit) are reported
 * under the path the client sent them at, `price.salesVatId`, like the field rules of the schema.
 */
export async function preparePrice(
  prices: Pick<PriceCatalog, 'prepare'>,
  input: PriceInput | null,
): Promise<OperationResult<CalculatedPrice | null>> {
  if (input === null) return ok(null)
  const prepared = await prices.prepare(input)
  if (prepared.kind !== 'invalid') return prepared
  return invalid(
    Object.fromEntries(
      Object.entries(prepared.errors).map(([field, messages]) => [`price.${field}`, messages]),
    ),
  )
}

/**
 * The price id the article keeps after the write: the stored one when no price was submitted, a new
 * row when it had none, otherwise the stored row rewritten in place, so the id never churns. A
 * stored id without its row is a broken invariant, because this module minted it.
 */
export async function writePrice(
  trx: Transaction<Tables>,
  prices: Pick<PriceCatalog, 'storeInTransaction' | 'replaceInTransaction'>,
  storedPriceId: number | null,
  price: CalculatedPrice | null,
): Promise<number | null> {
  if (price === null) return storedPriceId
  if (storedPriceId === null) return prices.storeInTransaction(trx, price)
  if (!(await prices.replaceInTransaction(trx, storedPriceId, price))) {
    throw new Error(`The price row ${String(storedPriceId)} of an article disappeared`)
  }
  return storedPriceId
}
