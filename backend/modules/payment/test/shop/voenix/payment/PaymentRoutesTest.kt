package shop.voenix.payment

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.installAuthModule
import shop.voenix.auth.installGuestCapableRouteProtection
import shop.voenix.http.installHttpRuntime

/**
 * The one translation this module's HTTP surface performs: a confirmation outcome into a status.
 *
 * The operation is a stub, so what the route decides *before* any payment work runs — the secret
 * above all — is a statement this test can actually make: every rejection below is asserted
 * together with the fact that the stub was never called at all.
 */
internal class PaymentRoutesTest {
    @Test
    fun `a wrong secret is refused before anything is read`() = testApplication {
        val payments = StubPayments(PaymentConfirmation.CONFIRMED)
        application { installPaymentTestApplication(payments) }

        val response = client.webhook(secret = "not-the-secret", id = "tr_first")

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(payments.confirmed.isEmpty(), "nothing is processed for a wrong secret")
    }

    /**
     * The secret is not a password field a near miss is forgiven on. A prefix of it, its uppercase
     * form, and an empty one are all just wrong.
     */
    @Test
    fun `no near miss of the secret is accepted`() = testApplication {
        val payments = StubPayments(PaymentConfirmation.CONFIRMED)
        application { installPaymentTestApplication(payments) }

        listOf(WEBHOOK_SECRET.dropLast(1), WEBHOOK_SECRET.uppercase(), "%20").forEach { secret ->
            assertEquals(
                HttpStatusCode.Forbidden,
                client.webhook(secret = secret, id = "tr_first").status,
                "secret '$secret' must not open the webhook",
            )
        }
        assertTrue(payments.confirmed.isEmpty())
    }

    /**
     * Deviation D23: a delivery that carries no secret segment at all does not reach a `403` — it
     * reaches nothing. The route is only mounted under the secret, so Ktor answers its plain `404`,
     * and the difference is worth pinning: the two answers together are what makes the secret a
     * credential rather than a parameter the route validates.
     */
    @Test
    fun `a delivery without a secret segment does not match the route`() = testApplication {
        val payments = StubPayments(PaymentConfirmation.CONFIRMED)
        application { installPaymentTestApplication(payments) }

        listOf("/api/payments/webhook", "/api/payments/webhook/").forEach { path ->
            val response =
                client.post(path) {
                    setBody(FormDataContent(Parameters.build { append("id", "tr_first") }))
                }
            assertEquals(HttpStatusCode.NotFound, response.status, "path '$path'")
        }
        assertTrue(payments.confirmed.isEmpty(), "nothing is processed without the secret")
    }

    @Test
    fun `a delivery without a payment id is a bad request`() = testApplication {
        val payments = StubPayments(PaymentConfirmation.CONFIRMED)
        application { installPaymentTestApplication(payments) }

        listOf(null, "", "   ").forEach { id ->
            assertEquals(
                HttpStatusCode.BadRequest,
                client.webhook(secret = WEBHOOK_SECRET, id = id).status,
                "id '$id' names no payment",
            )
        }
        assertTrue(payments.confirmed.isEmpty())
    }

    /**
     * The body is untrusted input from the internet. Everything in it except `id` is ignored, so a
     * forged `status=PAID` reaches nothing: the operation is handed the id and nothing else.
     */
    @Test
    fun `only the payment id is read from the body`() = testApplication {
        val payments = StubPayments(PaymentConfirmation.RECORDED)
        application { installPaymentTestApplication(payments) }

        val response =
            client.post(webhookPath(WEBHOOK_SECRET)) {
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("id", " tr_forged ")
                            append("status", "paid")
                            append("amount", "999999")
                        }
                    )
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("tr_forged"), payments.confirmed)
    }

    /** The outcome → status table of the webhook, asserted outcome by outcome. */
    @Test
    fun `every confirmation outcome answers the status Mollie should act on`() = testApplication {
        val outcomes = Collections.synchronizedList(mutableListOf<PaymentConfirmation>())
        val payments = PaymentOperations { outcomes.removeAt(0) }
        application { installPaymentTestApplication(payments) }

        val expected =
            mapOf(
                PaymentConfirmation.RECORDED to HttpStatusCode.OK,
                PaymentConfirmation.CONFIRMED to HttpStatusCode.OK,
                PaymentConfirmation.NOT_CONFIRMED to HttpStatusCode.OK,
                PaymentConfirmation.SUPERSEDED to HttpStatusCode.OK,
                PaymentConfirmation.UNKNOWN_PAYMENT to HttpStatusCode.OK,
                PaymentConfirmation.PROVIDER_UNAVAILABLE to HttpStatusCode.BadGateway,
                PaymentConfirmation.DATABASE_FAILURE to HttpStatusCode.InternalServerError,
            )
        assertEquals(
            PaymentConfirmation.entries.toSet(),
            expected.keys,
            "every outcome has a decided status",
        )

        expected.forEach { (outcome, status) ->
            outcomes += outcome
            val response = client.webhook(secret = WEBHOOK_SECRET, id = "tr_first")
            assertEquals(status, response.status, "$outcome")
            assertEquals("", response.bodyAsText(), "a webhook answer carries no body")
        }
    }

    /**
     * Mollie has no session and sends no CSRF token, which is why this route installs none of the
     * auth subtrees — and why the secret exists. The protected route next to it proves the
     * application's ordinary protection is still installed and still rejecting.
     */
    @Test
    fun `the webhook needs no token while a protected route still refuses one without`() =
        testApplication {
            val payments = StubPayments(PaymentConfirmation.CONFIRMED)
            application {
                installPaymentTestApplication(payments)
                routing {
                    route("/api/protected") {
                        installGuestCapableRouteProtection()
                        post { call.respond(HttpStatusCode.OK) }
                    }
                }
            }

            assertEquals(
                HttpStatusCode.OK,
                client.webhook(secret = WEBHOOK_SECRET, id = "tr_first").status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                client.post("/api/protected").status,
                "the ordinary protection is installed and still rejects a missing CSRF token",
            )
        }

    private suspend fun HttpClient.webhook(
        secret: String,
        id: String?,
    ): HttpResponse =
        post(webhookPath(secret)) {
            setBody(FormDataContent(Parameters.build { id?.let { value -> append("id", value) } }))
        }

    private fun webhookPath(secret: String): String = "/api/payments/webhook/$secret"

    /** The operations seam, recording what the route decided to hand it. */
    private class StubPayments(private val outcome: PaymentConfirmation) : PaymentOperations {
        val confirmed: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun confirm(molliePaymentId: String): PaymentConfirmation {
            confirmed += molliePaymentId
            return outcome
        }
    }

    private companion object {
        const val WEBHOOK_SECRET = "payment-route-test-webhook-secret"
    }
}

/** The shared runtime a route test needs: content negotiation, sessions and CSRF, and the route. */
private fun Application.installPaymentTestApplication(payments: PaymentOperations) {
    installHttpRuntime()
    installAuthModule(AuthSettings("payment-route-test-session-secret"))
    installPaymentModule(payments, "payment-route-test-webhook-secret")
}
