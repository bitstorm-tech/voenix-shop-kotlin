import type { Generated } from 'kysely'

/** Lifecycle of one customer request for additional Magic Coins. */
export type MagicCoinRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

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

/**
 * The `magic_coin_requests` table (V44): one row per refill request submitted by a signed-in
 * customer whose balance reached zero. `ux_magic_coin_requests_pending_user` allows at most one
 * `PENDING` request per user at a time.
 */
export interface MagicCoinRequestsTable {
  id: Generated<number>
  user_id: number
  status: MagicCoinRequestStatus
  granted_coins: number | null
  blocked_user: Generated<boolean>
  decided_by_user_id: number | null
  created_at: Generated<Date>
  decided_at: Date | null
}

/**
 * The `magic_coin_request_blocks` table (V44): accounts permanently blocked from submitting new
 * Magic Coin refill requests after an admin rejected them with a block.
 */
export interface MagicCoinRequestBlocksTable {
  user_id: number
  blocked_by_user_id: number | null
  blocked_at: Generated<Date>
}

declare module '@/platform/db/database' {
  interface Tables {
    magic_coins: MagicCoinsTable
    magic_coin_requests: MagicCoinRequestsTable
    magic_coin_request_blocks: MagicCoinRequestBlocksTable
  }
}
