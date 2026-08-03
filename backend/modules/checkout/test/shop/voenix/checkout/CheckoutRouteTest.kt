package shop.voenix.checkout

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.promotion.PromotionCodeResult

/**
 * What the checkout routes decide before any checkout logic runs: CSRF on both routes, the field
 * rules on the body, and the one status and error code each refusal has.
 *
 * The operations are a stub that records its calls, so "rejected before the operation" is a
 * statement this test can actually make.
 */
internal class CheckoutRouteTest {
    @Test
    fun `both routes are rejected without a csrf token, before the operation runs`() =
        testApplication {
            val checkouts = StubCheckoutOperations()
            application { installCheckoutTestApplication(checkouts) }

            listOf(client.post("/api/checkout"), client.post("/api/checkout/orders/1/payment"))
                .forEach { response ->
                    assertEquals(HttpStatusCode.BadRequest, response.status)
                    assertEquals("Invalid CSRF token", response.message())
                }
            assertEquals(emptyList(), checkouts.calls)
        }

    @Test
    fun `the exact request the frontend sends today is accepted`() = testApplication {
        val checkouts = StubCheckoutOperations()
        application { installCheckoutTestApplication(checkouts) }
        val guest = createClient { install(HttpCookies) }
        val token = antiforgeryToken(guest)

        val response = guest.checkout(token, FRONTEND_BODY)

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("/api/orders/4711", response.headers[HttpHeaders.Location])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertNull(
            response.headers[HttpHeaders.SetCookie],
            "A checkout reads the guest token and never mints one (D8)",
        )
        assertEquals(
            """{"orderId":4711,"checkoutUrl":"https://pay.example/abc"}""",
            response.bodyAsText(),
        )
        assertEquals(listOf("checkout"), checkouts.calls)

        val shipping = checkouts.requests.single().shippingAddress
        assertEquals("München", shipping?.city)
        assertEquals("ada@example.org", shipping?.normalizedEmail)
        assertEquals("", shipping?.phone, "The blank phone reaches the operation as it was sent")
        assertNull(shipping?.normalizedPhone, "…and is normalized away behind it (D12)")
        assertNull(checkouts.requests.single().billingAddress)
    }

    @Test
    fun `the contact fields the frontend serializes on the billing address are ignored`() =
        testApplication {
            val checkouts = StubCheckoutOperations()
            application { installCheckoutTestApplication(checkouts) }
            val guest = createClient { install(HttpCookies) }
            val token = antiforgeryToken(guest)

            val response =
                guest.checkout(
                    token,
                    """
                    {"shippingAddress":$SHIPPING_JSON,
                     "billingAddress":{"firstName":"Grace","lastName":"Hopper",
                       "street":"Rechenweg","houseNumber":"1","postalCode":"10115",
                       "city":"Berlin","country":"DE",
                       "email":"grace@example.org","phone":"+49 30 1"}}
                    """
                        .trimIndent(),
                )

            assertEquals(HttpStatusCode.Created, response.status)
            assertEquals("Berlin", checkouts.requests.single().billingAddress?.city)
        }

    @Test
    fun `a free order answers the same shape with an explicit null url`() = testApplication {
        val checkouts =
            StubCheckoutOperations(
                CheckoutResult.Started(CheckoutResponse(orderId = 4711, checkoutUrl = null))
            )
        application { installCheckoutTestApplication(checkouts) }
        val guest = createClient { install(HttpCookies) }

        val response = guest.checkout(antiforgeryToken(guest), FRONTEND_BODY)

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("""{"orderId":4711,"checkoutUrl":null}""", response.bodyAsText())
    }

    @Test
    fun `a body that breaks the field rules is rejected before the operation`() = testApplication {
        val checkouts = StubCheckoutOperations()
        application { installCheckoutTestApplication(checkouts) }
        val guest = createClient { install(HttpCookies) }
        val token = antiforgeryToken(guest)

        listOf(
                "{}",
                """{"shippingAddress":{"firstName":"Ada"}}""",
                """{"shippingAddress":$SHIPPING_JSON,"billingAddress":{"city":"Berlin"}}""",
            )
            .forEach { body ->
                val response = guest.checkout(token, body)
                assertEquals(HttpStatusCode.BadRequest, response.status, body)
                assertEquals("Validation failed", response.message())
            }
        assertEquals(emptyList(), checkouts.calls)
    }

