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
 * the merged lines, the retired cart, and the promotion capacity that cart gives back commit
 * together or not at all.
 *
 * One thing it deliberately does not decide again: whether the coupon a guest cart brings along may
 * be used by the customer who is signing in. An adopted code can carry a per-user limit that this
 * account has already exhausted, and then the checkout's `reserve` — the step that decides —
 * refuses it. That refusal is recoverable and stays with the customer: they see the coupon on their
 * cart and can drop it, exactly as if they had entered it themselves.
 */
internal object CartClaim {
    /**
     * Moves the print images and the cart of [guestToken] to [userId].
     *
     * The two rows differ on purpose. A print image keeps its token — not as anyone's handle (the
     * login that claims it rotates the browser's cookie away), but because `ck_print_images_owner`
     * requires an owner to remain when `fk_print_images_user`'s `ON DELETE SET NULL` fires — and it
     * belongs to the user from here on: the token identifies an image only while it is unclaimed,
     * which is why this `WHERE` and `ownershipPredicate` ask the same `user_id IS NULL`. The cart
     * changes identity instead: it gains the user id and gives up the token, or — when the customer
     * already has an active cart — its lines move into that one and the emptied cart is retired.
     *
     * [backsLiveOrder] and [releaseReservation] are the two things this rule needs from other
     * modules, and both are called *inside* this transaction: the first is the order module's
     * answer to "does this cart already back an order somebody may still pay?", the second is the
     * promotion module giving a retired cart's hold back. Passing them as functions keeps the
     * decision here and the capabilities in the composition root, exactly as `OrderRepository`
     * takes the redemption of a payment.
     *
     * The release inside the transaction buys atomicity at the price of a lock edge: the claim
     * holds `FOR UPDATE` on the guest cart while it deletes that cart's reservation row, and a
     * concurrent guest checkout of the same cart takes the two the other way round (the
     * reservation's upsert first, then the foreign key's `FOR KEY SHARE` on the cart). Two tabs —
     * one checking out, one logging in — can therefore deadlock; PostgreSQL detects it within its
     * `deadlock_timeout` and aborts one side with `40P01`. On this side that abort travels the
     * ordinary failure path: the claim is best effort, the login keeps the guest cookie, and the
     * next login claims again. Documented in `persistence-error-handling.md`.
     */
    suspend fun runInTransaction(
        guestToken: String,
        userId: Long,
        backsLiveOrder: suspend (cartId: Long) -> Boolean,
        releaseReservation: suspend (cartId: Long) -> Unit,
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
            guestCartId == null -> CartClaimResult.Claimed
            userCartId == null -> {
                adoptInTransaction(guestCartId, userId)
                CartClaimResult.Claimed
            }
            // A guest cart that already backs an order is retired as it stands, and nothing of it
            // is taken anywhere else.
            backsLiveOrder(guestCartId) -> {
                retireInTransaction(guestCartId)
                CartClaimResult.Claimed
            }
            else -> {
                mergeInTransaction(from = guestCartId, into = userCartId)
                releaseReservation(guestCartId)
                CartClaimResult.Claimed
            }
        }
    }

    /**
     * Turns the anonymous cart [cartId] into the cart of [userId]: one identity replaces the other.
     *
     * This is the one branch an order in flight changes nothing about. The cart keeps its id, so an
     * order placed from it still names the cart it was placed from, the double-placement index
     * still dedupes a second checkout of it, and its reservation stays where that order's
     * redemption expects it.
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
     * Retires the guest cart [cartId] with everything still on it: its lines, its coupon, and the
     * promotion capacity it is holding.
     *
     * This is what a guest cart that already backs a live order gets instead of a merge. A merge
     * would move its lines to a cart with a *different id*, and the order module dedupes placements
     * per cart id — so the customer's next checkout would place a second order for the items the
     * pending one already contains, while that one stays payable. The reservation is left alone for
     * the same reason: the pending order's redemption is what consumes it, and its cancellation or
     * its terminal payment is what gives it back.
     *
     * What the customer sees is honest: the cart they filled as a visitor is gone from their cart,
     * because it is not a cart any more — it is an order waiting to be paid, and it is in their
     * order history from this login on.
     */
    private fun retireInTransaction(cartId: Long) {
        Carts.update({ Carts.id eq cartId }) { statement ->
            statement[status] = CART_STATUS_MERGED
            statement[updatedAt] = CurrentTimestampWithTimeZone
        }
    }

    /**
     * Moves every line of cart [from] into cart [into] and retires the emptied cart.
     *
     * Two lines are the same line when they carry the same variant, the same print image, and the
     * same prompt — the rule the login merge was decided with, narrowed by issue #83's review: the
     * prompt belongs to the key because it is what the customer is charged extra for, so dropping
     * the guest line would change what they pay and lose the prompt from every order made of that
     * cart. It is still deliberately coarser than the one an add uses, which compares the whole
     * snapshot: the visitor and the customer were quoted their prices at different moments, and
     * showing the same mug twice for that reason would be a worse answer than one merged line. A
     * merged quantity stops at what one line may hold, exactly like an add.
     */
    private fun mergeInTransaction(
        from: Long,
        into: Long,
    ) {
        adoptPromotionInTransaction(from, into)

        // The lines of the receiving cart, keyed by what makes two lines mergeable. Two of them can
        // share that key — they were quoted different prices, say — and then the first one wins, so
        // the merge is decided by position and not by whatever order the database returns rows in.
        val targets = mutableMapOf<MergeKey, Pair<Long, Int>>()
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
        retireInTransaction(from)
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

    /** What makes two lines the same line for the login merge. */
    private fun ResultRow.mergeKey(): MergeKey =
        MergeKey(
            variantId = this[CartItems.variantId],
            printImageId = this[CartItems.printImageId],
            promptId = this[CartItems.promptId],
        )

    private fun ResultRow.idAndQuantity(): Pair<Long, Int> =
        this[CartItems.id].value to this[CartItems.quantity]

    /**
     * The three references two lines have to share to become one line here: what was bought, what
     * is printed on it, and which prompt produced that print. The price snapshots are deliberately
     * not part of it — see [mergeInTransaction].
     */
    private data class MergeKey(
        val variantId: Long,
        val printImageId: Long?,
        val promptId: Long?,
    )
}
