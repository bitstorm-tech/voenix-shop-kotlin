package shop.voenix.payment

import java.util.Collections
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import shop.voenix.order.OrderPaymentGateway
import shop.voenix.order.OrderPaymentOutcome
import shop.voenix.order.OrderPaymentStatus

/**
 * The fixtures every payment test shares: a seeded database whose orders the payments' foreign key
 * can point at, and stand-ins for the two capabilities this module consumes.
 *
 * The database is real, and it has to be. Every rule this module leans on is a constraint or an
 * index — the status CHECK, the amount CHECK, the unique Mollie id, and above all
 * `ux_payments_live_order`, which is the *only* thing preventing a double-clicked checkout from
 * charging a customer twice. None of that can be faked.
 *
 * The provider and the order gateway are faked, but where a fake stands in for a suspending call it
 * suspends the way the real one does: both [FakeMolliePayments] and [FakeOrders] cross a real
 * dispatch before they answer, because a cancelled job aborts exactly at those dispatches, and the
 * compensation tests exist to prove that the cleanup runs anyway. A fake that answered without
 * suspending would make those tests pass with `NonCancellable` removed from the production code.
 */
internal object PaymentTestSupport {
    const val USER_ID: Long = 7
    const val ORDER_ID: Long = 1
    const val OTHER_ORDER_ID: Long = 2
    const val AMOUNT_CENTS: Int = 4_070
    const val CHECKOUT_URL: String = "https://checkout.mollie.com/pay/tr_first"

    /**
     * Ids 1..[ORDER_COUNT] exist as orders, each on a cart of its own.
     *
     * Twenty is not a round number for its own sake: it is the history the batch status read has to
     * answer in a single query and without a single provider call, and a seed that is smaller than
     * the case under test cannot prove that.
     */
    const val ORDER_COUNT: Int = 20

    fun seed(dataSource: DataSource) {
        val carts =
            (1..ORDER_COUNT).joinToString(", ") { id -> "($id, 'guest-$id', 'CHECKED_OUT')" }
        val orders =
            (1..ORDER_COUNT).joinToString(", ") { id ->
                "($id, $id, 'guest-$id', 'PENDING', $ADDRESS_VALUES, 3580, 490, 0, 4070)"
            }
        execute(
            dataSource,
            "TRUNCATE voenix.payments, voenix.order_items, voenix.orders, " +
                "voenix.promotion_redemptions, voenix.production_requests, voenix.email_jobs, " +
                "voenix.cart_items, voenix.carts, voenix.users RESTART IDENTITY CASCADE",
            "INSERT INTO voenix.users (id, email, password_hash) " +
                "VALUES ($USER_ID, 'customer@example.com', 'hash')",
            "INSERT INTO voenix.carts (id, guest_session_token, status) VALUES $carts",
            "INSERT INTO voenix.orders (id, cart_id, guest_session_token, status, " +
                "$ADDRESS_COLUMNS, subtotal_cents, shipping_cost_cents, discount_cents, " +
                "total_cents) VALUES $orders",
        )
    }

    /** A payment row written behind the repository's back, to set a race or a history up. */
    fun insertPayment(
        dataSource: DataSource,
        orderId: Long,
        molliePaymentId: String,
        status: OrderPaymentStatus = OrderPaymentStatus.OPEN,
        amountCents: Int = AMOUNT_CENTS,
        checkoutUrl: String = "https://checkout.mollie.com/pay/$molliePaymentId",
    ) {
        execute(
            dataSource,
            "INSERT INTO voenix.payments " +
                "(order_id, mollie_payment_id, status, amount_cents, checkout_url) VALUES " +
                "($orderId, '$molliePaymentId', '${status.name}', $amountCents, '$checkoutUrl')",
        )
    }

    /** A payment request that is valid in every respect; each test varies what it is about. */
    fun paymentRequest(
        orderId: Long = ORDER_ID,
        amountCents: Int = AMOUNT_CENTS,
        email: String = "customer@example.com",
        phone: String? = "017623123456",
        billingCountry: String = "DE",
        shippingCountry: String = "DE",
    ): PaymentRequest =
        PaymentRequest(
            orderId = orderId,
            amountCents = amountCents,
            email = email,
            phone = phone,
            billingAddress =
                PaymentRequest.Address(
                    firstName = "Max",
                    lastName = "Mustermann",
                    street = "Musterstraße",
                    houseNumber = "1",
                    postalCode = "10115",
                    city = "Berlin",
                    country = billingCountry,
                ),
            shippingAddress =
                PaymentRequest.Address(
                    firstName = "Erika",
                    lastName = "Musterfrau",
                    street = "Lieferweg",
                    houseNumber = "5",
                    postalCode = "20095",
                    city = "Hamburg",
                    country = shippingCountry,
                ),
        )

