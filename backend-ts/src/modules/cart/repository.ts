import { sql, type ExpressionBuilder, type Kysely, type Transaction } from 'kysely'
import type { GuestImageResolver } from '@/modules/image'
import type { PromotionCodes } from '@/modules/promotion'
import { read, write, type Tables } from '@/platform/db/database'
import './schema'

/** The largest quantity one cart line may hold; the CHECK of `cart_items.quantity` says the same. */
export const MAXIMUM_LINE_QUANTITY = 99

/**
 * Who a cart operation is for. A signed-in request finds and creates its cart by `userId`, an
 * anonymous one by `guestToken`; a cart never carries both, and a login moves no cart (issues #77,
 * #110). The token still matters for a signed-in caller, because a print image belongs to its token
 * *or* its user. A request with neither identity means no cart at all.
 */
export interface CartOwner {
  guestToken: string | null
  userId: number | null
}

/** One stored line: the references and the price snapshots taken when it was added. */
export interface StoredLine {
  id: number
  articleId: number
  variantId: number
  quantity: number
  priceCents: number
  promptId: number | null
  promptPriceCents: number
  printImageId: number | null
}

/** A cart exactly as the database holds it: the id, the promotion reference, the lines in position order. */
export interface StoredCart {
  id: number
  promotionId: number | null
  lines: StoredLine[]
}

/** A line to add, with the snapshots the service has already resolved. */
export type NewLine = Omit<StoredLine, 'id'>

export type CartRepository = ReturnType<typeof createCartRepository>

/**
 * The only place that touches `carts`, `cart_items`, and `print_images`. Two rules shape every
 * mutation:
 *
 * 1. **One transaction per operation.** A mutation finds or creates the cart, writes, and reads the
 *    answer back in one transaction.
 * 2. **The cart row is the lock.** Every mutation takes `SELECT … FOR UPDATE` on the cart before it
 *    reads what it is about to write, so line merging and `max(position) + 1` are safe against a
 *    second request of the same customer: the two queue up.
 *
 * Creating a cart cannot lock a row that does not exist yet, so it inserts with `ON CONFLICT DO
 * NOTHING` against the partial unique indexes over active carts and then re-selects with the lock:
 * whoever loses the race reads the winner's cart.
 */
