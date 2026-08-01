package shop.voenix.payment

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.db.executePostgresWrite

/**
 * The only code in this backend that touches `payments`.
 *
 * Two of its four operations can be refused by `ux_payments_live_order`, and both report that
 * refusal as a value instead of an exception, because both are a *race* rather than a bug:
 *
 * 1. [insert] loses a race against a concurrent `start` for the same order — the double-clicked
 *    checkout. The conflict cannot be finished inside the failed transaction, so the caller reads
 *    the winner afterwards, which is why [Insertion] exists and no `OrderWriteResult`-shaped result
 *    does.
 * 2. [updateStatus] moves a payment *back into* the live set — a `FAILED` payment that Mollie later
 *    reports as `PAID` while a retry payment already occupies the order's live slot. The index
 *    refuses it, the stored status stays what it was, and the caller has to say loudly that
 *    somebody may have been charged twice.
 *
 * No preliminary "does this order already have a live payment" query decides anything: the fast
 * path in [livePayment] is an optimization for the repeated checkout, and the index is the
 * authority.
 */
internal class PaymentRepository(private val database: Database) {
    /**
     * The order's live payment — the one occupying `ux_payments_live_order` — or `null` when the
     * order has no payment at all or every payment it had ended terminally.
     */
    suspend fun livePayment(orderId: Long): StoredPayment? = read {
        Payments.selectAll()
            .where { (Payments.orderId eq orderId) and livePredicate() }
            .singleOrNull()
            ?.toStoredPayment()
    }

    /** The payment Mollie is talking about, or `null` when this backend never created it. */
    suspend fun paymentByMollieId(molliePaymentId: String): StoredPayment? = read {
        Payments.selectAll()
            .where { Payments.molliePaymentId eq molliePaymentId }
            .singleOrNull()
            ?.toStoredPayment()
    }

    /**
     * Stores a freshly created provider payment, or reports that the order already has a live one.
     *
     * The conflict is answered as [Insertion.Conflict] rather than completed here: the transaction
     * that hit `23505` is dead, and reading the payment that won the race needs a fresh one.
     */
    suspend fun insert(
        orderId: Long,
        molliePaymentId: String,
        status: PaymentStatus,
        amountCents: Int,
        checkoutUrl: String,
    ): Insertion =
        executePostgresWrite(uniqueViolation = Insertion.Conflict) {
            write {
                Insertion.Stored(
                    Payments.insertAndGetId { statement ->
                            statement[Payments.orderId] = orderId
                            statement[Payments.molliePaymentId] = molliePaymentId
                            statement[Payments.status] = status.name
                            statement[Payments.amountCents] = amountCents
                            statement[Payments.checkoutUrl] = checkoutUrl
                            statement[createdAt] = CurrentTimestampWithTimeZone
                            statement[updatedAt] = CurrentTimestampWithTimeZone
                        }
                        .value
                )
            }
        }

    /**
     * Writes the status Mollie reported, bumping `updated_at` with it.
     *
     * Only called when the status actually changed, so `updated_at` stays the moment the payment
     * last *moved* rather than the moment a webhook was last delivered.
     */
    suspend fun updateStatus(
        paymentId: Long,
        status: PaymentStatus,
    ): StatusUpdate =
        executePostgresWrite(uniqueViolation = StatusUpdate.SUPERSEDED) {
            write {
                Payments.update({ Payments.id eq paymentId }) { statement ->
                    statement[Payments.status] = status.name
                    statement[updatedAt] = CurrentTimestampWithTimeZone
                }
                StatusUpdate.APPLIED
            }
        }

    private suspend fun <T> read(operation: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                operation()
            }
        }

    private suspend fun <T> write(operation: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                operation()
            }
        }

    /**
     * What the inserting transaction itself can end in; a conflict is completed by a second read.
     */
    sealed interface Insertion {
        data class Stored(val paymentId: Long) : Insertion

        data object Conflict : Insertion
    }

    /** Whether the reported status could be written, or another live payment stood in its way. */
    enum class StatusUpdate {
        APPLIED,
        SUPERSEDED,
    }
}

/**
 * The same predicate `ux_payments_live_order` is defined with, written the same way round: a
 * payment is live while its status is *not* one of the three terminal ones. Expressing it as "not
 * terminal" rather than "one of the other four" is what keeps this read and the index in agreement
 * about a status neither of them knows yet.
 */
private fun livePredicate() =
    not(Payments.status inList PaymentStatus.entries.filterNot { it.isLive }.map { it.name })

private fun ResultRow.toStoredPayment(): StoredPayment =
    StoredPayment(
        paymentId = this[Payments.id].value,
        orderId = this[Payments.orderId],
        molliePaymentId = this[Payments.molliePaymentId],
        status = PaymentStatus.valueOf(this[Payments.status]),
        amountCents = this[Payments.amountCents],
        checkoutUrl = this[Payments.checkoutUrl],
    )
