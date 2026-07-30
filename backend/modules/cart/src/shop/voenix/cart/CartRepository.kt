package shop.voenix.cart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * The only place that touches `carts`, `cart_items`, and `print_images`.
 *
 * Two rules shape every mutation here:
 *
 * 1. **One transaction per operation.** A mutation finds or creates the cart, writes its line, and
 *    reads the answer back inside one transaction, so a failure anywhere rolls back all of it and a
 *    caller never sees half a write.
 * 2. **The cart row is the lock.** Every mutation takes `SELECT … FOR UPDATE` on the cart before it
 *    reads anything it is about to write. That is what makes merging, position assignment, and
 *    adopting a signed-in user safe against a second request of the same customer: the two queue up
 *    instead of computing the same `max(position) + 1` twice.
 *
 * Creating the cart cannot use that lock — there is no row to lock yet — so it uses the database's
 * own authority instead: `INSERT … ON CONFLICT DO NOTHING` against the partial unique index over
 * active carts, followed by the locking re-select. Whoever loses the race simply reads the winner's
 * cart, and no preliminary existence query is involved, because that would race.
 */
internal class CartRepository(private val database: Database) {
    /** The active cart of this guest token, or `null`. A read never creates anything. */
    suspend fun findActiveCart(owner: CartOwner): StoredCart? = read {
        activeCartIdInTransaction(owner.guestToken)?.let(::cartInTransaction)
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

    /** Stores [promotionId] on the active cart, replacing whatever was applied before. */
    suspend fun applyPromotion(
        owner: CartOwner,
        promotionId: Long,
    ): CartWriteResult =
        writeToExistingCart(owner) { cartId -> setPromotionInTransaction(cartId, promotionId) }

    suspend fun removePromotion(owner: CartOwner): CartWriteResult =
        writeToExistingCart(owner) { cartId -> setPromotionInTransaction(cartId, null) }

    /** Registers an uploaded file as a print image of [owner] and returns the id it is used by. */
    suspend fun insertPrintImage(
        owner: CartOwner,
        filename: String,
    ): Long =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                PrintImages.insertAndGetId { statement ->
                        statement[PrintImages.filename] = filename
                        statement[guestSessionToken] = owner.guestToken
                        statement[userId] = owner.userId
                        statement[createdAt] = CurrentTimestampWithTimeZone
                    }
                    .value
            }
        }

    /**
     * The file name of print image [imageId] when it belongs to the caller, and `null` otherwise —
     * including when it does not exist at all. The two cases are deliberately indistinguishable,
     * because the guest delivery route answers both with `404`, so an id cannot be probed.
     */
    suspend fun findPrintImage(
        imageId: Long,
        guestToken: String?,
        userId: Long?,
    ): String? = read {
        PrintImages.select(PrintImages.filename)
            .where { (PrintImages.id eq imageId) and ownershipPredicate(guestToken, userId) }
            .singleOrNull()
            ?.get(PrintImages.filename)
    }

    /**
     * Moves what the guest [guestToken] owns to [userId]: the carts and the print images that have
     * no user yet.
     *
     * The `user_id IS NULL` predicate is what makes the claim idempotent and safe at once. A second
     * run changes nothing, and no run can ever take a row away from another account.
     */
    suspend fun claimGuestData(
        guestToken: String,
        userId: Long,
    ) {
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                Carts.update({
                    (Carts.guestSessionToken eq guestToken) and Carts.userId.isNull()
                }) { statement ->
                    statement[Carts.userId] = userId
                    statement[Carts.updatedAt] = CurrentTimestampWithTimeZone
                }
                PrintImages.update({
                    (PrintImages.guestSessionToken eq guestToken) and PrintImages.userId.isNull()
                }) { statement ->
                    statement[PrintImages.userId] = userId
                }
            }
        }
    }

    /**
     * The active cart of [owner], created when there is none, and locked either way.
     *
     * The insert comes first and is ignored on conflict, so two concurrent first mutations of the
     * same guest cannot produce two carts: the partial unique index decides, not a read.
     *
     * The `checkNotNull` below is reachable in exactly one situation that does not exist yet: a
     * concurrent transaction checking the cart out between the insert and the locking re-select
     * would leave no active cart to lock, and the resulting `IllegalStateException` would escape
     * `CartService.databaseOperation`, which only catches `SQLException`. Nothing writes
     * `CHECKED_OUT` today — that path belongs to the deferred Checkout migration, which must decide
     * then whether this becomes a retry or an expected result. It is deliberately not a retry loop
     * now, so the assumption stays visible instead of being silently handled.
     */
    private fun findOrCreateLockedCartInTransaction(owner: CartOwner): Long {
        Carts.insertIgnore { statement ->
            statement[guestSessionToken] = owner.guestToken
            statement[userId] = owner.userId
            statement[status] = CART_STATUS_ACTIVE
            statement[createdAt] = CurrentTimestampWithTimeZone
            statement[updatedAt] = CurrentTimestampWithTimeZone
        }
        val cartId =
            checkNotNull(lockedActiveCartIdInTransaction(owner.guestToken)) {
                "The active cart vanished inside the transaction that created it"
            }
        adoptUserInTransaction(cartId, owner)
        return cartId
    }

    /** Adopts a signed-in customer onto a cart that still belongs to the anonymous visitor. */
    private fun adoptUserInTransaction(
        cartId: Long,
        owner: CartOwner,
    ) {
        val userId = owner.userId ?: return
        Carts.update({ (Carts.id eq cartId) and Carts.userId.isNull() }) { statement ->
            statement[Carts.userId] = userId
            statement[Carts.updatedAt] = CurrentTimestampWithTimeZone
        }
    }

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

    private suspend fun write(operation: () -> CartWriteResult): CartWriteResult =
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
        val cartId =
            lockedActiveCartIdInTransaction(owner.guestToken)
                ?: return@write CartWriteResult.NotFound
        adoptUserInTransaction(cartId, owner)
        if (!operation(cartId)) return@write CartWriteResult.NotFound
        touchCartInTransaction(cartId)
        CartWriteResult.Stored(cartInTransaction(cartId))
    }
}

