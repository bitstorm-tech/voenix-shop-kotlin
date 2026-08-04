package shop.voenix.cart

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/**
 * What a login does to what a visitor left behind, inside the transaction [CartRepository] opens
 * for it.
 *
 * It lives next to the repository rather than in it because it is a rule of its own: every other
 * write here belongs to one cart, this one decides between *two*. The tables it touches are still
 * the repository's, and it never opens a transaction — the caller owns that, so the print images,
 * the merged lines, and the retired cart commit together or not at all.
 */
internal object CartClaim {
    /**
     * Moves the print images and the cart of [guestToken] to [userId].
     *
     * The two rows differ on purpose. A print image is only ever *added* to an account and keeps
     * its token, because it belongs to its token **or** its user. The cart changes identity
     * instead: it gains the user id and gives up the token, or — when the customer already has an
     * active cart — its lines move into that one and the emptied cart is retired.
     */
    fun runInTransaction(
        guestToken: String,
        userId: Long,
    ): CartClaimResult {
        PrintImages.update({
            (PrintImages.guestSessionToken eq guestToken) and PrintImages.userId.isNull()
        }) { statement ->
            statement[PrintImages.userId] = userId
        }

        // The visitor's cart first, the customer's second, always in that order: two claims of the
        // same customer then queue up behind one row instead of deadlocking over two.
        val guestCartId = lockedActiveCartIdInTransaction(CartOwner(guestToken, userId = null))
        val userCartId = guestCartId?.let {
            lockedActiveCartIdInTransaction(CartOwner(guestToken = null, userId))
        }

        return when {
            guestCartId == null -> CartClaimResult.Claimed(retiredCartId = null)
            userCartId == null -> {
                adoptInTransaction(guestCartId, userId)
                CartClaimResult.Claimed(retiredCartId = null)
            }
            else -> {
                mergeInTransaction(from = guestCartId, into = userCartId)
                CartClaimResult.Claimed(retiredCartId = guestCartId)
            }
        }
    }

    /**
     * Turns the anonymous cart [cartId] into the cart of [userId]: one identity replaces the other.
     */
    private fun adoptInTransaction(
        cartId: Long,
        userId: Long,
    ) {
        Carts.update({ Carts.id eq cartId }) { statement ->
            statement[Carts.userId] = userId
            statement[guestSessionToken] = null
            statement[updatedAt] = CurrentTimestampWithTimeZone
        }
    }

    /**
     * Moves every line of cart [from] into cart [into] and retires the emptied cart.
     *
     * Two lines are the same line when they carry the same variant and the same print image — the
     * rule the login merge was decided with. It is deliberately coarser than the one an add uses,
     * which compares the whole snapshot: the visitor and the customer were quoted their prices at
     * different moments, and showing the same mug twice for that reason would be a worse answer
     * than one merged line. A merged quantity stops at what one line may hold, exactly like an add.
     */
    private fun mergeInTransaction(
        from: Long,
        into: Long,
    ) {
        adoptPromotionInTransaction(from, into)

        // The lines of the receiving cart, keyed by what makes two lines mergeable. Two of them can
        // share that key — they differ in their prompt, say — and then the first one wins, so the
        // merge is decided by position and not by whatever order the database returns rows in.
        val targets = mutableMapOf<Pair<Long, Long?>, Pair<Long, Int>>()
        CartItems.selectAll()
            .where { CartItems.cartId eq into }
            .orderBy(CartItems.position to SortOrder.ASC)
            .forEach { line -> targets.putIfAbsent(line.mergeKey(), line.idAndQuantity()) }

        var nextPosition = nextPositionInTransaction(into)
        val moved =
            CartItems.selectAll()
                .where { CartItems.cartId eq from }
                .orderBy(CartItems.position to SortOrder.ASC)
                .toList()

        moved.forEach { line ->
            val key = line.mergeKey()
            val target = targets[key]
            if (target == null) {
                CartItems.update({ CartItems.id eq line[CartItems.id] }) { statement ->
                    statement[cartId] = into
                    statement[position] = nextPosition
                    statement[updatedAt] = CurrentTimestampWithTimeZone
                }
                targets[key] = line.idAndQuantity()
                nextPosition++
            } else {
                val (targetId, targetQuantity) = target
                val merged =
                    (targetQuantity + line[CartItems.quantity]).coerceAtMost(MAXIMUM_LINE_QUANTITY)
                CartItems.update({ CartItems.id eq targetId }) { statement ->
                    statement[quantity] = merged
                    statement[updatedAt] = CurrentTimestampWithTimeZone
                }
                targets[key] = targetId to merged
                CartItems.deleteWhere { CartItems.id eq line[CartItems.id] }
            }
        }

        touchCartInTransaction(into)
        Carts.update({ Carts.id eq from }) { statement ->
            statement[status] = CART_STATUS_MERGED
            statement[updatedAt] = CurrentTimestampWithTimeZone
        }
    }

    /**
     * Gives cart [into] the coupon of cart [from], but only when it carries none of its own: what
     * the customer already had wins.
     */
    private fun adoptPromotionInTransaction(
        from: Long,
        into: Long,
    ) {
        val promotionId =
            Carts.select(Carts.promotionId).where { Carts.id eq from }.single()[Carts.promotionId]
                ?: return
        Carts.update({ (Carts.id eq into) and Carts.promotionId.isNull() }) { statement ->
            statement[Carts.promotionId] = promotionId
        }
    }

    /** What makes two lines the same line for the login merge: the variant and the print image. */
    private fun ResultRow.mergeKey(): Pair<Long, Long?> =
        this[CartItems.variantId] to this[CartItems.printImageId]

    private fun ResultRow.idAndQuantity(): Pair<Long, Int> =
        this[CartItems.id].value to this[CartItems.quantity]
}
