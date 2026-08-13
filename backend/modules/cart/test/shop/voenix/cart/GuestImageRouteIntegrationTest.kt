package shop.voenix.cart

import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.image.ImageSettings
import shop.voenix.image.installGuestImageRoute
import shop.voenix.image.installImageModule
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The print image from upload to delivery, with the image and cart modules composed exactly as the
 * application composes them.
 *
 * The point of the test is the seam between the two: the image module owns the route and knows
 * nothing about carts, the cart module owns the ownership records and knows nothing about files,
 * and the composition root is what connects them. Everything here — the upload, the WebP
 * normalization, the delivery, the compensation — runs through the real implementations.
 */
internal class GuestImageRouteIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `the guest who uploaded an image is served it, and nobody else is`() =
        withComposedApplication("owner") { fixture ->
            val guest = fixture.guestClient()
            val imageId = fixture.upload(guest)

            val served = guest.get("/api/images/guest/120/$imageId")
            assertEquals(HttpStatusCode.OK, served.status)
            assertTrue(served.body().isNotEmpty())
            assertNull(
                served.headers[HttpHeaders.SetCookie],
                "Delivering an image must not create a guest session",
            )
            assertEquals("private, max-age=3600", served.headers[HttpHeaders.CacheControl])

            val stranger = fixture.builder.createClient { install(HttpCookies) }
            assertEquals(
                HttpStatusCode.NotFound,
                stranger.get("/api/images/guest/120/$imageId").status,
                "A foreign caller must not be able to tell the image apart from a missing one",
            )
            assertEquals(
                HttpStatusCode.NotFound,
                guest.get("/api/images/guest/120/999999").status,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                guest.get("/api/images/guest/no/$imageId").status,
            )
        }

    /**
     * An upload made while signed in is reachable by its **user id** and by nothing else, which is
     * the whole of print-image ownership since the login claim is gone (issue #110): no image ever
     * changes hands, so the only question left is which identity a row was written with.
     */
    @Test
    fun `signing out leaves the browser with a token that no longer reaches the upload`() =
        withComposedApplication("logout") { fixture ->
            val browser = fixture.guestClient()
            fixture.signIn(browser, CartTestSupport.USER_ID)
            val imageId = fixture.upload(browser)

            // The logout clears the session only: the guest cookie is deliberately kept, so the
            // browser goes on carrying exactly the token the signed-in upload was written with.
            assertEquals(HttpStatusCode.OK, browser.post("/test/sign-out").status)
            assertEquals(
                HttpStatusCode.NotFound,
                browser.get("/api/images/guest/120/$imageId").status,
                "The token an image carries next to its user must not be a handle on it — it is " +
                    "the next person at this browser who would use it",
            )
            val added = fixture.addItemWithImage(browser, imageId)
            assertEquals(HttpStatusCode.BadRequest, added.status)
            assertEquals(
                listOf("The image cannot be used"),
                Json.parseToJsonElement(added.bodyAsText())
                    .jsonObject
                    .getValue("errors")
                    .jsonObject
                    .getValue("imageId")
                    .jsonArray
                    .map { it.jsonPrimitive.content },
                "and the signed-out browser must not attach it to its own cart either",
            )

            val customer = fixture.builder.createClient { install(HttpCookies) }
            fixture.signIn(customer, CartTestSupport.USER_ID)
            assertEquals(
                HttpStatusCode.OK,
                customer.get("/api/images/guest/120/$imageId").status,
                "The customer the image belongs to still reaches it",
            )
        }

    @Test
    fun `an upload whose row cannot be written leaves no file behind`() =
        withComposedApplication("compensation") { fixture ->
            val ghost = fixture.guestClient()
            // The session names a user that does not exist, so the print-image row violates its
            // foreign key after the file has already been written. Signing in first also mints a
            // CSRF token for that user, which the guest-capable protection insists on.
            fixture.signIn(ghost, userId = 999_999)

            val response = fixture.uploadResponse(ghost)

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals(
                0,
                CartTestSupport.count(
                    fixture.dataSource,
                    "SELECT count(*) FROM voenix.print_images",
                ),
            )
            assertEquals(
                emptyList(),
                fixture.storedPrintImageFiles(),
                "The compensating delete must remove the file the failed upload wrote",
            )
        }

    private fun withComposedApplication(
        name: String,
        test: suspend ApplicationTestBuilder.(Fixture) -> Unit,
    ) {
        val imageRoot = createTempDirectory("cart-guest-image-$name")
        try {
            migratedDataSource("cart-guest-image-$name").use { dataSource ->
                CartTestSupport.seed(dataSource)
                lateinit var fixture: Fixture
                testApplication {
                    application {
                        val authSettings = AuthSettings(SESSION_SECRET)
                        val guestTokens = GuestTokens(authSettings)
                        installHttpRuntime()
                        install(RequestValidation) { validateCartRequests() }
                        installAuthModule(authSettings)
                        val images = installImageModule(imageSettings(imageRoot))
                        val cart =
                            installCartModule(
                                Database.connect(dataSource),
                                CartTestSupport.FakeArticles(
                                    mapOf(CartTestSupport.REFERENCE to CartTestSupport.variant())
                                ),
                                CartTestSupport.FakePrompts(),
                                CartTestSupport.FakePromotions(),
                                images.privateStorage,
                                CartTestSupport.FakeOrderItems(),
                                guestTokens,
                            )
                        installGuestImageRoute(images, guestTokens, cart.guestImages)
                        routing {
                            post("/test/sign-in") {
                                call.sessions.set(
                                    UserSession(
                                        userId = call.request.queryParameters["userId"].orEmpty(),
                                        role = "CUSTOMER",
                                    )
                                )
                                call.respond(HttpStatusCode.OK)
                            }
                            // The real logout clears the session and keeps the guest cookie.
                            post("/test/sign-out") {
                                call.sessions.clear<UserSession>()
                                call.respond(HttpStatusCode.OK)
                            }
                        }
                        fixture = Fixture(this@testApplication, dataSource, imageRoot)
                    }
                    startApplication()
                    test(fixture)
                }
            }
        } finally {
            imageRoot.toFile().deleteRecursively()
        }
    }

    private fun imageSettings(root: Path): ImageSettings =
        ImageSettings.from(
            MapApplicationConfig(
                "image.publicRoot" to root.resolve("public").toString(),
                "image.privateRoot" to root.resolve("private").toString(),
                "image.cacheRoot" to root.resolve("cache").toString(),
            )
        )

    private class Fixture(
        val builder: ApplicationTestBuilder,
        val dataSource: HikariDataSource,
        private val imageRoot: Path,
    ) {
        private var csrfToken: String = ""

        suspend fun guestClient(): HttpClient {
            val client = builder.createClient { install(HttpCookies) }
            refreshCsrfToken(client)
            return client
        }

        /**
         * Signs [client] in and mints a fresh CSRF token for it. The order matters: the
         * guest-capable protection rejects a token that was minted before the session existed.
         */
        suspend fun signIn(
            client: HttpClient,
            userId: Long,
        ) {
            check(client.post("/test/sign-in?userId=$userId").status == HttpStatusCode.OK)
            refreshCsrfToken(client)
        }

        private suspend fun refreshCsrfToken(client: HttpClient) {
            csrfToken =
                Json.parseToJsonElement(client.get("/api/antiforgery/token").bodyAsText())
                    .jsonObject
                    .getValue("requestToken")
                    .jsonPrimitive
                    .content
        }

        suspend fun upload(client: HttpClient): Long {
            val response = uploadResponse(client)
            check(response.status == HttpStatusCode.Created) { "Upload failed: ${response.status}" }
            return Json.parseToJsonElement(response.bodyAsText())
                .jsonObject
                .getValue("id")
                .jsonPrimitive
                .content
                .toLong()
        }

        suspend fun uploadResponse(client: HttpClient): HttpResponse =
            client.post("/api/cart/images") {
                header(AuthRouting.CSRF_HEADER, csrfToken)
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append(
                                "file",
                                pngBytes(),
                                Headers.build {
                                    append(HttpHeaders.ContentType, "image/png")
                                    append(
                                        HttpHeaders.ContentDisposition,
                                        "filename=\"print.png\"",
                                    )
                                },
                            )
                        }
                    )
                )
            }

        /** Attaches [imageId] to a line of [client]'s own cart — the second ownership check. */
        suspend fun addItemWithImage(
            client: HttpClient,
            imageId: Long,
        ): HttpResponse =
            client.post("/api/cart/items") {
                header(AuthRouting.CSRF_HEADER, csrfToken)
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {"articleId":${CartTestSupport.ARTICLE_ID},
                     "variantId":${CartTestSupport.VARIANT_ID},
                     "quantity":1,
                     "imageId":$imageId}
                    """
                        .trimIndent()
                )
            }

        fun storedPrintImageFiles(): List<String> {
            val folder = imageRoot.resolve("private").resolve("print-images")
            if (!Files.isDirectory(folder)) return emptyList()
            return Files.list(folder).use { files ->
                files.map { file -> file.fileName.toString() }.toList()
            }
        }
    }

    private companion object {
        const val SESSION_SECRET = "cart-guest-image-route-session-secret"

        suspend fun HttpResponse.body(): ByteArray = bodyAsBytes()

        fun pngBytes(): ByteArray {
            val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
            val bytes = ByteArrayOutputStream()
            ImageIO.write(image, "png", bytes)
            return bytes.toByteArray()
        }
    }
}
