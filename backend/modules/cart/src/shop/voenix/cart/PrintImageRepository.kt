package shop.voenix.cart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * The registry of uploaded print images: the two operations that stand on their own, outside any
 * cart transaction.
 *
 * An upload is registered before any cart line points at it, and the image module's guest delivery
 * route resolves a file name without a cart being involved at all — which is why this is a
 * repository of its own rather than a third table on [CartRepository].
 *
 * What the *cart's* own transactions need from `print_images` stays with them: the ownership check
 * of an add and the guest claim have to commit together with the cart write, so they use the table
 * inside that transaction through [ownsPrintImageInTransaction] and the claim's own update.
 */
internal class PrintImageRepository(private val database: Database) {
    /**
     * Registers an uploaded file as a print image of [owner] and returns the id it is used by.
     *
     * An upload made while signed in stores **both** halves of [owner]: `ck_print_images_owner`
     * requires at least one owner and `fk_print_images_user` is `ON DELETE SET NULL`, so a row
     * without a token would become unreachable the moment the account is deleted. Which of the two
     * then *identifies* the image is decided by `ownershipPredicate`, not by the insert: a row that
     * carries a user id is claimed, and its token stops being a handle on it.
     */
    suspend fun insert(
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
    suspend fun find(
        imageId: Long,
        guestToken: String?,
        userId: Long?,
    ): String? =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                PrintImages.select(PrintImages.filename)
                    .where {
                        (PrintImages.id eq imageId) and ownershipPredicate(guestToken, userId)
                    }
                    .singleOrNull()
                    ?.get(PrintImages.filename)
            }
        }
}

/** "This image belongs to [owner]", asked inside the transaction that is about to use it. */
internal fun ownsPrintImageInTransaction(
    imageId: Long,
    owner: CartOwner,
): Boolean =
    PrintImages.select(PrintImages.id)
        .where {
            (PrintImages.id eq imageId) and ownershipPredicate(owner.guestToken, owner.userId)
        }
        .singleOrNull() != null

/**
 * "This image belongs to the caller": once an image has been claimed it belongs to its **user**,
 * and the guest token identifies it only while it is still unclaimed. So a token matches a row
 * whose `user_id` is `NULL` and nothing else, exactly like the claim's own `WHERE`.
 *
 * The `NULL` check is what makes the login's token rotation worth something. An upload made while
 * signed in is written with both owners (the CHECK constraint forbids a user-only row), so a token
 * comparison without it would keep serving the customer's images to whoever browses the same
 * machine next — after a logout, which deliberately keeps the cookie, or after a registration,
 * which never rotates it at all.
 *
 * A request carrying neither identity matches nothing.
 */
private fun ownershipPredicate(
    guestToken: String?,
    userId: Long?,
): Op<Boolean> =
    when {
        guestToken != null && userId != null ->
            (PrintImages.userId eq userId) or
                ((PrintImages.guestSessionToken eq guestToken) and PrintImages.userId.isNull())
        guestToken != null ->
            (PrintImages.guestSessionToken eq guestToken) and PrintImages.userId.isNull()
        userId != null -> PrintImages.userId eq userId
        else -> Op.FALSE
    }
