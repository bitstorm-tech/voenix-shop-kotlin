package shop.voenix.cart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * The only place that touches `carts` and `cart_items` — plus the one `print_images` row a cart
 * transaction has to decide together with its own write: the ownership check of an add. The
 * registry itself is [PrintImageRepository].
 *
 * Two rules shape every mutation here:
 *
 * 1. **One transaction per operation.** A mutation finds or creates the cart, writes its line, and
 *    reads the answer back inside one transaction, so a failure anywhere rolls back all of it and a
 *    caller never sees half a write.
 * 2. **The cart row is the lock.** Every mutation takes `SELECT … FOR UPDATE` on the cart before it
 *    reads anything it is about to write. That is what makes line merging and position assignment
 *    safe against a second request of the same customer: the two queue up instead of computing the
 *    same `max(position) + 1` twice.
 *
 * Creating the cart cannot use that lock — there is no row to lock yet — so it uses the database's
 * own authority instead: `INSERT … ON CONFLICT DO NOTHING` against the partial unique indexes over
 * active carts, followed by the locking re-select. Whoever loses the race simply reads the winner's
 * cart, and no preliminary existence query is involved, because that would race.
 */
internal class CartRepository(private val database: Database) {
    /** The active cart of this owner, or `null`. A read never creates anything. */
    suspend fun findActiveCart(owner: CartOwner): StoredCart? = read {
        activeCartIdInTransaction(owner)?.let(::cartInTransaction)
    }

    /**
     * Adds one line to the — possibly just created — cart of [owner], merging it into an identical
     * line when there is one. [priceCents] and [promptPriceCents] are the snapshots the service has
     * already resolved; the repository asks no catalog anything.
     *
     * The image the caller named is checked before the cart is found or created, so a rejected add
     * leaves nothing behind at all — not even an empty cart the customer never asked for.
     */
    suspend fun addItem(
        owner: CartOwner,
        input: AddCartItemInput,
        priceCents: Int,
        promptPriceCents: Int,
    ): CartWriteResult = write {
        val imageId = input.imageId
        if (imageId != null && !ownsPrintImageInTransaction(imageId, owner)) {
            return@write CartWriteResult.ImageNotOwned
        }
        val cartId = findOrCreateLockedCartInTransaction(owner)
        mergeOrInsertLineInTransaction(cartId, input, priceCents, promptPriceCents)
        touchCartInTransaction(cartId)
        CartWriteResult.Stored(cartInTransaction(cartId))
    }

    suspend fun updateQuantity(
        owner: CartOwner,
        itemId: Long,
        quantity: Int,
    ): CartWriteResult =
        writeToExistingCart(owner) { cartId ->
            CartItems.update({ lineOf(itemId, cartId) }) { statement ->
                statement[CartItems.quantity] = quantity
                statement[updatedAt] = CurrentTimestampWithTimeZone
            } > 0
        }

    suspend fun removeItem(
        owner: CartOwner,
        itemId: Long,
    ): CartWriteResult =
        writeToExistingCart(owner) { cartId ->
            CartItems.deleteWhere { lineOf(itemId, cartId) } > 0
        }

    /**
     * Stores [promotionId] on the active cart, replacing whatever was applied before — and removes
     * the promotion when it is `null`. Applying and removing are one write, because they differ in
     * nothing but that value.
     */
    suspend fun setPromotion(
        owner: CartOwner,
        promotionId: Long?,
    ): CartWriteResult =
        writeToExistingCart(owner) { cartId -> setPromotionInTransaction(cartId, promotionId) }

    /**
     * Closes cart [cartId] and reports whether this call was the one that did it.
     *
     * The `status = 'ACTIVE'` predicate is the whole mechanism: the database decides which of two
     * concurrent checkouts performed the transition, so the loser is told `false` instead of
     * overwriting a decision that was already made. A cart that does not exist answers `false` too
     * — for the caller both mean "there is nothing left to close".
     */
    suspend fun markCheckedOut(cartId: Long): Boolean = write {
        Carts.update({ (Carts.id eq cartId) and (Carts.status eq CART_STATUS_ACTIVE) }) { statement
            ->
            statement[status] = CART_STATUS_CHECKED_OUT
            statement[updatedAt] = CurrentTimestampWithTimeZone
        } > 0
    }

