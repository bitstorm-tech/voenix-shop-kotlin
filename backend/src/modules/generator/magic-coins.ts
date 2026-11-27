import type { Context } from 'hono'
import { sql, type ExpressionBuilder, type Kysely, type Transaction } from 'kysely'
import type { Auth } from '@/platform/auth/auth'
import { read, write, type Tables } from '@/platform/db/database'
import './schema'

/** The grant a balance starts with on first contact, and the price of one generation. */
export const INITIAL_BALANCE = 10
export const GENERATION_COST = 1

/** Who owns a coin balance: a signed-in customer, or the guest of the `voenix.guest` cookie. */
export type MagicCoinsOwner = { kind: 'user'; userId: number } | { kind: 'guest'; token: string }

/** Balance metadata of one user account for admin verification signals. */
export interface UserMagicCoinsSummary {
  balance: number
  firstSpentAt: Date
  lastUpdatedAt: Date
}

/**
 * The owner behind a mutating request: the signed-in user, otherwise the guest of the
 * `voenix.guest` cookie, which is issued when the request carries no usable one.
 */
export async function magicCoinsOwner(
  c: Context,
  guestTokens: Auth['guestTokens'],
): Promise<MagicCoinsOwner> {
  const userId = c.var.principal?.userId
  if (userId !== undefined) return { kind: 'user', userId }
  return { kind: 'guest', token: await guestTokens.getOrCreate(c) }
}

/** How an owner appears in a log line: a guest token identifies a visitor and is never logged. */
export const describeOwner = (owner: MagicCoinsOwner): string =>
  owner.kind === 'user' ? `user owner ${String(owner.userId)}` : 'guest owner'

/**
 * Atomically ensures the user's `magic_coins` row exists (starting from `INITIAL_BALANCE`) and
 * adds `amount` coins inside the caller's transaction; answers the resulting balance.
 */
export async function grantCoinsInTransaction(
  trx: Transaction<Tables>,
  userId: number,
  amount: number,
): Promise<number> {
  await trx
    .insertInto('magic_coins')
    .values({ user_id: userId, balance: INITIAL_BALANCE })
    .onConflict((conflict) => conflict.doNothing())
    .execute()
  const updated = await trx
    .updateTable('magic_coins')
    .set({ balance: sql`balance + ${amount}`, updated_at: sql`CURRENT_TIMESTAMP` })
    .where('user_id', '=', userId)
    .returning('balance')
    .executeTakeFirstOrThrow()
  return updated.balance
}

export type MagicCoins = ReturnType<typeof createMagicCoins>

/**
 * The statements a balance is ever touched with. Every race is left to PostgreSQL: the partial
 * unique indexes decide which of two first contacts creates the row, and the spend is an atomic
 * conditional `UPDATE`, so concurrent generations can never overspend a single coin. A database
 * failure throws; it is never reported as an empty balance.
 */
export function createMagicCoins(db: Kysely<Tables>) {
  return {
    /** Reads the owner's balance without creating a row; defaults to `INITIAL_BALANCE` if absent. */
    readBalance: async (owner: MagicCoinsOwner): Promise<number> => {
      const row = await read(db, (trx) =>
        trx.selectFrom('magic_coins').select('balance').where(ownerIs(owner)).executeTakeFirst(),
      )
      return row?.balance ?? INITIAL_BALANCE
    },

    /** Reads stored balance metadata for the given user ids; users without a row are absent. */
    readUserSummaries: async (
      userIds: readonly number[],
    ): Promise<Map<number, UserMagicCoinsSummary>> => {
      const unique = [...new Set(userIds)]
      if (unique.length === 0) return new Map()
      const rows = await read(db, (trx) =>
        trx
          .selectFrom('magic_coins')
          .select(['user_id', 'balance', 'created_at', 'updated_at'])
          .where('user_id', 'in', unique)
          .$narrowType<{ user_id: number }>()
          .execute(),
      )
      return new Map(
        rows.map((row) => [
          row.user_id,
          {
            balance: row.balance,
            firstSpentAt: row.created_at,
            lastUpdatedAt: row.updated_at,
          },
        ]),
      )
    },

    /** The owner's balance, created with the initial grant on first contact. */
    balance: (owner: MagicCoinsOwner): Promise<number> =>
      write(db, async (trx) => {
        await trx
          .insertInto('magic_coins')
          .values(
            owner.kind === 'user'
              ? { user_id: owner.userId, balance: INITIAL_BALANCE }
              : { guest_session_token: owner.token, balance: INITIAL_BALANCE },
          )
          .onConflict((conflict) => conflict.doNothing())
          .execute()
        const row = await trx
          .selectFrom('magic_coins')
          .select('balance')
          .where(ownerIs(owner))
          .executeTakeFirstOrThrow()
        return row.balance
      }),

    /**
     * Atomically ensures the owner row exists and deducts one generation (`WHERE balance >= 1`);
     * answers `false` when the balance did not cover it.
     */
    spend: (owner: MagicCoinsOwner): Promise<boolean> =>
      write(db, async (trx) => {
        await trx
          .insertInto('magic_coins')
          .values(
            owner.kind === 'user'
              ? { user_id: owner.userId, balance: INITIAL_BALANCE }
              : { guest_session_token: owner.token, balance: INITIAL_BALANCE },
          )
          .onConflict((conflict) => conflict.doNothing())
          .execute()
        const updated = await trx
          .updateTable('magic_coins')
          .set({ balance: sql`balance - ${GENERATION_COST}`, updated_at: sql`CURRENT_TIMESTAMP` })
          .where(ownerIs(owner))
          .where('balance', '>=', GENERATION_COST)
          .returning('balance')
          .executeTakeFirst()
        return updated !== undefined
      }),

    /** Atomically credits back one generation after an upstream or internal failure. */
    refund: async (owner: MagicCoinsOwner): Promise<void> => {
      await db
        .updateTable('magic_coins')
        .set({ balance: sql`balance + ${GENERATION_COST}`, updated_at: sql`CURRENT_TIMESTAMP` })
        .where(ownerIs(owner))
        .execute()
    },
  }
}

/** The one row an owner's balance lives in. */
const ownerIs = (owner: MagicCoinsOwner) => (eb: ExpressionBuilder<Tables, 'magic_coins'>) =>
  owner.kind === 'user'
    ? eb('user_id', '=', owner.userId)
    : eb('guest_session_token', '=', owner.token)
