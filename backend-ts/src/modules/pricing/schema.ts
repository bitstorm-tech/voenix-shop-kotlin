import type { ColumnType, Generated } from 'kysely'
import type {
  PriceCalculationMode,
  PriceDiscountType,
  PurchaseActiveRow,
  SalesActiveRow,
} from './calculation'

/** A `numeric` column: `pg` answers a string, which the repository parses once; writes take a number. */
type Numeric = ColumnType<string, number, number>

/** The `value_added_taxes` table (V2): the VAT entries an admin maintains. */
export interface ValueAddedTaxesTable {
  id: Generated<number>
  name: string
  percent: number
  description: string | null
  is_default: boolean
}

/**
 * The `prices` table (V4, V28): calculation inputs only. The text columns are typed by the values
 * their check constraints allow.
 */
export interface PricesTable {
  id: Generated<number>
  purchase_vat_id: number
  purchase_calculation_mode: PriceCalculationMode
  purchase_active_row: PurchaseActiveRow
  purchase_price_input_cents: number
  purchase_cost_input_cents: number
  purchase_cost_percent: Numeric
  sales_vat_id: number
  sales_calculation_mode: PriceCalculationMode
  sales_active_row: SalesActiveRow
  sales_margin_input_cents: number
  sales_margin_percent: Numeric
  sales_total_input_cents: number
  discount_type: PriceDiscountType | null
  discount_value: ColumnType<string | null, number | null, number | null>
}

declare module '@/platform/db/database' {
  interface Tables {
    value_added_taxes: ValueAddedTaxesTable
    prices: PricesTable
  }
}
