package shop.voenix

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import shop.voenix.payment.MollieSettings

/**
 * Mollie, as far as a composed checkout needs it: the three calls `MolliePaymentClient` makes, and
 * the four ways a test wants them to behave.
 *
 * Every checkout journey of this backend ends at the provider, so a cross-module test of the
 * checkout is only as honest as its stub. This one answers real payment ids, remembers the amount
 * it was asked for — the webhook compares it against the stored one — and records every call, which
 * is what the "no provider call was made" assertions are built on.
 *
 * The four knobs are each one row of the record's test matrix:
 * - [refuseCreation] is the provider that will not create a payment (the payment module's deviation
 *   D10): that module cancels the order and the checkout answers `502`;
 * - [fixedPaymentId] makes a creation answer a payment id that is already stored, so the insert
 *   conflicts on `ux_payments_mollie_payment_id` while the order's live slot stays free. It is the
 *   deterministic way to reach the payment module's other `null` (deviation D21): no payment is
 *   started and the order deliberately stays `PENDING`. It reproduces that *answer*, not the
 *   doubly-vacated race that also produces it — and the duplicated id stays open at the provider,
 *   because it belongs to another order;
 * - [gate] holds a creation until a test opens it, which is how two checkouts of one cart are made
 *   to overlap without any sleeping;
 * - [answer] is what a later read — a webhook delivery — is told the payment did.
 */
internal class CheckoutMollieStub : AutoCloseable {
    /** Every payment id this stub created, in creation order. */
    val created: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Every payment id a read asked about. */
    val read: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Every payment id a cancellation closed. */
    val cancelled: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Answer every creation with `422`, like a provider that refuses the payment outright. */
    @Volatile var refuseCreation: Boolean = false

    /** Answer every creation with this very id instead of a fresh one. */
    @Volatile var fixedPaymentId: String? = null

    /**
     * Awaited by the *first* creation, for as long as a test keeps it closed. Only the first one
     * waits: the point of the gate is to hold one checkout inside the provider call while another
     * runs past it.
     */
    @Volatile var gate: CountDownLatch? = null

    /** Counted down when that first creation starts waiting, so a test knows it may go on. */
    @Volatile var gateReached: CountDownLatch? = null

    private val statuses: MutableMap<String, String> = ConcurrentHashMap()

    private val amounts: MutableMap<String, String> = ConcurrentHashMap()

    private val nextId = AtomicInteger()

    private val attempts = AtomicInteger()

    /**
     * What makes this stub's payment ids unique across the whole suite. The tests of one class
     * share a schema, and `ux_payments_mollie_payment_id` is global: a counter that restarts with
     * every stub would let one test's second payment collide with the previous test's — which is a
     * real conflict path (see [fixedPaymentId]) and would silently turn an unrelated journey into
     * it.
     */
    private val run: String = UUID.randomUUID().toString().take(RUN_ID_LENGTH)

    private val server =
        embeddedServer(Netty, port = 0) {
                routing {
                    post("/payments") {
                        val amount = call.requestedAmount()
                        if (attempts.incrementAndGet() == 1) {
                            gateReached?.countDown()
                            gate?.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        }
                        call.create(amount)
                    }
                    get("/payments/{id}") {
                        val id = checkNotNull(call.parameters["id"])
                        read += id
                        call.respondPayment(id)
                    }
                    delete("/payments/{id}") {
                        val id = checkNotNull(call.parameters["id"])
                        cancelled += id
                        statuses[id] = "canceled"
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
            .start(wait = false)

    /** The URL the payment module is pointed at, in the settings a deployment would carry. */
    fun settings(webhookSecret: String): MollieSettings =
        MollieSettings(
            apiKey = "test_checkout_mollie_key",
            redirectUrl = "http://localhost:5173/checkout/success",
            webhookUrl = "https://voenix.test/api/payments/webhook/$webhookSecret",
            webhookSecret = webhookSecret,
            apiUrl = "http://localhost:${resolvedPort()}/payments",
        )

    /** What the next read of [molliePaymentId] reports — the status a webhook delivery carries. */
    fun answer(
        molliePaymentId: String,
        status: String,
    ) {
        statuses[molliePaymentId] = status
    }

    /** The checkout URL this stub hands out for [molliePaymentId]. */
    fun checkoutUrl(molliePaymentId: String): String =
        "https://checkout.mollie.test/pay/$molliePaymentId"

    override fun close() {
        server.stop()
    }

    private suspend fun ApplicationCall.create(amount: String) {
        if (refuseCreation) {
            respondText(
                """{"status":422,"title":"Unprocessable Entity"}""",
                ContentType.Application.Json,
                HttpStatusCode.UnprocessableEntity,
            )
            return
        }
        val id = fixedPaymentId ?: "tr_${run}_${nextId.incrementAndGet()}"
        created += id
        amounts[id] = amount
        statuses.putIfAbsent(id, "open")
        respondPayment(id)
    }

    private suspend fun ApplicationCall.respondPayment(id: String) {
        respondText(
            """{"id":"$id","status":"${statuses[id] ?: "open"}",""" +
                """"amount":{"currency":"EUR","value":"${amounts[id] ?: DEFAULT_AMOUNT}"},""" +
                """"_links":{"checkout":{"href":"${checkoutUrl(id)}"}}}""",
            ContentType.Application.Json,
        )
    }

    /** The amount of a creation, read out of the body the payment module serialized itself. */
    private suspend fun ApplicationCall.requestedAmount(): String =
        AMOUNT_PATTERN.find(receiveText())?.groupValues?.get(1) ?: DEFAULT_AMOUNT

    private fun resolvedPort(): Int = runBlocking {
        server.engine.resolvedConnectors().first().port
    }

    private companion object {
        const val GATE_TIMEOUT_SECONDS = 30L
        const val DEFAULT_AMOUNT = "0.00"
        const val RUN_ID_LENGTH = 8

        val AMOUNT_PATTERN = Regex(""""value"\s*:\s*"([^"]+)"""")
    }
}
