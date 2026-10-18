import type { ColumnType, Generated } from 'kysely'

/**
 * The `promotions` table (V12). `discount_value` is `numeric(12,2)`: `pg` reads it as a string,
 * which the repository parses once; a write accepts the number.
 */
export interface PromotionsTable {
  id: Generated<number>
  name: string
  discount_type: 'PERCENTAGE' | 'FIXED_AMOUNT'
  discount_value: ColumnType<string, number, number>
  coupon_code: string
  /** The trimmed, upper-cased code; carries the case-insensitive unique constraint. */
  coupon_code_normalized: string
  starts_at: Date | null
  ends_at: Date | null
  usage_limit_total: number | null
  usage_limit_per_user: number | null
  is_active: boolean
}

/**
 * The `promotion_redemptions` table (V12, `order_id` added by V16): one row per paid order that used
 * a promotion. `order_id` is unique, so an order redeems at most once.
 */
export interface PromotionRedemptionsTable {
  id: Generated<number>
  promotion_id: number
  user_id: number | null
  order_id: number
  redeemed_at: Date
}

/**
 * The `promotion_reservations` table (V18): the capacity a running checkout holds, at most one row
 * per cart (`cart_id` is unique).
 */
export interface PromotionReservationsTable {
  id: Generated<number>
  promotion_id: number
  cart_id: number
  user_id: number | null
  created_at: Generated<Date>
}

declare module '@/platform/db/database' {
  interface Tables {
    promotions: PromotionsTable
    promotion_redemptions: PromotionRedemptionsTable
    promotion_reservations: PromotionReservationsTable
  }
}