    /**
     * The active cart of [owner], created when there is none, and locked either way.
     *
     * One attempt is [createOrLockActiveCartInTransaction], and it answers `null` in exactly one
     * situation, which the Checkout migration made reachable: a concurrent checkout committed
     * `CHECKED_OUT` between this transaction's insert and its locking re-select, so the insert was
     * ignored against a cart that is no longer active and there is nothing left to lock. The cart
     * the customer is mutating has just been bought, and the right answer is a fresh active cart —
     * which the second attempt writes, because the partial unique index is free now.
     *
     * The retry is bounded rather than looped, exactly like `OrderRepository.place`: a second
     * `null` needs a *second* checkout to commit inside a second such window, and looping over that
     * would trade a vanishingly rare failure for an unbounded one. The residual `error` is
     * therefore reachable and deliberately loud; it names no guest token, because that token is a
     * bearer credential.
     */
    private fun findOrCreateLockedCartInTransaction(owner: CartOwner): Long =
        createOrLockActiveCartInTransaction(owner)
            ?: createOrLockActiveCartInTransaction(owner)
            ?: error("A cart was checked out twice in a row while its owner was mutating it")

    /**
     * Merges [input] into the identical line of this cart, or appends a new one behind the last
     * position. "Identical" is the whole snapshot — article, variant, price, image, prompt, and
     * prompt price — so two lines differing in any of them stay two lines, and a merge caps the
     * quantity at what one line may hold instead of failing the add.
     */
    private fun mergeOrInsertLineInTransaction(
        cartId: Long,
        input: AddCartItemInput,
        priceCents: Int,
        promptPriceCents: Int,
    ) {
        val articleId = checkNotNull(input.articleId)
        val variantId = checkNotNull(input.variantId)
        val quantity = checkNotNull(input.quantity)
        val existing =
            CartItems.selectAll()
                .where {
                    (CartItems.cartId eq cartId) and
                        (CartItems.articleId eq articleId) and
                        (CartItems.variantId eq variantId) and
                        (CartItems.priceCents eq priceCents) and
                        (CartItems.promptPriceCents eq promptPriceCents) and
                        CartItems.promptId.matches(input.promptId) and
                        CartItems.printImageId.matches(input.imageId)
                }
                .firstOrNull()

        if (existing != null) {
            val merged =
                (existing[CartItems.quantity] + quantity).coerceAtMost(MAXIMUM_LINE_QUANTITY)
            CartItems.update({ CartItems.id eq existing[CartItems.id] }) { statement ->
                statement[CartItems.quantity] = merged
                statement[updatedAt] = CurrentTimestampWithTimeZone
            }
            return
        }

        val nextPosition = nextPositionInTransaction(cartId)
        CartItems.insertAndGetId { statement ->
            statement[CartItems.cartId] = cartId
            statement[CartItems.articleId] = articleId
            statement[CartItems.variantId] = variantId
            statement[CartItems.quantity] = quantity
            statement[CartItems.priceCents] = priceCents
            statement[promptId] = input.promptId
            statement[CartItems.promptPriceCents] = promptPriceCents
            statement[printImageId] = input.imageId
            statement[position] = nextPosition
            statement[createdAt] = CurrentTimestampWithTimeZone
            statement[updatedAt] = CurrentTimestampWithTimeZone
        }
    }