    fun execute(
        dataSource: DataSource,
        vararg statements: String,
    ) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statements.forEach { sql -> statement.execute(sql) }
            }
        }
    }

    fun singleString(
        dataSource: DataSource,
        sql: String,
    ): String? = single(dataSource, sql) { rows -> rows.getString(1) }

    fun singleLong(
        dataSource: DataSource,
        sql: String,
    ): Long? = single(dataSource, sql) { rows -> rows.getLong(1).takeUnless { rows.wasNull() } }

    fun count(
        dataSource: DataSource,
        sql: String,
    ): Int = single(dataSource, sql) { rows -> rows.getInt(1) } ?: 0

    private fun <T> single(
        dataSource: DataSource,
        sql: String,
        read: (java.sql.ResultSet) -> T?,
    ): T? =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows -> if (rows.next()) read(rows) else null }
            }
        }

    /**
     * The provider, without a network.
     *
     * Every default answer dispatches before it answers, exactly where [MolliePaymentClient] would
     * go to the network, so a cancelled job breaks this fake in the same places it would break the
     * real adapter. That is the whole point of the compensation tests: a fake that answered without
     * suspending would make them pass while production leaked an open payment. A test that hands in
     * its own behaviour takes that responsibility over — most of them dispatch on purpose, to widen
     * the window they are racing in.
     */
    class FakeMolliePayments(
        private val onCreate: suspend (PaymentRequest, String) -> MolliePayment? = { request, _ ->
            dispatched { payment(id = "tr_first", request = request, checkoutUrl = CHECKOUT_URL) }
        },
        private val onFind: suspend (String) -> MolliePayment? = { dispatched { null } },
        private val onCancel: suspend (String) -> Boolean = { dispatched { true } },
    ) : MolliePayments {
        val created: MutableList<String> = synchronizedList()
        val idempotencyKeys: MutableList<String> = synchronizedList()
        val found: MutableList<String> = synchronizedList()
        val cancelled: MutableList<String> = synchronizedList()

        override suspend fun create(
            request: PaymentRequest,
            idempotencyKey: String,
        ): MolliePayment? {
            idempotencyKeys += idempotencyKey
            val payment = onCreate(request, idempotencyKey)
            payment?.let { created += it.id }
            return payment
        }

        override suspend fun find(molliePaymentId: String): MolliePayment? {
            found += molliePaymentId
            return onFind(molliePaymentId)
        }

        override suspend fun cancel(molliePaymentId: String): Boolean {
            cancelled += molliePaymentId
            return onCancel(molliePaymentId)
        }

        private fun <T> synchronizedList(): MutableList<T> =
            Collections.synchronizedList(mutableListOf())
    }

    /**
     * The order module's three writes, recorded rather than performed.
     *
     * They dispatch before they answer, for the same reason [FakeMolliePayments] does: the real
     * gateway is a database write behind `Dispatchers.IO`, and the D10 compensation test would pass
     * with `NonCancellable` removed if this fake answered on the caller's own cancelled job.
     *
     * `paymentEnded` has no caller in the payment module yet — the checkout migration's T4 binds
     * the terminal-status path to it — so it only records that it was reached.
     */
    class FakeOrders(
        private val onConfirm: (Long) -> OrderPaymentOutcome = { OrderPaymentOutcome.APPLIED },
        private val onCancel: (Long) -> OrderPaymentOutcome = { OrderPaymentOutcome.APPLIED },
    ) : OrderPaymentGateway {
        val confirmed: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val cancelled: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val ended: MutableList<Long> = Collections.synchronizedList(mutableListOf())

        override suspend fun confirm(orderId: Long): OrderPaymentOutcome {
            confirmed += orderId
            return dispatched { onConfirm(orderId) }
        }

        override suspend fun cancel(orderId: Long): OrderPaymentOutcome {
            cancelled += orderId
            return dispatched { onCancel(orderId) }
        }

        override suspend fun paymentEnded(orderId: Long) {
            ended += orderId
            dispatched {}
        }
    }

    /**
     * [answer], produced after a real dispatch — the step a cancelled job aborts at.
     *
     * Recording what a fake was asked stays *before* the dispatch on purpose: a test asserting that
     * a compensation reached the fake must see the call even when the answer never arrives.
     */
    private suspend fun <T> dispatched(answer: suspend () -> T): T =
        withContext(Dispatchers.IO) { answer() }

    fun payment(
        id: String,
        request: PaymentRequest,
        status: OrderPaymentStatus = OrderPaymentStatus.OPEN,
        checkoutUrl: String? = "https://checkout.mollie.com/pay/$id",
    ): MolliePayment =
        MolliePayment(
            id = id,
            status = status,
            amountCents = request.amountCents,
            checkoutUrl = checkoutUrl,
        )

    private const val ADDRESS_COLUMNS =
        "shipping_first_name, shipping_last_name, shipping_street, shipping_house_number, " +
            "shipping_postal_code, shipping_city, shipping_country, billing_first_name, " +
            "billing_last_name, billing_street, billing_house_number, billing_postal_code, " +
            "billing_city, billing_country, email"

    private const val ADDRESS_VALUES =
        "'Erika', 'Musterfrau', 'Lieferweg', '5', '20095', 'Hamburg', 'DE', " +
            "'Max', 'Mustermann', 'Musterstraße', '1', '10115', 'Berlin', 'DE', " +
            "'customer@example.com'"
}
