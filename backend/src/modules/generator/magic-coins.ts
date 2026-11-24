import type { Context } from 'hono'
import { sql, type ExpressionBuilder, type Kysely } from 'kysely'
import type { Auth } from '@/platform/auth/auth'
import { read, write, type Tables } from '@/platform/db/database'
import './schema'

/** The grant a balance starts with on first contact, and the price of one generation. */
export const INITIAL_BALANCE = 10
export const GENERATION_COST = 1

/** Who owns a coin balance: a signed-in customer, or the guest of the `voenix.guest` cookie. */
export type MagicCoinsOwner = { kind: 'user'; userId: number } | { kind: 'guest'; token: string }

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

/**
 * The existing owner behind a read request, or `null` for an anonymous visitor without a guest
 * cookie. Never sets a cookie, so passive page views do not create a 30-day guest cookie or a DB
 * row (§ 25 TDDDG).
 */
export async function tryMagicCoinsOwner(
  c: Context,
  guestTokens: Auth['guestTokens'],
): Promise<MagicCoinsOwner | null> {
  const userId = c.var.principal?.userId
  if (userId !== undefined) return { kind: 'user', userId }
  const token = await guestTokens.tryGet(c)
  return token === null ? null : { kind: 'guest', token }
}

/** How an owner appears in a log line: a guest token identifies a visitor and is never logged. */
export const describeOwner = (owner: MagicCoinsOwner): string =>
  owner.kind === 'user' ? `user owner ${String(owner.userId)}` : 'guest owner'

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
