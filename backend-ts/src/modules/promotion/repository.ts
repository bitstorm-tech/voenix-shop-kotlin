import { sql, type Kysely, type Transaction } from 'kysely'
import { read, write, type Tables } from '@/platform/db/database'
import { executePostgresWrite } from '@/platform/db/postgres-write'
import type { PromotionInput } from './service'
import './schema'

export type DiscountType = 'PERCENTAGE' | 'FIXED_AMOUNT'

/**
 * The discount a promotion grants: a percentage above 0 and at most 100 with two decimals, or a
 * positive amount of whole cents. Serializes as `{ discountType, discountValue }`.
 */
export interface Discount {
  discountType: DiscountType
  discountValue: number
}

/** A stored promotion without the admin bookkeeping: what the coupon-code rules read. */
export interface StoredPromotion {
  id: number
  name: string
  couponCode: string
  discount: Discount
  startsAt: Date | null
  endsAt: Date | null
  usageLimitTotal: number | null
  usageLimitPerUser: number | null
  isActive: boolean
}

/**
 * The admin representation. `redemptionCount` and `isLocked` come from the recorded redemptions; a
 * locked promotion can no longer be reconfigured or deleted.
 */
export interface Promotion extends StoredPromotion {
  redemptionCount: number
  isLocked: boolean
}

export type PromotionRepository = ReturnType<typeof createPromotionRepository>

/**
 * The value of `coupon_code_normalized`, which carries the case-insensitive unique constraint.
 * Storing a code and looking one up go through here, so they cannot disagree.
 */
export const normalizedCouponCode = (couponCode: string): string => couponCode.trim().toUpperCase()

const columns = [
  'id',
  'name',
  'coupon_code',
  'discount_type',
  'discount_value',
  'starts_at',
  'ends_at',
  'usage_limit_total',
  'usage_limit_per_user',
  'is_active',
] as const

function selectPromotions(trx: Transaction<Tables>) {
  return trx.selectFrom('promotions').select(columns)
}

type PromotionRow = Awaited<
  ReturnType<ReturnType<typeof selectPromotions>['executeTakeFirstOrThrow']>
>

function toStoredPromotion(row: PromotionRow): StoredPromotion {
  return {
    id: row.id,
    name: row.name,
    couponCode: row.coupon_code,
    discount: { discountType: row.discount_type, discountValue: Number(row.discount_value) },
    startsAt: row.starts_at,
    endsAt: row.ends_at,
    usageLimitTotal: row.usage_limit_total,
    usageLimitPerUser: row.usage_limit_per_user,
    isActive: row.is_active,
  }
}

function withRedemptions(promotion: StoredPromotion, redemptionCount: number): Promotion {
  return { ...promotion, redemptionCount, isLocked: redemptionCount > 0 }
}

/** The promotions with these ids and their redemption counts, ordered by name, then id. */
async function promotionsWhere(
  trx: Transaction<Tables>,
  ids: readonly number[] | 'all',
): Promise<Promotion[]> {
  let query = trx
    .selectFrom('promotions')
    .select(columns)
    .select((eb) =>
      eb
        .selectFrom('promotion_redemptions')
        .select(eb.fn.countAll<number>().as('count'))
        .whereRef('promotion_redemptions.promotion_id', '=', 'promotions.id')
        .as('redemption_count'),
    )
    .orderBy('name')
    .orderBy('id')
  if (ids !== 'all') query = query.where('id', 'in', ids)
  const rows = await query.execute()
  return rows.map((row) => withRedemptions(toStoredPromotion(row), Number(row.redemption_count)))
}

/** The admin operations, each in a transaction of its own. */
export function createPromotionRepository(db: Kysely<Tables>) {
  return {
    list: () => read(db, (trx) => promotionsWhere(trx, 'all')),

    find: async (id: number): Promise<Promotion | undefined> =>
      (await read(db, (trx) => promotionsWhere(trx, [id])))[0],

    insert: (input: PromotionInput) =>
      executePostgresWrite({ uniqueViolation: 'conflict' }, () =>
        write(db, async (trx) => {
          const row = await trx
            .insertInto('promotions')
            .values(rowOf(input))
            .returning(columns)
            .executeTakeFirstOrThrow()
          return withRedemptions(toStoredPromotion(row), 0)
        }),
      ),

    /**
     * Replaces the configuration of an unredeemed promotion. The row lock comes first, so a
     * redemption racing this update is either committed before the count is read or waits until
     * the update has committed. A redeemed promotion still accepts a change of `isActive` alone.
     */
    update: (id: number, input: PromotionInput) =>
      executePostgresWrite({ uniqueViolation: 'conflict' }, () =>
        write(db, async (trx): Promise<Promotion | 'notFound' | 'locked'> => {
          const stored = await lockPromotion(trx, id)
          if (stored === undefined) return 'notFound'
          const redemptionCount = await countRedemptions(trx, id)
          if (redemptionCount === 0) {
            const row = await trx
              .updateTable('promotions')
              .set(rowOf(input))
              .where('id', '=', id)
              .returning(columns)
              .executeTakeFirstOrThrow()
            return withRedemptions(toStoredPromotion(row), 0)
          }
          if (!changesOnlyActivation(input, stored)) return 'locked'
          await trx
            .updateTable('promotions')
            .set({ is_active: input.isActive })
            .where('id', '=', id)
            .execute()
          return withRedemptions({ ...stored, isActive: input.isActive }, redemptionCount)
        }),
      ),

    /**
     * The number of rows deleted, or `'inUse'` when a redemption, a reservation, or an order still
     * restricts the delete. The SQL state cannot tell which one, so the answer does not either.
     */
    delete: (id: number) =>
      executePostgresWrite({ foreignKeyViolation: 'inUse' }, async () => {
        const result = await write(db, (trx) =>
          trx.deleteFrom('promotions').where('id', '=', id).executeTakeFirst(),
        )
        return Number(result.numDeletedRows)
      }),
  }
}

