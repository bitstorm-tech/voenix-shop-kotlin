package shop.voenix.cart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
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
    /** Registers an uploaded file as a print image of [owner] and returns the id it is used by. */
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