export function createCartRepository(
  db: Kysely<Tables>,
  promotionCodes: Pick<PromotionCodes, 'release'>,
) {
  /**
   * Runs `operation` on the locked active cart of `owner` and answers the cart afterwards, or
   * `undefined` when there is no active cart or `operation` named nothing of it.
   */
  const writeToExistingCart = (
    owner: CartOwner,
    operation: (trx: Transaction<Tables>, cartId: number) => Promise<boolean>,
  ): Promise<StoredCart | undefined> =>
    write(db, async (trx) => {
      const cartId = await activeCartId(trx, owner, { lock: true })
      if (cartId === undefined || !(await operation(trx, cartId))) return undefined
      await touch(trx, cartId)
      return storedCart(trx, cartId)
    })

  return {
    /** The active cart of `owner`, or `undefined`. A read never creates anything. */
    findActiveCart: (owner: CartOwner): Promise<StoredCart | undefined> =>
      read(db, async (trx) => {
        const cartId = await activeCartId(trx, owner, { lock: false })
        return cartId === undefined ? undefined : storedCart(trx, cartId)
      }),

    /**
     * Adds `line` to the, possibly just created, cart of `owner`, merging it into the identical
     * line when there is one. The image is checked first, so a rejected add leaves nothing behind,
     * not even an empty cart.
     */
    addItem: (owner: CartOwner, line: NewLine): Promise<StoredCart | 'imageNotOwned'> =>
      write(db, async (trx) => {
        if (line.printImageId !== null) {
          const image = await trx
            .selectFrom('print_images')
            .select('id')
            .where('id', '=', line.printImageId)
            .where(ownedBy(owner))
            .executeTakeFirst()
          if (image === undefined) return 'imageNotOwned'
        }
        const cartId = await lockedOrCreatedCartId(trx, owner)
        await mergeOrInsertLine(trx, cartId, line)
        await touch(trx, cartId)
        return storedCart(trx, cartId)
      }),

    updateQuantity: (owner: CartOwner, itemId: number, quantity: number) =>
      writeToExistingCart(owner, async (trx, cartId) => {
        const result = await trx
          .updateTable('cart_items')
          .set({ quantity, updated_at: sql<Date>`now()` })
          .where('id', '=', itemId)
          .where('cart_id', '=', cartId)
          .executeTakeFirst()
        return result.numUpdatedRows > 0n
      }),

    removeItem: (owner: CartOwner, itemId: number) =>
      writeToExistingCart(owner, async (trx, cartId) => {
        const result = await trx
          .deleteFrom('cart_items')
          .where('id', '=', itemId)
          .where('cart_id', '=', cartId)
          .executeTakeFirst()
        return result.numDeletedRows > 0n
      }),

    /** Stores `promotionId` on the active cart, replacing whatever was applied before. */
    applyPromotion: (owner: CartOwner, promotionId: number) =>
      writeToExistingCart(owner, async (trx, cartId) => {
        await setPromotion(trx, cartId, promotionId)
        return true
      }),

    /**
     * Takes the coupon off the active cart and gives back the reservation the cart still holds, in
     * one transaction. A checkout that ended without an order (a refused payment) leaves the cart
     * active and its reservation standing, and reservations do not expire; dropping the code is
     * the customer's usual next move, and nothing else would ever release that capacity.
     */
    removePromotion: (owner: CartOwner) =>
      writeToExistingCart(owner, async (trx, cartId) => {
        await setPromotion(trx, cartId, null)
        await promotionCodes.release(trx, cartId)
        return true
      }),

    /**
     * Locks cart `cartId` in the caller's transaction (checkout TX1) and answers whether it is
     * still active. A cart mutation waits behind this lock, and a second checkout of the same cart
     * as well, until the caller commits.
     */
    lockForCheckout: async (trx: Transaction<Tables>, cartId: number): Promise<boolean> => {
      const cart = await trx
        .selectFrom('carts')
        .select('status')
        .where('id', '=', cartId)
        .forUpdate()
        .executeTakeFirst()
      return cart?.status === 'ACTIVE'
    },

    /**
     * Closes cart `cartId` in the caller's transaction: `ACTIVE` becomes `CHECKED_OUT`, and the
     * customer's next add starts a fresh cart. `true` means this call performed the transition,
     * `false` that the cart was already checked out or does not exist; neither is a failure.
     */
    markCheckedOut: async (trx: Transaction<Tables>, cartId: number): Promise<boolean> => {
      const result = await trx
        .updateTable('carts')
        .set({ status: 'CHECKED_OUT', updated_at: sql<Date>`now()` })
        .where('id', '=', cartId)
        .where('status', '=', 'ACTIVE')
        .executeTakeFirst()
      return result.numUpdatedRows > 0n
    },

    /**
     * Registers an uploaded file as a print image of `owner` and answers its id. An upload made
     * while signed in stores **both** identities: `fk_print_images_user` is `ON DELETE SET NULL`,
     * so a row without the token would become unreachable with the account.
     */
    insertPrintImage: async (owner: CartOwner, filename: string): Promise<number> => {
      const { id } = await write(db, (trx) =>
        trx
          .insertInto('print_images')
          .values({ filename, guest_session_token: owner.guestToken, user_id: owner.userId })
          .returning('id')
          .executeTakeFirstOrThrow(),
      )
      return id
    },

    /**
     * The file name of print image `imageId` when it belongs to the caller, `null` otherwise,
     * including when it does not exist: the guest image route answers both with `404`, so an id
     * cannot be probed. This is the cart's `GuestImageResolver`.
     */
    printImageFilename: (async ({ imageId, guestToken, userId }) => {
      const image = await read(db, (trx) =>
        trx
          .selectFrom('print_images')
          .select('filename')
          .where('id', '=', imageId)
          .where(ownedBy({ guestToken, userId }))
          .executeTakeFirst(),
      )
      return image?.filename ?? null
    }) satisfies GuestImageResolver,
  }
}

/**
 * "The active cart of this owner": the user's when the request is signed in, the token's
 * otherwise; an owner with neither identity has none.
 */
async function activeCartId(
  trx: Transaction<Tables>,
  owner: CartOwner,
  { lock }: { lock: boolean },
): Promise<number | undefined> {
  const query = trx
    .selectFrom('carts')
    .select('id')
    .where('status', '=', 'ACTIVE')
    .where((eb) => {
      if (owner.userId !== null) return eb('user_id', '=', owner.userId)
      if (owner.guestToken !== null) return eb('guest_session_token', '=', owner.guestToken)
      return eb.or([])
    })
  const cart = await (lock ? query.forUpdate() : query).executeTakeFirst()
  return cart?.id
}

/**
 * The active cart of `owner`, created when there is none, and locked either way.
 *
 * One attempt inserts (ignored on conflict) and re-selects with the lock. It finds nothing in
 * exactly one situation: a checkout committed `CHECKED_OUT` between the insert and the re-select,
 * so the insert was ignored against a cart that is no longer active. The second attempt then
 * writes the fresh cart, because the partial unique index is free. A second miss needs a second
 * checkout inside a second such window; it throws instead of looping.
 */
