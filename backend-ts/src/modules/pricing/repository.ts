import type { Kysely, Transaction } from 'kysely'
import { read, write, type Tables } from '@/platform/db/database'
import { executePostgresWrite } from '@/platform/db/postgres-write'
import { conflict, notFound, ok, type OperationResult } from '@/platform/operation'
import type { CalculatedPrice, PriceInput } from './calculation'
import './schema'

/** A stored VAT entry; also the admin response and the VAT a calculated price carries. */
export interface Vat {
  id: number
  name: string
  percent: number
  description: string | null
  isDefault: boolean
}

/** The validated body of a VAT create or update, as `vat-routes.ts`'s schema produces it. */
export type VatInput = Omit<Vat, 'id'>

export type PricingRepository = ReturnType<typeof createPricingRepository>

const vatColumns = ['id', 'name', 'percent', 'description', 'is_default as isDefault'] as const

export function createPricingRepository(db: Kysely<Tables>) {
  return {
    /** Every VAT entry, ordered by name, then id. */
    listVats: () =>
      read(db, (trx) =>
        trx
          .selectFrom('value_added_taxes')
          .select(vatColumns)
          .orderBy('name')
          .orderBy('id')
          .execute(),
      ),

    getVat: async (id: number): Promise<OperationResult<Vat>> => {
      const vat = await read(db, (trx) =>
        trx
          .selectFrom('value_added_taxes')
          .select(vatColumns)
          .where('id', '=', id)
          .executeTakeFirst(),
      )
      return vat === undefined ? notFound : ok(vat)
    },

    /** The VAT entries with these ids, keyed by id; unknown ids are absent. */
    findVats: async (ids: Iterable<number>): Promise<Map<number, Vat>> => {
      const wanted = [...new Set(ids)]
      if (wanted.length === 0) return new Map()
      const rows = await read(db, (trx) =>
        trx.selectFrom('value_added_taxes').select(vatColumns).where('id', 'in', wanted).execute(),
      )
      return new Map(rows.map((row) => [row.id, row]))
    },

    /**
     * Inserts a VAT entry; a new default demotes the previous one in the same transaction. Any
     * unique violation (the name, or a concurrent second default) is the generic conflict.
     */
    createVat: (vat: VatInput): Promise<OperationResult<Vat>> =>
      executePostgresWrite({ uniqueViolation: conflict }, () =>
        write(db, async (trx) => {
          if (vat.isDefault) await demoteDefault(trx)
          const { id } = await trx
            .insertInto('value_added_taxes')
            .values(vatRow(vat))
            .returning('id')
            .executeTakeFirstOrThrow()
          return ok({ id, ...vat })
        }),
      ),

    updateVat: (id: number, vat: VatInput): Promise<OperationResult<Vat>> =>
      executePostgresWrite({ uniqueViolation: conflict }, () =>
        write(db, async (trx) => {
          const existing = await trx
            .selectFrom('value_added_taxes')
            .select('id')
            .where('id', '=', id)
            .forUpdate()
            .executeTakeFirst()
          if (existing === undefined) return notFound
          if (vat.isDefault) await demoteDefault(trx)
          await trx.updateTable('value_added_taxes').set(vatRow(vat)).where('id', '=', id).execute()
          return ok({ id, ...vat })
        }),
      ),

    /** A conflict when a price still references the entry. */
    deleteVat: (id: number): Promise<OperationResult<undefined>> =>
      executePostgresWrite({ foreignKeyViolation: conflict }, async () => {
        const result = await db
          .deleteFrom('value_added_taxes')
          .where('id', '=', id)
          .executeTakeFirst()
        return result.numDeletedRows === 0n ? notFound : ok(undefined)
      }),

    /** The stored inputs of these prices, keyed by id; unknown ids are absent. */
    findPrices: async (ids: Iterable<number>): Promise<Map<number, PriceInput>> => {
      const wanted = [...new Set(ids)]
      if (wanted.length === 0) return new Map()
      const rows = await read(db, (trx) =>
        trx.selectFrom('prices').selectAll().where('id', 'in', wanted).execute(),
      )
      return new Map(
        rows.map((row) => [
          row.id,
          {
            purchaseVatId: row.purchase_vat_id,
            purchaseCalculationMode: row.purchase_calculation_mode,
            purchaseActiveRow: row.purchase_active_row,
            purchasePriceInputCents: row.purchase_price_input_cents,
            purchaseCostInputCents: row.purchase_cost_input_cents,
            purchaseCostPercent: Number(row.purchase_cost_percent),
            salesVatId: row.sales_vat_id,
            salesCalculationMode: row.sales_calculation_mode,
            salesActiveRow: row.sales_active_row,
            salesMarginInputCents: row.sales_margin_input_cents,
            salesMarginPercent: Number(row.sales_margin_percent),
            salesTotalInputCents: row.sales_total_input_cents,
            discountType: row.discount_type,
            discountValue: row.discount_value === null ? null : Number(row.discount_value),
          },
        ]),
      )
    },

    /** Inserts `price` in the caller's transaction and answers the new id. */
    insertPrice: async (trx: Transaction<Tables>, price: CalculatedPrice): Promise<number> => {
      const { id } = await trx
        .insertInto('prices')
        .values(priceRow(price))
        .returning('id')
        .executeTakeFirstOrThrow()
      return id
    },

    /** Replaces every input of the price `id` in the caller's transaction; whether it existed. */
    updatePrice: async (
      trx: Transaction<Tables>,
      id: number,
      price: CalculatedPrice,
    ): Promise<boolean> => {
      const result = await trx
        .updateTable('prices')
        .set(priceRow(price))
        .where('id', '=', id)
        .executeTakeFirst()
      return result.numUpdatedRows > 0n
    },

    /** Deletes the price `id` in the caller's transaction; whether it existed. */
    deletePrice: async (trx: Transaction<Tables>, id: number): Promise<boolean> => {
      const result = await trx.deleteFrom('prices').where('id', '=', id).executeTakeFirst()
      return result.numDeletedRows > 0n
    },
  }
}

/** Clears the current default before a write that sets a new one, in the same transaction. */
async function demoteDefault(trx: Transaction<Tables>): Promise<void> {
  await trx
    .updateTable('value_added_taxes')
    .set({ is_default: false })
    .where('is_default', '=', true)
    .execute()
}

const vatRow = (vat: Omit<Vat, 'id'>) => ({
  name: vat.name,
  percent: vat.percent,
  description: vat.description,
  is_default: vat.isDefault,
})

/** The one column mapping of every price write: a calculated price stores exactly its inputs. */
const priceRow = (price: CalculatedPrice) => ({
  purchase_vat_id: price.purchaseVatId,
  purchase_calculation_mode: price.purchaseCalculationMode,
  purchase_active_row: price.purchaseActiveRow,
  purchase_price_input_cents: price.purchasePriceInputCents,
  purchase_cost_input_cents: price.purchaseCostInputCents,
  purchase_cost_percent: price.purchaseCostPercent,
  sales_vat_id: price.salesVatId,
  sales_calculation_mode: price.salesCalculationMode,
  sales_active_row: price.salesActiveRow,
  sales_margin_input_cents: price.salesMarginInputCents,
  sales_margin_percent: price.salesMarginPercent,
  sales_total_input_cents: price.salesTotalInputCents,
  discount_type: price.discount?.discountType ?? null,
  discount_value: price.discount?.discountValue ?? null,
})
