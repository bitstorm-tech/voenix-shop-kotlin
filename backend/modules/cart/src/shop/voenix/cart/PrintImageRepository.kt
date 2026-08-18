package shop.voenix.cart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import shop.voenix.image.GuestImageResolver

/**
 * The registry of uploaded print images: the two operations that stand on their own, outside any
 * cart transaction.
 *
 * An upload is registered before any cart line points at it, and the image module's guest delivery
 * route resolves a file name without a cart being involved at all — which is why this is a
 * repository of its own rather than a third table on [CartRepository].
 *
 * What the *cart's* own transaction needs from `print_images` stays with it: the ownership check of
 * an add has to commit together with the cart write, so it uses the table inside that transaction
 * through [ownsPrintImageInTransaction].
 */
internal class PrintImageRepository(private val database: Database) {
    /**
     * Registers an uploaded file as a print image of [owner] and returns the id it is used by.
     *
     * An upload made while signed in stores **both** halves of [owner]: `ck_print_images_owner`
     * requires at least one owner and `fk_print_images_user` is `ON DELETE SET NULL`, so a row
     * without a token would become unreachable the moment the account is deleted. Which of the two
     * then *identifies* the image is decided by `ownershipPredicate`, not by the insert: a row that
     * carries a user id belongs to that user, and its token stops being a handle on it. Nothing
     * ever writes `user_id` onto an existing row — an image belongs from birth to whoever uploaded
     * it.
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

internal object PrintImages : LongIdTable("print_images") {
    val filename = varchar("filename", length = 64)
    val guestSessionToken = text("guest_session_token").nullable()
    val userId = long("user_id").nullable()
    val createdAt = timestampWithTimeZone("created_at")
}

/**
 * The cart's answer to the image module's only question about a print image: does this caller own
 * it, and under which file name is it stored?
 *
 * The class is public because the composition root hands it to `installGuestImageRoute`, but it
 * carries nothing else outward. It never tells anybody whether an image *exists* — a foreign image
 * and an unknown id both answer `null`, so the route turns both into `404` and an id cannot be
 * probed.
 */
public class CartGuestImages internal constructor(private val images: PrintImageRepository) :
    GuestImageResolver {
    override suspend fun resolve(
        imageId: Long,
        guestToken: String?,
        userId: Long?,
    ): String? = images.find(imageId, guestToken, userId)
}

/**
 * "This image belongs to [owner]", asked inside the transaction that is about to use it.
 *
 * It is `internal` only because Kotlin has no visibility between `private` and `internal`: the
 * single caller is [CartRepository.addItem], which needs the answer inside the cart's own
 * transaction, and the function lives here because [ownershipPredicate] — the rule that separates
 * two people sharing a browser — must stay private to this file.
 */
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
 * "This image belongs to the caller": an image uploaded while signed in belongs to its **user**,
 * and the guest token identifies an image only while that image has no user at all. So a token
 * matches a row whose `user_id` is `NULL` and nothing else.
 *
 * That `NULL` check is the only thing separating two people who share a browser, and issue #110
 * made it more important, not less: the guest cookie now survives every login, logout, and
 * registration untouched, so the token stays the same across a change of customer. `insert` writes
 * *both* identities for an upload made while signed in — `ck_print_images_owner` only demands at
 * least one owner, the row carrying both is the application's doing — so a token comparison without
 * the `NULL` check would serve the previous customer's images to whoever browses that machine next.
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