private fun setPromotionInTransaction(
    cartId: Long,
    promotionId: Long?,
): Boolean {
    Carts.update({ Carts.id eq cartId }) { statement -> statement[Carts.promotionId] = promotionId }
    return true
}

private fun nextPositionInTransaction(cartId: Long): Int {
    val maximum = CartItems.position.max()
    val last = CartItems.select(maximum).where { CartItems.cartId eq cartId }.single()[maximum]
    return (last ?: 0) + 1
}

private fun touchCartInTransaction(cartId: Long) {
    Carts.update({ Carts.id eq cartId }) { statement ->
        statement[updatedAt] = CurrentTimestampWithTimeZone
    }
}

private fun ownsPrintImageInTransaction(
    imageId: Long,
    owner: CartOwner,
): Boolean =
    PrintImages.select(PrintImages.id)
        .where {
            (PrintImages.id eq imageId) and ownershipPredicate(owner.guestToken, owner.userId)
        }
        .singleOrNull() != null

private fun activeCartIdInTransaction(guestToken: String): Long? =
    Carts.select(Carts.id)
        .where { activeCartPredicate(guestToken) }
        .singleOrNull()
        ?.get(Carts.id)
        ?.value

private fun lockedActiveCartIdInTransaction(guestToken: String): Long? =
    Carts.select(Carts.id)
        .where { activeCartPredicate(guestToken) }
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

private fun activeCartPredicate(guestToken: String): Op<Boolean> =
    (Carts.guestSessionToken eq guestToken) and (Carts.status eq CART_STATUS_ACTIVE)

private fun lineOf(
    itemId: Long,
    cartId: Long,
): Op<Boolean> = (CartItems.id eq itemId) and (CartItems.cartId eq cartId)

/**
 * "This image belongs to the caller": the stored guest token matches, or the caller is the
 * signed-in user the image was claimed by. A request carrying neither identity matches nothing.
 */
private fun ownershipPredicate(
    guestToken: String?,
    userId: Long?,
): Op<Boolean> =
    when {
        guestToken != null && userId != null ->
            (PrintImages.guestSessionToken eq guestToken) or (PrintImages.userId eq userId)
        guestToken != null -> PrintImages.guestSessionToken eq guestToken
        userId != null -> PrintImages.userId eq userId
        else -> Op.FALSE
    }

/** Equality that reads `null` as "the column is null" instead of as an always-false comparison. */
private fun Column<Long?>.matches(value: Long?): Op<Boolean> =
    if (value == null) isNull() else this eq value