async function lockedOrCreatedCartId(trx: Transaction<Tables>, owner: CartOwner): Promise<number> {
  for (let attempt = 0; attempt < 2; attempt++) {
    await trx
      .insertInto('carts')
      .values({
        // Exactly the identity this owner's lookup uses: a row carrying both would stay
        // reachable from a browser that signed out.
        user_id: owner.userId,
        guest_session_token: owner.userId === null ? owner.guestToken : null,
        status: 'ACTIVE',
      })
      .onConflict((conflict) => conflict.doNothing())
      .execute()
    const cartId = await activeCartId(trx, owner, { lock: true })
    if (cartId !== undefined) return cartId
  }
  // No guest token in the message: it is a bearer credential.
  throw new Error('A cart was checked out twice in a row while its owner was mutating it')
}

/**
 * Merges `line` into the identical line of the cart, or appends it behind the last position.
 * "Identical" is the whole snapshot (article, variant, price, prompt, prompt price, image), and a
 * merge caps the quantity at what one line may hold instead of failing the add.
 */
async function mergeOrInsertLine(
  trx: Transaction<Tables>,
  cartId: number,
  line: NewLine,
): Promise<void> {
  const existing = await trx
    .selectFrom('cart_items')
    .select(['id', 'quantity'])
    .where('cart_id', '=', cartId)
    .where('article_id', '=', line.articleId)
    .where('variant_id', '=', line.variantId)
    .where('price_cents', '=', line.priceCents)
    .where('prompt_price_cents', '=', line.promptPriceCents)
    .where('prompt_id', 'is not distinct from', line.promptId)
    .where('print_image_id', 'is not distinct from', line.printImageId)
    .executeTakeFirst()
  if (existing !== undefined) {
    await trx
      .updateTable('cart_items')
      .set({
        quantity: Math.min(existing.quantity + line.quantity, MAXIMUM_LINE_QUANTITY),
        updated_at: sql<Date>`now()`,
      })
      .where('id', '=', existing.id)
      .execute()
    return
  }
  const { last } = await trx
    .selectFrom('cart_items')
    .select((eb) => eb.fn.coalesce(eb.fn.max('position'), eb.lit(0)).as('last'))
    .where('cart_id', '=', cartId)
    .executeTakeFirstOrThrow()
  await trx
    .insertInto('cart_items')
    .values({
      cart_id: cartId,
      article_id: line.articleId,
      variant_id: line.variantId,
      quantity: line.quantity,
      price_cents: line.priceCents,
      prompt_id: line.promptId,
      prompt_price_cents: line.promptPriceCents,
      print_image_id: line.printImageId,
      position: last + 1,
    })
    .execute()
}

async function setPromotion(
  trx: Transaction<Tables>,
  cartId: number,
  promotionId: number | null,
): Promise<void> {
  await trx
    .updateTable('carts')
    .set({ promotion_id: promotionId })
    .where('id', '=', cartId)
    .execute()
}

async function touch(trx: Transaction<Tables>, cartId: number): Promise<void> {
  await trx
    .updateTable('carts')
    .set({ updated_at: sql<Date>`now()` })
    .where('id', '=', cartId)
    .execute()
}

async function storedCart(trx: Transaction<Tables>, cartId: number): Promise<StoredCart> {
  const cart = await trx
    .selectFrom('carts')
    .select('promotion_id')
    .where('id', '=', cartId)
    .executeTakeFirstOrThrow()
  const lines = await trx
    .selectFrom('cart_items')
    .select([
      'id',
      'article_id as articleId',
      'variant_id as variantId',
      'quantity',
      'price_cents as priceCents',
      'prompt_id as promptId',
      'prompt_price_cents as promptPriceCents',
      'print_image_id as printImageId',
    ])
    .where('cart_id', '=', cartId)
    .orderBy('position')
    .execute()
  return { id: cartId, promotionId: cart.promotion_id, lines }
}

/**
 * "This print image belongs to the caller": an image that carries a user id belongs to that user
 * alone, and the guest token identifies an image only while it has no user. Since issue #110 the
 * guest cookie survives login, logout, and registration, so without the `user_id IS NULL` check
 * the next person on a shared browser would reach the previous customer's signed-in uploads. A
 * caller with neither identity matches nothing.
 */
function ownedBy({ guestToken, userId }: CartOwner) {
  return (eb: ExpressionBuilder<Tables, 'print_images'>) => {
    const branches = []
    if (userId !== null) branches.push(eb('user_id', '=', userId))
    if (guestToken !== null) {
      branches.push(eb.and([eb('user_id', 'is', null), eb('guest_session_token', '=', guestToken)]))
    }
    return eb.or(branches)
  }
}
