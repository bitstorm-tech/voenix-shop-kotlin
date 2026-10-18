import type { Generated } from 'kysely'

/**
 * The `magic_coins` table (V10): one coin balance per owner. Exactly one of the two owner columns
 * is set (`ck_magic_coins_exactly_one_owner`), each is unique where set, and the balance never goes
 * below zero (`ck_magic_coins_balance_non_negative`).
 */
export interface MagicCoinsTable {
  id: Generated<number>
  guest_session_token: string | null
  user_id: number | null
  balance: number
  created_at: Generated<Date>
  updated_at: Generated<Date>
}

declare module '@/platform/db/database' {
  interface Tables {
    magic_coins: MagicCoinsTable
  }
}
