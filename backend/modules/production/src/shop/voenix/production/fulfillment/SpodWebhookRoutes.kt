package shop.voenix.production.fulfillment

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.http.ApiError

/**
 * The one inbound route of the print-on-demand channel: the partner reporting what happened to an
 * order it produces for this shop.
 *
 * It follows the payment module's Mollie webhook to the letter, because the situation is the same
 * (ADR 0002, decision 5): a caller with no session and no CSRF token, so the route deliberately
 * installs **none** of the auth subtrees and the secret path segment takes their place. The secret
 * is compared before anything else happens — before the body is read, before the database is
 * touched — so a wrong one costs a constant-time string comparison and nothing more. It never
 * reaches a log line, in this file or anywhere else.
 *
 * What differs from Mollie is the answer. The partner demands `202` with the body `[accepted]`
 * within eight seconds, and it redelivers anything else — so **every** processed and every no-op
 * outcome answers exactly that: an unknown reference, a job that shipped hours ago, an event type
 * this shop does not act on, a body that is not the JSON it should be. Redelivering any of them
 * would change nothing, and an endless retry loop on the partner's side is not a way to report that
 * a job is unknown. Only a database failure — the one thing a redelivery genuinely fixes — is
 * allowed to become a `500`, and it does so by throwing.
 *
 * The handler is synchronous: two lookups and one guarded transaction, no call back to the partner
 * and no work handed to a background scan. That is what keeps it inside the eight seconds, and it
 * is affordable precisely because there is so little of it.
 *
 * Nothing of the body is trusted or kept beyond the four bounded fields below. The carrier name is
 * mapped onto this shop's own enum, the tracking code is stored as text, and the partner's tracking
 * **URL is not read at all** — the shop builds its tracking links from its own bounded carrier list
 * (decision J2 of issue #119), so a link the partner chooses may never end up in a mail sent under
 * the shop's name.
 */
internal fun Application.installSpodWebhookRoute(
    webhook: SpodWebhookOperations,
    secret: String,
) {
    routing {
        post(WEBHOOK_PATH) {
            if (!secretMatches(call.parameters["secret"], secret)) {
                call.respond(HttpStatusCode.Forbidden, ApiError("Forbidden"))
                return@post
            }
            parseEvent(call.receiveText())?.let { event -> webhook.handle(event) }
            call.respondText(ACCEPTED_BODY, ContentType.Application.Json, HttpStatusCode.Accepted)
        }
    }
}

/**
 * What the webhook may ask the fulfillment surface to do, and the seam the route tests stub.
 *
 * It is a second, much smaller interface next to [FulfillmentOperations] on purpose: the callback
 * of a partner and the screens of a supplier have nothing in common but the ship transaction
 * underneath, and one interface serving both would offer the webhook everything an admin may do.
 */
internal fun interface SpodWebhookOperations {
    /**
     * Applies one reported event. It answers nothing: every outcome the caller could distinguish
     * ends as the same `202`, so an answer would only invite a route to act on it.
     */
    suspend fun handle(event: SpodWebhookEvent)
}

/**
 * The events this shop acts on, with the untrusted body already reduced to bounded values.
 *
 * [reference] is how the job is found: the partner's own order id where the payload carries one,
 * and this shop's `ORD-{orderId}-JOB-{jobId}` reference as the fallback — which is exactly why that
 * string is deterministic (ADR 0002, decision 4).
 */
internal sealed interface SpodWebhookEvent {
    val reference: SpodOrderReference

    /**
     * One package left the partner. [carrier] is the mapped enum the customer's mail is built from
     * and [reportedCarrier] the partner's own spelling, kept for an operator to read.
     */
    data class ShipmentSent(
        override val reference: SpodOrderReference,
        val carrier: ShippingCarrier,
        val reportedCarrier: String?,
        val trackingNumber: String?,
    ) : SpodWebhookEvent

    /** The partner cancelled the order or flagged it as needing action. */
    data class RemoteStateReported(
        override val reference: SpodOrderReference,
        val state: SpodReportedState,
    ) : SpodWebhookEvent
}

/** The two states the partner reports that this shop cannot resolve by itself. */
internal enum class SpodReportedState {
    CANCELLED,
    NEEDS_ACTION,
}

/**
 * How one reported event names the job it is about: the partner's order id, this shop's own
 * reference, or both. Either may be missing, and one that names nothing this shop knows is a no-op
 * — a partner sends events for orders of every one of its customers.
 */
internal data class SpodOrderReference(
    val externalReference: String?,
    val shopReference: String?,
)

/**
 * The payload reduced to what this shop acts on, or `null` when it is nothing this shop acts on.
 *
 * The parse is deliberately forgiving in one direction and strict in the other: unknown fields are
 * ignored, because a partner adds fields to its own payloads, while an unknown event type, a
 * missing reference, and a body that is not JSON at all end the same way — as the no-op that
 * answers `202`.
 *
 * The failure is logged **without the exception message**: a decoding message quotes the input it
 * failed on, and the input here is an untrusted body (repository rule, `backend/CLAUDE.md`).
 */