    private suspend fun <T> read(operation: () -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                operation()
            }
        }

    /** One write transaction. */
    private suspend fun <T> write(operation: () -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                operation()
            }
        }

    /**
     * Runs [operation] against the locked, already existing active cart of [owner] and answers with
     * the recalculated cart.
     *
     * A caller without an active cart is [CartWriteResult.NotFound] before anything is written, and
     * so is an [operation] that reports `false` because it named a line of somebody else's cart.
     */
    private suspend fun writeToExistingCart(
        owner: CartOwner,
        operation: (Long) -> Boolean,
    ): CartWriteResult = write {
        val cartId = lockedActiveCartIdInTransaction(owner) ?: return@write CartWriteResult.NotFound
        if (!operation(cartId)) return@write CartWriteResult.NotFound
        touchCartInTransaction(cartId)
        CartWriteResult.Stored(cartInTransaction(cartId))
    }
}

/**
 * A cart exactly as the database holds it: the id, the promotion reference, and the lines in
 * position order.
 *
 * It is not the response. Everything a customer sees beyond these numbers — names, colors,
 * availability, the promotion behind the id, and every total — is resolved and calculated by the
 * service from current master data, which is why the repository stops here.
 */
internal data class StoredCart(
    val id: Long,
    val promotionId: Long?,
    val lines: List<Line>,
) {
    data class Line(
        val id: Long,
        val articleId: Long,
        val variantId: Long,
        val quantity: Int,
        val priceCents: Int,
        val promptId: Long?,
        val promptPriceCents: Int,
        val printImageId: Long?,
    )
}

/**
 * What a cart mutation can end in, apart from an unexpected database failure.
 *
 * The row lock removes every conflict the design could otherwise produce — concurrent adds queue up
 * behind the cart row instead of racing for a position — so what is left is genuinely small: the
 * cart or the line the caller named does not exist ([NotFound]), the caller named a print image
 * that is not theirs ([ImageNotOwned]), or the write went through ([Stored]).
 *
 * [ImageNotOwned] is the reason this type exists rather than a nullable [StoredCart]: "not yours"
 * must not read like "no cart", and a caller that gets both answers as `null` cannot tell them
 * apart.
 *
 * The ownership check runs inside the same transaction as the line write, but not because a check
 * outside it would race: a print image keeps the owner it was uploaded with for life, so there is
 * no concurrent write that could take a match away. It runs there because the ownership fact and
 * the line that relies on it must be decided together. Within that transaction the check comes
 * first, before the cart is found or created, so a rejected add commits nothing at all — not even
 * an empty cart the customer never asked for.
 */
internal sealed interface CartWriteResult {
    data class Stored(val cart: StoredCart) : CartWriteResult

    data object NotFound : CartWriteResult

    data object ImageNotOwned : CartWriteResult
}