function rowOf(input: PromotionInput) {
  return {
    name: input.name,
    coupon_code: input.couponCode,
    coupon_code_normalized: normalizedCouponCode(input.couponCode),
    discount_type: input.discountType,
    discount_value: input.discountValue,
    starts_at: input.startsAt,
    ends_at: input.endsAt,
    usage_limit_total: input.usageLimitTotal,
    usage_limit_per_user: input.usageLimitPerUser,
    is_active: input.isActive,
  }
}

/**
 * Whether `input` leaves every configuration field of `stored` as it is, so at most `isActive`
 * differs. Timestamps compare as instants and the discount as a number, so
 * `2026-01-01T01:00:00+01:00` and `10` match the stored `2026-01-01T00:00:00Z` and `10.00`.
 */
function changesOnlyActivation(input: PromotionInput, stored: StoredPromotion): boolean {
  return (
    input.name === stored.name &&
    input.couponCode === stored.couponCode &&
    input.discountType === stored.discount.discountType &&
    input.discountValue === stored.discount.discountValue &&
    input.startsAt?.getTime() === stored.startsAt?.getTime() &&
    input.endsAt?.getTime() === stored.endsAt?.getTime() &&
    input.usageLimitTotal === stored.usageLimitTotal &&
    input.usageLimitPerUser === stored.usageLimitPerUser
  )
}

// The statements below run in the transaction they are given: the coupon-code capability decides
// which one that is (its own, or the caller's).

/** The promotion carrying this trimmed, upper-cased code, read without a lock. */
export async function promotionByCode(
  trx: Transaction<Tables>,
  normalizedCode: string,
): Promise<StoredPromotion | undefined> {
  const row = await selectPromotions(trx)
    .where('coupon_code_normalized', '=', normalizedCode)
    .executeTakeFirst()
  return row === undefined ? undefined : toStoredPromotion(row)
}

/** The promotions with these ids, read without a lock. */
export async function promotionsByIds(
  trx: Transaction<Tables>,
  ids: readonly number[],
): Promise<StoredPromotion[]> {
  const rows = await selectPromotions(trx).where('id', 'in', ids).execute()
  return rows.map(toStoredPromotion)
}

/**
 * The promotion `id` with its row locked until the transaction ends (`SELECT … FOR UPDATE`). The
 * admin update, `reserve`, and `redeem` all start here, which serializes them. Every count must be
 * read by a *later* statement: under `READ COMMITTED` that statement takes a fresh snapshot and
 * sees what the previous lock holder committed, while a count inside this statement would not.
 */
export async function lockPromotion(
  trx: Transaction<Tables>,
  id: number,
): Promise<StoredPromotion | undefined> {
  const row = await selectPromotions(trx).where('id', '=', id).forUpdate().executeTakeFirst()
  return row === undefined ? undefined : toStoredPromotion(row)
}

/** The redemptions of `promotionId`, narrowed to `userId` when one is given. */
export async function countRedemptions(
  trx: Transaction<Tables>,
  promotionId: number,
  userId?: number,
): Promise<number> {
  let query = trx
    .selectFrom('promotion_redemptions')
    .select((eb) => eb.fn.countAll<number>().as('count'))
    .where('promotion_id', '=', promotionId)
  if (userId !== undefined) query = query.where('user_id', '=', userId)
  return (await query.executeTakeFirstOrThrow()).count
}

/**
 * The reservations of `promotionId` held by every cart except `excludedCartId`, the caller's own
 * hold, narrowed to `userId` when one is given.
 */
export async function countReservations(
  trx: Transaction<Tables>,
  promotionId: number,
  excludedCartId: number,
  userId?: number,
): Promise<number> {
  let query = trx
    .selectFrom('promotion_reservations')
    .select((eb) => eb.fn.countAll<number>().as('count'))
    .where('promotion_id', '=', promotionId)
    .where('cart_id', '<>', excludedCartId)
  if (userId !== undefined) query = query.where('user_id', '=', userId)
  return (await query.executeTakeFirstOrThrow()).count
}

/**
 * Holds `promotionId` for `cartId`, overwriting whatever that cart held. The unique `cart_id` is
 * what turns a repeated checkout into an update instead of a second unit of capacity.
 */
export async function holdReservation(
  trx: Transaction<Tables>,
  promotionId: number,
  cartId: number,
  userId: number | null,
): Promise<void> {
  await trx
    .insertInto('promotion_reservations')
    .values({ promotion_id: promotionId, cart_id: cartId, user_id: userId })
    .onConflict((conflict) =>
      conflict.column('cart_id').doUpdateSet({
        promotion_id: promotionId,
        user_id: userId,
        created_at: sql<Date>`CURRENT_TIMESTAMP`,
      }),
    )
    .execute()
}

/** Gives the reservation of `cartId` back. A cart that holds none is a normal outcome. */
export async function releaseReservation(trx: Transaction<Tables>, cartId: number): Promise<void> {
  await trx.deleteFrom('promotion_reservations').where('cart_id', '=', cartId).execute()
}

/** Records that `orderId` redeemed `promotionId`. A second redemption of one order is a `23505`. */
export async function insertRedemption(
  trx: Transaction<Tables>,
  promotionId: number,
  orderId: number,
  userId: number | null,
): Promise<void> {
  await trx
    .insertInto('promotion_redemptions')
    .values({
      promotion_id: promotionId,
      order_id: orderId,
      user_id: userId,
      redeemed_at: sql<Date>`CURRENT_TIMESTAMP`,
    })
    .execute()
}
