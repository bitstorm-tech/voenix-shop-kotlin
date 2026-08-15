package shop.voenix.payment

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.security.MessageDigest
import shop.voenix.http.ApiError

/**
 * The whole HTTP surface of the payment module: one route, called by Mollie and by nobody else.
 *
 * The two legacy endpoints are gone (deviation D1). `POST /api/payments` let any signed-in caller
 * name their own amount, and `GET /api/payments/{id}` answered any payment by id to any signed-in
 * caller; neither had a consumer, and creating a payment is now a module capability that Checkout
 * calls, not something a client asks for.
 *
 * What is left cannot use the application's ordinary protection, because Mollie has no session and
 * sends no CSRF token: the route deliberately installs **none** of the auth subtrees. The secret
 * path segment is what takes their place (Joe's condition, D3). It is compared before anything else
 * happens — before the body is read, before Mollie is called, before the database is touched — so a
 * wrong secret costs a string comparison and nothing more. The comparison is constant-time, because
 * a timing side channel is exactly how a secret in a URL would be guessed.
 *
 * The body is never trusted. Only `id` is read from it; the status comes from Mollie's API, so a
 * forged `status=PAID` changes nothing. And the answer is always empty: a webhook response is a
 * message to Mollie about redelivery, and anything else in it would only tell a caller who probes
 * the route what this backend knows.
 */
internal fun Application.installPaymentRoutes(
    payments: PaymentOperations,
    webhookSecret: String,
) {
    routing {
        post(WEBHOOK_PATH) {
            if (!secretMatches(call.parameters["secret"], webhookSecret)) {
                call.respond(HttpStatusCode.Forbidden, ApiError("Forbidden"))
                return@post
            }
            val molliePaymentId = call.receiveParameters()["id"]?.trim()
            if (molliePaymentId.isNullOrEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ApiError("Missing payment id"))
                return@post
            }
            call.respond(payments.confirm(molliePaymentId).status)
        }
    }
}

/**
 * The secret in the path against the configured one, without leaking how far the two agreed.
 *
 * [MessageDigest.isEqual] is the JDK's constant-time array comparison; the lengths are compared
 * inside it, which is acceptable because the length of the configured secret is not the secret.
 */
private fun secretMatches(
    candidate: String?,
    expected: String,
): Boolean =
    candidate != null &&
        MessageDigest.isEqual(
            candidate.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )

private const val WEBHOOK_PATH = "/api/payments/webhook/{secret}"

/**
 * The outcome → status table of the webhook, and the only place the two are connected.
 *
 * Five outcomes answer `200` because a redelivery would change nothing about them; the two that
 * answer an error status are the two a redelivery can genuinely fix. The reasoning per outcome
 * lives on [PaymentConfirmation] itself.
 */
private val PaymentConfirmation.status: HttpStatusCode
    get() =
        when (this) {
            PaymentConfirmation.RECORDED,
            PaymentConfirmation.CONFIRMED,
            PaymentConfirmation.NOT_CONFIRMED,
            PaymentConfirmation.SUPERSEDED,
            PaymentConfirmation.UNKNOWN_PAYMENT -> HttpStatusCode.OK
            PaymentConfirmation.PROVIDER_UNAVAILABLE -> HttpStatusCode.BadGateway
            PaymentConfirmation.DATABASE_FAILURE -> HttpStatusCode.InternalServerError
        }
