import type { Context } from 'hono'
import { sql, type ExpressionBuilder, type Kysely } from 'kysely'
import type { Auth } from '@/platform/auth/auth'
import { write, type Tables } from '@/platform/db/database'
import './schema'

/** The grant a balance starts with on first contact, and the price of one generation. */
export const INITIAL_BALANCE = 10
export const GENERATION_COST = 1

/** Who owns a coin balance: a signed-in customer, or the guest of the `voenix.guest` cookie. */
export type MagicCoinsOwner = { kind: 'user'; userId: number } | { kind: 'guest'; token: string }

/**
 * The owner behind this request: the signed-in user, otherwise the guest of the `voenix.guest`
 * cookie, which is issued when the request carries no usable one. The one implementation of that
 * rule; both routes of the module use it.
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

export type MagicCoins = ReturnType<typeof createMagicCoins>

/**
 * The two statements a balance is ever touched with. Both leave every race to PostgreSQL: the
 * partial unique indexes decide which of two first contacts creates the row, and the spend is one
 * conditional `UPDATE`, so the balance never goes negative. A database failure throws; it is
 * never reported as an empty balance.
 */
export function createMagicCoins(db: Kysely<Tables>) {
  return {
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

    /** Deducts one generation in a single statement; `false` when the balance did not cover it. */
    spend: async (owner: MagicCoinsOwner): Promise<boolean> => {
      const result = await db
        .updateTable('magic_coins')
        .set({ balance: sql`balance - ${GENERATION_COST}`, updated_at: sql`CURRENT_TIMESTAMP` })
        .where(ownerIs(owner))
        .where('balance', '>=', GENERATION_COST)
        .executeTakeFirst()
      return result.numUpdatedRows > 0n
    },
  }
}

/** The one row an owner's balance lives in. */
const ownerIs = (owner: MagicCoinsOwner) => (eb: ExpressionBuilder<Tables, 'magic_coins'>) =>
  owner.kind === 'user'
    ? eb('user_id', '=', owner.userId)
    : eb('guest_session_token', '=', owner.token)