private fun parseEvent(body: String): SpodWebhookEvent? {
    val payload =
        try {
            webhookJson.decodeFromString<SpodWebhookPayload>(body)
        } catch (failure: SerializationException) {
            logger.warn(
                "A SPOD webhook body could not be read ({})",
                failure.javaClass.simpleName,
            )
            return null
        }
    return when (payload.eventType) {
        SHIPMENT_SENT_EVENT -> payload.shipmentSent()
        ORDER_CANCELLED_EVENT -> payload.remoteState(SpodReportedState.CANCELLED)
        ORDER_NEEDS_ACTION_EVENT -> payload.remoteState(SpodReportedState.NEEDS_ACTION)
        else -> null
    }
}

private fun SpodWebhookPayload.shipmentSent(): SpodWebhookEvent? {
    val reference = reference(shipment) ?: return null
    val reported = shipment?.carrier?.trim()?.takeIf(String::isNotEmpty)
    return SpodWebhookEvent.ShipmentSent(
        reference = reference,
        carrier = ShippingCarrier.ofReportedName(reported),
        reportedCarrier = reported?.take(MAXIMUM_REPORTED_CARRIER_LENGTH),
        trackingNumber =
            shipment
                ?.trackingCode
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.takeIf { code -> code.length <= MAXIMUM_TRACKING_NUMBER_LENGTH }
                ?.takeIf { code -> code.none(Char::isISOControl) },
    )
}

private fun SpodWebhookPayload.remoteState(state: SpodReportedState): SpodWebhookEvent? =
    reference(order)?.let { reference -> SpodWebhookEvent.RemoteStateReported(reference, state) }

/**
 * The two ways one payload names an order, taken from the event's own object first and from the
 * envelope second. A payload that names neither is nothing this shop can act on.
 */
private fun SpodWebhookPayload.reference(part: SpodWebhookOrderPart?): SpodOrderReference? {
    val externalReference = part?.orderId?.bounded()
    val shopReference =
        part?.externalOrderReference?.bounded() ?: this.externalOrderReference?.bounded()
    return if (externalReference == null && shopReference == null) {
        null
    } else {
        SpodOrderReference(externalReference, shopReference)
    }
}

/** Trimmed, non-empty, and short enough for the columns it is compared against — or `null`. */
private fun String.bounded(): String? =
    trim().takeIf { value -> value.isNotEmpty() && value.length <= MAXIMUM_REFERENCE_LENGTH }

/**
 * The payload, declared as the few fields this shop reads and nothing else.
 *
 * `trackingUrl` is deliberately **absent** from this type. A field that does not exist cannot be
 * stored by accident, and that is the point: the partner sends one, and the shop discards it.
 */
@Serializable
private data class SpodWebhookPayload(
    val eventType: String? = null,
    val shipment: SpodWebhookOrderPart? = null,
    val order: SpodWebhookOrderPart? = null,
    val externalOrderReference: String? = null,
)

/** The order half of a payload, whichever object of it carries the ids. */
@Serializable
private data class SpodWebhookOrderPart(
    val orderId: String? = null,
    val externalOrderReference: String? = null,
    val carrier: String? = null,
    val trackingCode: String? = null,
)

/**
 * The secret in the path against the configured one, without leaking how far the two agreed.
 *
 * [MessageDigest.isEqual] is the JDK's constant-time array comparison; the lengths are compared
 * inside it, which is acceptable because the length of the configured secret is not the secret.
 */
private fun secretMatches(candidate: String?, expected: String): Boolean =
    candidate != null &&
        MessageDigest.isEqual(
            candidate.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )

/**
 * Its own JSON, not the application's: the partner's ids arrive as numbers in some fields and as
 * strings in others, and `isLenient` is what lets one nullable `String` read both without a custom
 * serializer per field.
 */
private val webhookJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

private val logger: Logger = LoggerFactory.getLogger("shop.voenix.production.fulfillment.webhook")

private const val WEBHOOK_PATH = "/api/production/webhooks/spod/{secret}"

/** The exact body the partner requires; anything else makes it redeliver. */
private const val ACCEPTED_BODY = "[accepted]"

private const val SHIPMENT_SENT_EVENT = "Shipment.sent"

private const val ORDER_CANCELLED_EVENT = "Order.cancelled"

private const val ORDER_NEEDS_ACTION_EVENT = "Order.needs-action"

/** The width of `production_spod_orders.external_reference`, the wider of the two compared. */
private const val MAXIMUM_REFERENCE_LENGTH = 128

/** The width of `production_jobs.shipping_carrier_reported`. */
private const val MAXIMUM_REPORTED_CARRIER_LENGTH = 128

/** The width of `production_jobs.tracking_number`. */
private const val MAXIMUM_TRACKING_NUMBER_LENGTH = 128