internal object Carts : LongIdTable("carts") {
    val guestSessionToken = text("guest_session_token").nullable()
    val userId = long("user_id").nullable()
    val status = text("status")
    val promotionId = long("promotion_id").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

/** The status of the one cart a customer is currently filling. */
internal const val CART_STATUS_ACTIVE: String = "ACTIVE"

/**
 * The status of a cart a checkout has closed. Such carts are outside both partial unique indexes
 * over active carts, so an owner may carry any number of them, and the customer's next mutation
 * starts a new one.
 *
 * It is the only other status there is: a cart is being filled or it has been bought, and nothing
 * ever retires one for another reason.
 */
internal const val CART_STATUS_CHECKED_OUT: String = "CHECKED_OUT"

internal object CartItems : LongIdTable("cart_items") {
    val cartId = long("cart_id")
    val articleId = long("article_id")
    val variantId = long("variant_id")
    val quantity = integer("quantity")
    val priceCents = integer("price_cents")
    val promptId = long("prompt_id").nullable()
    val promptPriceCents = integer("prompt_price_cents")
    val printImageId = long("print_image_id").nullable()
    val position = integer("position")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

/** The largest quantity one cart line may carry; the database CHECK says the same. */
internal const val MAXIMUM_LINE_QUANTITY: Int = 99

/**
 * One attempt at creating or locking the active cart of [owner], or `null` when the cart the insert
 * conflicted with was checked out before the re-select could lock it.
 *
 * The insert comes first and is ignored on conflict, so two concurrent first mutations of the same
 * customer cannot produce two carts: the partial unique index decides, not a read.
 */
private fun createOrLockActiveCartInTransaction(owner: CartOwner): Long? {
    Carts.insertIgnore { statement ->
        // Exactly one identity is written, the one this owner's lookup uses: a signed-in customer's
        // cart is the user's, an anonymous visitor's is the token's. A row carrying both would stay
        // reachable from a browser that signed out.
        statement[Carts.userId] = owner.userId
        statement[Carts.guestSessionToken] = owner.guestToken.takeIf { owner.userId == null }
        statement[Carts.status] = CART_STATUS_ACTIVE
        statement[Carts.createdAt] = CurrentTimestampWithTimeZone
        statement[Carts.updatedAt] = CurrentTimestampWithTimeZone
    }
    return lockedActiveCartIdInTransaction(owner)
}

private fun setPromotionInTransaction(
    cartId: Long,
    promotionId: Long?,
): Boolean {
    val updated =
        Carts.update({ Carts.id eq cartId }) { statement ->
            statement[Carts.promotionId] = promotionId
        }
    return updated > 0
}

internal fun nextPositionInTransaction(cartId: Long): Int {
    val maximum = CartItems.position.max()
    val last = CartItems.select(maximum).where { CartItems.cartId eq cartId }.single()[maximum]
    return (last ?: 0) + 1
}

internal fun touchCartInTransaction(cartId: Long) {
    Carts.update({ Carts.id eq cartId }) { statement ->
        statement[updatedAt] = CurrentTimestampWithTimeZone
    }
}

private fun activeCartIdInTransaction(owner: CartOwner): Long? =
    Carts.select(Carts.id).where { activeCartPredicate(owner) }.singleOrNull()?.get(Carts.id)?.value

internal fun lockedActiveCartIdInTransaction(owner: CartOwner): Long? =
    Carts.select(Carts.id)
        .where { activeCartPredicate(owner) }
        .forUpdate()
        .singleOrNull()
        ?.get(Carts.id)
        ?.value

private fun cartInTransaction(cartId: Long): StoredCart {
    val cart = Carts.selectAll().where { Carts.id eq cartId }.single()
    return StoredCart(
        id = cartId,
        promotionId = cart[Carts.promotionId],
        lines =
            CartItems.selectAll()
                .where { CartItems.cartId eq cartId }
                .orderBy(CartItems.position to SortOrder.ASC)
                .map { row ->
                    StoredCart.Line(
                        id = row[CartItems.id].value,
                        articleId = row[CartItems.articleId],
                        variantId = row[CartItems.variantId],
                        quantity = row[CartItems.quantity],
                        priceCents = row[CartItems.priceCents],
                        promptId = row[CartItems.promptId],
                        promptPriceCents = row[CartItems.promptPriceCents],
                        printImageId = row[CartItems.printImageId],
                    )
                },
    )
}

/**
 * "The active cart of this owner": the user's when the request is signed in, the guest token's
 * otherwise. An owner with neither identity matches nothing, which is the honest answer — there is
 * no cart such a request could mean.
 */
private fun activeCartPredicate(owner: CartOwner): Op<Boolean> {
    val userId = owner.userId
    val guestToken = owner.guestToken
    val identity =
        when {
            userId != null -> Carts.userId eq userId
            guestToken != null -> Carts.guestSessionToken eq guestToken
            else -> Op.FALSE
        }
    return identity and (Carts.status eq CART_STATUS_ACTIVE)
}

private fun lineOf(
    itemId: Long,
    cartId: Long,
): Op<Boolean> = (CartItems.id eq itemId) and (CartItems.cartId eq cartId)

/** Equality that reads `null` as "the column is null" instead of as an always-false comparison. */
private fun Column<Long?>.matches(value: Long?): Op<Boolean> =
    if (value == null) isNull() else this eq value
