package shop.voenix.production.fulfillment

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import shop.voenix.http.installHttpRuntime

/**
 * The webhook route without a database: who is refused, in which order, and what the partner is
 * told about everything that is not a refusal.
 *
 * The order is the point of the first test. The secret is compared before the body is read, so a
 * caller guessing at the URL cannot even make this backend allocate their payload — the receive
 * pipeline of the test application counts every body that *is* read, and on a wrong secret the
 * count stays zero.
 */
internal class SpodWebhookRouteTest {
    @Test
    fun `a wrong secret is refused before the body is read and before any operation runs`() =
        testApplication {
            val webhook = RecordingWebhook()
            val bodies = BodyCounter()
            application {
                bodies.install(this)
                installHttpRuntime()
                installSpodWebhookRoute(webhook, SECRET)
            }

            listOf(
                    "/api/production/webhooks/spod/wrong-secret",
                    "/api/production/webhooks/spod/${SECRET.dropLast(1)}",
                    "/api/production/webhooks/spod/${SECRET}x",
                )
                .forEach { path ->
                    val response =
                        client.post(path) {
                            contentType(ContentType.Application.Json)
                            setBody(SHIPMENT_BODY)
                        }

                    assertEquals(HttpStatusCode.Forbidden, response.status, path)
                }

            assertEquals(emptyList(), webhook.events, "no event may reach the operations")
            assertEquals(0, bodies.count, "no body may be read on a wrong secret")
        }

    @Test
    fun `a shipment is reduced to bounded values and answered with the exact ack`() =
        testApplication {
            val webhook = RecordingWebhook()
            application {
                installHttpRuntime()
                installSpodWebhookRoute(webhook, SECRET)
            }

            val response =
                client.post(webhookPath()) {
                    contentType(ContentType.Application.Json)
                    setBody(SHIPMENT_BODY)
                }

            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals("[accepted]", response.bodyAsText())
            assertEquals(
                listOf<SpodWebhookEvent>(
                    SpodWebhookEvent.ShipmentSent(
                        reference =
                            SpodOrderReference(
                                externalReference = "9911",
                                shopReference = "ORD-70-JOB-4",
                            ),
                        carrier = ShippingCarrier.DEUTSCHE_POST,
                        reportedCarrier = "Deutsche Post",
                        trackingNumber = "0034043416",
                    )
                ),
                webhook.events,
            )
        }

    /**
     * The partner sends a tracking URL with every shipment, and this shop discards it (decision J2
     * of issue #119). The pin is on the parse, because that is where it would otherwise get in: a
     * field that never exists in the payload type cannot be carried anywhere later.
     */
    @Test
    fun `the partner's tracking URL never leaves the parse`() = testApplication {
        val webhook = RecordingWebhook()
        application {
            installHttpRuntime()
            installSpodWebhookRoute(webhook, SECRET)
        }

        client.post(webhookPath()) {
            contentType(ContentType.Application.Json)
            setBody(SHIPMENT_BODY)
        }

        assertTrue(
            webhook.events.none { event -> event.toString().contains("evil.example") },
            "the reported event must carry nothing of the partner's link: ${webhook.events}",
        )
    }

    @Test
    fun `the two order events become the two reported states`() = testApplication {
        val webhook = RecordingWebhook()
        application {
            installHttpRuntime()
            installSpodWebhookRoute(webhook, SECRET)
        }

        listOf("Order.cancelled", "Order.needs-action").forEach { eventType ->
            val response =
                client.post(webhookPath()) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"eventType":"$eventType","order":{"orderId":"9911"}}""")
                }
            assertEquals(HttpStatusCode.Accepted, response.status)
        }

        assertEquals(
            listOf(SpodReportedState.CANCELLED, SpodReportedState.NEEDS_ACTION),
            webhook.events.filterIsInstance<SpodWebhookEvent.RemoteStateReported>().map { event ->
                event.state
            },
        )
    }

    /**
     * Every no-op answers exactly like a processed event, because a redelivery would change none of
     * them and the partner keeps redelivering anything that is not this answer.
     */
    @Test
    fun `an unknown event, a nameless payload, and a broken body are all accepted no-ops`() =
        testApplication {
            val webhook = RecordingWebhook()
            application {
                installHttpRuntime()
                installSpodWebhookRoute(webhook, SECRET)
            }

            listOf(
                    """{"eventType":"Order.something-else","order":{"orderId":"1"}}""",
                    """{"eventType":"Shipment.sent","shipment":{"carrier":"DHL"}}""",
                    """{"eventType":"Shipment.sent"}""",
                    "not json at all",
                    "",
                )
                .forEach { body ->
                    val response =
                        client.post(webhookPath()) {
                            contentType(ContentType.Application.Json)
                            setBody(body)
                        }

                    assertEquals(HttpStatusCode.Accepted, response.status, body)
                    assertEquals("[accepted]", response.bodyAsText(), body)
                }

            assertEquals(emptyList(), webhook.events, "none of them is an event to act on")
        }

    private fun webhookPath(): String = "/api/production/webhooks/spod/$SECRET"

    /** Every event the route decided to hand on, in order. */
    private class RecordingWebhook : SpodWebhookOperations {
        val events = mutableListOf<SpodWebhookEvent>()

        override suspend fun handle(event: SpodWebhookEvent) {
            events += event
        }
    }

    /**
     * Counts the request bodies this application reads. It hangs into the receive pipeline, which
     * is the one place every `receive` call passes through — so "the body was never read" is an
     * observation rather than a claim about the source order of two statements.
     */
    private class BodyCounter {
        var count: Int = 0
            private set

        fun install(application: Application) {
            application.receivePipeline.intercept(ApplicationReceivePipeline.Before) { count += 1 }
        }
    }

    private companion object {
        const val SECRET = "0123456789abcdef0123456789abcdef"

        /**
         * A payload shaped like the partner's: the order id as a number, our own reference beside
         * it, a carrier spelled the way a human would, and the tracking URL this shop discards.
         */
        val SHIPMENT_BODY =
            """
            {
              "eventType": "Shipment.sent",
              "shipment": {
                "orderId": 9911,
                "externalOrderReference": "ORD-70-JOB-4",
                "carrier": "Deutsche Post",
                "trackingCode": "0034043416",
                "trackingUrl": "https://evil.example/track/0034043416"
              }
            }
            """
                .trimIndent()
    }
}