    @Test
    fun `the retry route answers the same body with 200 and no location`() = testApplication {
        val checkouts = StubCheckoutOperations()
        application { installCheckoutTestApplication(checkouts) }
        val guest = createClient { install(HttpCookies) }

        val response =
            guest.post("/api/checkout/orders/4711/payment") {
                header(AuthRouting.CSRF_HEADER, antiforgeryToken(guest))
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(response.headers[HttpHeaders.Location])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals(
            """{"orderId":4711,"checkoutUrl":"https://pay.example/abc"}""",
            response.bodyAsText(),
        )
        assertEquals(listOf("startPayment(4711)"), checkouts.calls)
    }

    @Test
    fun `an order id that is not a number is simply not found`() = testApplication {
        val checkouts = StubCheckoutOperations()
        application { installCheckoutTestApplication(checkouts) }
        val guest = createClient { install(HttpCookies) }

        val response =
            guest.post("/api/checkout/orders/not-a-number/payment") {
                header(AuthRouting.CSRF_HEADER, antiforgeryToken(guest))
            }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("Order not found", response.message())
        assertEquals(emptyList(), checkouts.calls)
    }

    @Test
    fun `every refusal has its status and its stable code`() = testApplication {
        val cases =
            listOf(
                CheckoutResult.EmptyCart to (HttpStatusCode.BadRequest to "CART_EMPTY"),
                CheckoutResult.PromotionRejected(PromotionCodeResult.Expired) to
                    (HttpStatusCode.BadRequest to "PROMOTION_EXPIRED"),
                CheckoutResult.PromotionRejected(PromotionCodeResult.LoginRequired) to
                    (HttpStatusCode.Forbidden to "PROMOTION_LOGIN_REQUIRED"),
                CheckoutResult.PromotionRejected(PromotionCodeResult.TotalExhausted) to
                    (HttpStatusCode.Conflict to "PROMOTION_TOTAL_EXHAUSTED"),
                CheckoutResult.ItemUnavailable to
                    (HttpStatusCode.Conflict to "CART_ITEM_UNAVAILABLE"),
                CheckoutResult.ImageUnavailable to
                    (HttpStatusCode.Conflict to "CART_IMAGE_UNAVAILABLE"),
                CheckoutResult.TotalTooLarge to (HttpStatusCode.Conflict to "CART_TOTAL_TOO_LARGE"),
                CheckoutResult.PaymentNotStarted to
                    (HttpStatusCode.BadGateway to "PAYMENT_NOT_STARTED"),
                CheckoutResult.OrderNotFound to (HttpStatusCode.NotFound to null),
                CheckoutResult.OrderNotPayable.AlreadyPaid to
                    (HttpStatusCode.Conflict to "ORDER_ALREADY_PAID"),
                CheckoutResult.OrderNotPayable.NotPayable to
                    (HttpStatusCode.Conflict to "ORDER_NOT_PAYABLE"),
                CheckoutResult.Invalid to (HttpStatusCode.InternalServerError to null),
                CheckoutResult.UnexpectedFailure to (HttpStatusCode.InternalServerError to null),
            )
        val checkouts = StubCheckoutOperations()
        application { installCheckoutTestApplication(checkouts) }
        val guest = createClient { install(HttpCookies) }
        val token = antiforgeryToken(guest)

        cases.forEach { (result, expected) ->
            val (status, code) = expected
            checkouts.answer = result

            val response = guest.checkout(token, FRONTEND_BODY)

            assertEquals(status, response.status, "$result")
            assertEquals(code, response.code(), "$result")
            assertNull(response.headers[HttpHeaders.Location], "$result")
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl], "$result")
        }
    }

    private suspend fun HttpClient.checkout(
        csrfToken: String,
        body: String,
    ): HttpResponse =
        post("/api/checkout") {
            header(AuthRouting.CSRF_HEADER, csrfToken)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun Application.installCheckoutTestApplication(checkouts: CheckoutOperations) {
        val authSettings = AuthSettings(SESSION_SECRET)
        installHttpRuntime()
        install(RequestValidation) { validateCheckoutRequests() }
        installAuthModule(authSettings)
        installCheckoutModule(checkouts, GuestTokens(authSettings))
    }

    private suspend fun ApplicationTestBuilder.antiforgeryToken(client: HttpClient): String {
        val response = client.get("/api/antiforgery/token")
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.parseToJsonElement(response.bodyAsText())
            .jsonObject
            .getValue("requestToken")
            .jsonPrimitive
            .content
    }

    private suspend fun HttpResponse.message(): String? = field("message")

    private suspend fun HttpResponse.code(): String? = field("code")

    private suspend fun HttpResponse.field(name: String): String? =
        Json.parseToJsonElement(bodyAsText()).jsonObject[name]?.jsonPrimitive?.content

    /** Records which operation a request reached, and answers everything the same way. */
    private class StubCheckoutOperations(
        var answer: CheckoutResult =
            CheckoutResult.Started(CheckoutResponse(4711, "https://pay.example/abc"))
    ) : CheckoutOperations {
        val calls: MutableList<String> = mutableListOf()
        val requests: MutableList<CheckoutRequest> = mutableListOf()

        override suspend fun checkout(
            guestToken: String?,
            userId: Long?,
            request: CheckoutRequest,
        ): CheckoutResult {
            calls += "checkout"
            requests += request
            return answer
        }

        override suspend fun startPayment(
            orderId: Long,
            guestToken: String?,
            userId: Long?,
        ): CheckoutResult {
            calls += "startPayment($orderId)"
            return answer
        }
    }

    private companion object {
        const val SESSION_SECRET = "checkout-route-security-session-secret"

        val SHIPPING_JSON =
            """
            {"firstName":"Ada","lastName":"Lovelace","street":"Musterweg",
             "houseNumber":"12a","postalCode":"80331","city":"München",
             "country":"DE","email":"ada@example.org","phone":""}
            """
                .trimIndent()

        /** The exact JSON the Vue store posts today, blank phone and `null` billing included. */
        val FRONTEND_BODY = """{"shippingAddress":$SHIPPING_JSON,"billingAddress":null}"""
    }
}
