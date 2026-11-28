import { sql, type Kysely } from 'kysely'
import { read, write, type Tables } from '@/platform/db/database'

/** Where one SPOD variant's stock is read: its article's destination and environment, and its SKU. */
export interface SpodStockKey {
  variantId: number
  destinationId: number
  environment: string
  sku: string
}

/**
 * The SPOD variants the partner can deliver: stock above zero, or never read. Unqualified, because
 * only `article_spod_variants` has the column, whatever the query joins.
 */
export const IN_STOCK = sql<boolean>`(spod_stock IS NULL OR spod_stock > 0)`

export type SpodStock = ReturnType<typeof createSpodStock>

/**
 * The partner's stock on the SPOD variants (`spod_stock`, V46). Production reads it from the partner
 * and hands it here, because the access to the partner is production's and the variants are ours.
 */
export function createSpodStock(db: Kysely<Tables>) {
  return {
    /**
     * Stores `stock` (SKU → quantity) on the variants of one destination's articles in
     * `environment`. A `complete` listing also stores `0` on every variant whose SKU it lacks: the
     * partner no longer sells it. A partial one touches only the SKUs it names. Answers how many
     * variants changed.
     */
    record: (
      destinationId: number,
      environment: string,
      stock: ReadonlyMap<string, number>,
      complete: boolean,
    ): Promise<number> => {
      const skus = [...stock.keys()]
      if (!complete && skus.length === 0) return Promise.resolve(0)
      const listed = sql<number | null>`(SELECT s.quantity
        FROM unnest(${skus}::text[], ${[...stock.values()]}::int[]) AS s(sku, quantity)
        WHERE s.sku = article_spod_variants.sku)`
      const quantity = complete ? sql<number>`COALESCE(${listed}, 0)` : listed
      return write(db, async (trx) => {
        let update = trx
          .updateTable('article_spod_variants')
          .set({ spod_stock: quantity })
          .where('article_id', 'in', (eb) =>
            eb
              .selectFrom('article_spod_articles')
              .select('id')
              .where('spod_destination_id', '=', destinationId)
              .where('spod_environment', '=', environment),
          )
          .where('sku', 'is not', null)
          .where(sql<boolean>`spod_stock IS DISTINCT FROM ${quantity}`)
        if (!complete) update = update.where('sku', 'in', skus)
        const result = await update.executeTakeFirst()
        return Number(result.numUpdatedRows)
      })
    },

    /** Where to read the stock of those of `variantIds` that are SPOD variants with a SKU. */
    keysOf: async (variantIds: readonly number[]): Promise<SpodStockKey[]> => {
      if (variantIds.length === 0) return []
      const rows = await read(db, (trx) =>
        trx
          .selectFrom('article_spod_variants as v')
          .innerJoin('article_spod_articles as a', 'a.id', 'v.article_id')
          .select([
            'v.id as variantId',
            'v.sku',
            'a.spod_destination_id as destinationId',
            'a.spod_environment as environment',
          ])
          .where('v.id', 'in', variantIds)
          .where('v.sku', 'is not', null)
          .execute(),
      )
      return rows.flatMap((row) => (row.sku === null ? [] : [{ ...row, sku: row.sku }]))
    },
  }
}
