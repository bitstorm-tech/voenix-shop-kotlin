package shop.voenix.cart

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.image.UploadedImage
import shop.voenix.operation.OperationResult

/**
 * What the cart routes decide before any cart logic runs: CSRF on every mutation, the shared field
 * rules on every body, and who gets a guest cookie.
 *
 * The operations are a stub that records its calls, so "rejected before the operation" is a
 * statement this test can actually make.
 */
internal class CartRouteSecurityAndValidationTest {
    @Test
    fun `every mutation is rejected without a csrf token, before the operation runs`() =
        testApplication {
            val carts = StubCartOperations()
            application { installCartTestApplication(carts) }

            listOf(
                    client.post("/api/cart/images"),
                    client.post("/api/cart/items"),
                    client.patch("/api/cart/items/1"),
                    client.delete("/api/cart/items/1"),
                    client.post("/api/cart/order-items/1"),
                    client.post("/api/cart/promotion"),
                    client.delete("/api/cart/promotion"),
                )
                .forEach { response ->
                    assertEquals(HttpStatusCode.BadRequest, response.status)
                    assertEquals("Invalid CSRF token", response.message())
                }
            assertEquals(emptyList(), carts.calls)
        }

    @Test
    fun `reading a cart needs no token and never creates a guest`() = testApplication {
        val carts = StubCartOperations()
        application { installCartTestApplication(carts) }

        val response = client.get("/api/cart")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertNull(response.headers[HttpHeaders.SetCookie], "A read must not create a guest")
        assertEquals(
            emptyList(),
            carts.calls,
            "Without a guest cookie there is no cart to read at all",
        )
        assertTrue(
            response.bodyAsText().contains("\"id\":null"),
            "A visitor without a cart sees the empty view, not a fabricated cart id",
        )
    }

    @Test
    fun `the first mutation hands out the guest cookie`() = testApplication {
        val carts = StubCartOperations()
        application { installCartTestApplication(carts) }
        val guest = createClient { install(HttpCookies) }
        val token = antiforgeryToken(guest)

        val response =
            guest.post("/api/cart/items") {
                header(AuthRouting.CSRF_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody("""{"articleId":10,"variantId":20,"quantity":2}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            response.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { cookie ->
                cookie.startsWith("voenix.guest=")
            },
            "A mutation must create the guest session it stores the cart under",
        )
        assertEquals(listOf("addItem"), carts.calls)
    }

    @Test
    fun `a body that breaks the field rules is rejected before the operation`() = testApplication {
        val carts = StubCartOperations()
        application { installCartTestApplication(carts) }
        val guest = createClient { install(HttpCookies) }
        val token = antiforgeryToken(guest)

        val add =
            guest.post("/api/cart/items") {
                header(AuthRouting.CSRF_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody("""{"articleId":10,"variantId":20,"quantity":0}""")
            }
        assertEquals(HttpStatusCode.BadRequest, add.status)
        assertEquals("Validation failed", add.message())

        val quantity =
            guest.patch("/api/cart/items/1") {
                header(AuthRouting.CSRF_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody("""{"quantity":100}""")
            }
        assertEquals(HttpStatusCode.BadRequest, quantity.status)

        val promotion =
            guest.post("/api/cart/promotion") {
                header(AuthRouting.CSRF_HEADER, token)
                contentType(ContentType.Application.Json)
                setBody("""{"promotionCode":"   "}""")
            }
        assertEquals(HttpStatusCode.BadRequest, promotion.status)

        assertEquals(emptyList(), carts.calls)
    }

    @Test
    fun `a line id that is not a number is simply not found`() = testApplication {
        val carts = StubCartOperations()
        application { installCartTestApplication(carts) }
        val guest = createClient { install(HttpCookies) }
        val token = antiforgeryToken(guest)

        val response =
            guest.delete("/api/cart/items/not-a-number") { header(AuthRouting.CSRF_HEADER, token) }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(emptyList(), carts.calls)
    }

    @Test
    fun `an order item id that is not a number is not found, and names the order item`() =
        testApplication {
            val carts = StubCartOperations()
            application { installCartTestApplication(carts) }
            val guest = createClient { install(HttpCookies) }
            val token = antiforgeryToken(guest)

            val response =
                guest.post("/api/cart/order-items/not-a-number") {
                    header(AuthRouting.CSRF_HEADER, token)
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("Order item not found", response.message())
            assertEquals(emptyList(), carts.calls)
        }

    private fun Application.installCartTestApplication(carts: CartOperations) {
        val authSettings = AuthSettings(SESSION_SECRET)
        installHttpRuntime()
        install(RequestValidation) { validateCartRequests() }
        installAuthModule(authSettings)
        installCartModule(carts, GuestTokens(authSettings))
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

    private suspend fun HttpResponse.message(): String? =
        Json.parseToJsonElement(bodyAsText()).jsonObject["message"]?.jsonPrimitive?.content

    /** Records which operation a request reached, and answers everything with an empty cart. */
    private class StubCartOperations : CartOperations {
        val calls: MutableList<String> = mutableListOf()

        override suspend fun cart(owner: CartOwner): OperationResult<CartView> {
            calls += "cart"
            return OperationResult.Success(CartView.EMPTY)
        }

        override suspend fun uploadPrintImage(
            owner: CartOwner,
            upload: UploadedImage,
        ): OperationResult<PrintImageId> {
            calls += "uploadPrintImage"
            return OperationResult.Success(PrintImageId(1))
        }

        override suspend fun addItem(
            owner: CartOwner,
            input: AddCartItemInput,
        ): OperationResult<CartView> {
            calls += "addItem"
            return OperationResult.Success(CartView.EMPTY)
        }

        override suspend fun reorder(
            owner: CartOwner,
            orderItemId: Long,
        ): OperationResult<CartView> {
            calls += "reorder"
            return OperationResult.Success(CartView.EMPTY)
        }

        override suspend fun updateQuantity(
            owner: CartOwner,
            itemId: Long,
            input: CartQuantityInput,
        ): OperationResult<CartView> {
            calls += "updateQuantity"
            return OperationResult.Success(CartView.EMPTY)
        }

        override suspend fun removeItem(
            owner: CartOwner,
            itemId: Long,
        ): OperationResult<CartView> {
            calls += "removeItem"
            return OperationResult.Success(CartView.EMPTY)
        }

        override suspend fun applyPromotion(
            owner: CartOwner,
            input: PromotionCodeInput,
        ): CartPromotionResult {
            calls += "applyPromotion"
            return CartPromotionResult.Applied(CartView.EMPTY)
        }

        override suspend fun removePromotion(owner: CartOwner): OperationResult<CartView> {
            calls += "removePromotion"
            return OperationResult.Success(CartView.EMPTY)
        }
    }

    private companion object {
        const val SESSION_SECRET = "cart-route-security-session-secret"
    }
}
