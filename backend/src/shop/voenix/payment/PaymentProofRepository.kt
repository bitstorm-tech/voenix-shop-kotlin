package shop.voenix.payment

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.persistence.OrderStatus
import shop.voenix.persistence.Orders

class PaymentProofRepository(
    private val database: Database,
) : PaymentSideEffectQueue {
    fun attachMolliePayment(
        orderId: Int,
        molliePaymentId: String,
    ): Boolean =
        transaction(database) {
            Orders.update({ Orders.id eq orderId }) {
                it[Orders.molliePaymentId] = molliePaymentId
            } == 1
        }

    fun markOrderPaidFromMollie(
        payment: MolliePayment,
        nowEpochSeconds: Long,
    ): PaidOrderTransition? {
        if (!payment.status.isPaid) {
            return null
        }

        return transaction(database) {
            val order =
                Orders
                    .selectAll()
                    .where { Orders.molliePaymentId eq payment.id }
                    .singleOrNull()
                    ?: return@transaction null
            val orderId = order[Orders.id].value
            val changed = order[Orders.status] != OrderStatus.Paid.dbValue

            if (changed) {
                Orders.update({ Orders.id eq orderId }) {
                    it[status] = OrderStatus.Paid.dbValue
                }
            }

            ensureSideEffect(orderId, SideEffectType.Email, nowEpochSeconds)
            ensureSideEffect(orderId, SideEffectType.SftpUpload, nowEpochSeconds)

            PaidOrderTransition(orderId = orderId, changed = changed)
        }
    }

    fun listSideEffectsForOrder(orderId: Int): List<PaymentSideEffectJob> =
        transaction(database) {
            PaymentSideEffects
                .selectAll()
                .where { PaymentSideEffects.order eq EntityID(orderId, Orders) }
                .orderBy(PaymentSideEffects.id to SortOrder.ASC)
                .map(::toJob)
        }

    override fun claimDueSideEffects(
        nowEpochSeconds: Long,
        limit: Int,
    ): List<PaymentSideEffectJob> =
        transaction(database) {
            require(limit > 0) { "limit must be positive" }

            exec(
                """
                select id, order_id, type, status, attempts, next_attempt_epoch_seconds, idempotency_key, last_error
                from payment_side_effects
                where status in ('${SideEffectStatus.Pending.dbValue}', '${SideEffectStatus.Failed.dbValue}')
                  and next_attempt_epoch_seconds <= $nowEpochSeconds
                order by id
                limit $limit
                for update skip locked
                """.trimIndent(),
            ) { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(
                            PaymentSideEffectJob(
                                id = resultSet.getInt("id"),
                                orderId = resultSet.getInt("order_id"),
                                type = SideEffectType.fromDb(resultSet.getString("type")),
                                status = SideEffectStatus.fromDb(resultSet.getString("status")),
                                attempts = resultSet.getInt("attempts"),
                                nextAttemptEpochSeconds = resultSet.getLong("next_attempt_epoch_seconds"),
                                idempotencyKey = resultSet.getString("idempotency_key"),
                                lastError = resultSet.getString("last_error"),
                            ),
                        )
                    }
                }
            }.orEmpty().map { job ->
                val claimed =
                    job.copy(
                        status = SideEffectStatus.InProgress,
                        attempts = job.attempts + 1,
                        nextAttemptEpochSeconds = nowEpochSeconds,
                    )

                PaymentSideEffects.update({ PaymentSideEffects.id eq job.id }) {
                    it[status] = claimed.status.dbValue
                    it[attempts] = claimed.attempts
                    it[nextAttemptEpochSeconds] = claimed.nextAttemptEpochSeconds
                }

                claimed
            }
        }

    override fun reclaimInProgressSideEffects(
        olderThanEpochSeconds: Long,
        nextAttemptEpochSeconds: Long,
    ): Int =
        transaction(database) {
            PaymentSideEffects
                .selectAll()
                .orderBy(PaymentSideEffects.id to SortOrder.ASC)
                .map(::toJob)
                .filter { job ->
                    job.status == SideEffectStatus.InProgress &&
                        job.nextAttemptEpochSeconds <= olderThanEpochSeconds
                }
                .sumOf { job ->
                    PaymentSideEffects.update({ PaymentSideEffects.id eq job.id }) {
                        it[status] = SideEffectStatus.Failed.dbValue
                        it[PaymentSideEffects.nextAttemptEpochSeconds] = nextAttemptEpochSeconds
                        it[lastError] = "reclaimed abandoned in-progress job"
                    }
                }
        }

    override fun markSideEffectSucceeded(id: Int) {
        transaction(database) {
            PaymentSideEffects.update({ PaymentSideEffects.id eq id }) {
                it[status] = SideEffectStatus.Succeeded.dbValue
                it[lastError] = null
            }
        }
    }

    override fun markSideEffectFailedForRetry(
        id: Int,
        lastError: String,
        nextAttemptEpochSeconds: Long,
    ) {
        transaction(database) {
            PaymentSideEffects.update({ PaymentSideEffects.id eq id }) {
                it[status] = SideEffectStatus.Failed.dbValue
                it[PaymentSideEffects.lastError] = lastError
                it[PaymentSideEffects.nextAttemptEpochSeconds] = nextAttemptEpochSeconds
            }
        }
    }

    private fun ensureSideEffect(
        orderId: Int,
        type: SideEffectType,
        nowEpochSeconds: Long,
    ) {
        val idempotencyKey = type.idempotencyKey(orderId)
        PaymentSideEffects.insertIgnore {
            it[order] = EntityID(orderId, Orders)
            it[PaymentSideEffects.type] = type.dbValue
            it[status] = SideEffectStatus.Pending.dbValue
            it[attempts] = 0
            it[nextAttemptEpochSeconds] = nowEpochSeconds
            it[PaymentSideEffects.idempotencyKey] = idempotencyKey
            it[lastError] = null
        }
    }

    private fun toJob(row: ResultRow): PaymentSideEffectJob =
        PaymentSideEffectJob(
            id = row[PaymentSideEffects.id].value,
            orderId = row[PaymentSideEffects.order].value,
            type = SideEffectType.fromDb(row[PaymentSideEffects.type]),
            status = SideEffectStatus.fromDb(row[PaymentSideEffects.status]),
            attempts = row[PaymentSideEffects.attempts],
            nextAttemptEpochSeconds = row[PaymentSideEffects.nextAttemptEpochSeconds],
            idempotencyKey = row[PaymentSideEffects.idempotencyKey],
            lastError = row[PaymentSideEffects.lastError],
        )
}
