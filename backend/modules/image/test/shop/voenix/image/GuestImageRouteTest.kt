package shop.voenix.image

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.operation.OperationResult

/**
 * The ownership matrix of `GET /api/images/guest/{size}/{id}` against a fake resolver.
 *
 * The resolver stands in for the module that owns the ownership records. What is proven here is
 * only what the route itself decides: which identity it hands to the resolver, that a `null` answer
 * is always `404` and never `403`, that an invalid size is `400`, and that the route never issues a
 * guest cookie — reading somebody's image must not create a guest session.
 */
internal class GuestImageRouteTest {
    @Test
    fun `a guest owner is served through the guest cookie without receiving a new one`() =
        withResource { resource, bytes ->
            testApplication {
                val resolver = FakeResolver(guestOwned = mapOf(1L to "owned.webp"))
                val images = StubImageOperations(OperationResult.Success(resource))
                application { installGuestRouteTestApplication(images, resolver) }

                val guest = guestClient()
                val response = guest.get("/api/images/guest/120/1")

                assertEquals(HttpStatusCode.OK, response.status)
                assertContentEquals(bytes, response.bodyAsBytes())
                assertNull(
                    response.headers[HttpHeaders.SetCookie],
                    "The guest route must never create a guest session",
                )
                assertEquals("private, max-age=3600", response.headers[HttpHeaders.CacheControl])
                assertEquals(
                    Triple(ImageVisibility.PRIVATE, "120", "$PRINT_IMAGE_FOLDER/owned.webp"),
                    images.calls.single(),
                )
                val call = resolver.calls.single()
                assertEquals(1L, call.imageId)
                assertNotNull(call.guestToken, "The guest cookie must reach the resolver")
                assertNull(call.userId)
            }
        }

    @Test
    fun `a logged-in owner is served through the session without any guest cookie`() =
        withResource { resource, _ ->
            testApplication {
                val resolver = FakeResolver(userOwned = mapOf(1L to "owned.webp"))
                val images = StubImageOperations(OperationResult.Success(resource))
                application { installGuestRouteTestApplication(images, resolver) }

                val response = signedInClient().get("/api/images/guest/120/1")

                assertEquals(HttpStatusCode.OK, response.status)
                assertNull(response.headers[HttpHeaders.SetCookie])
                val call = resolver.calls.single()
                assertNull(call.guestToken)
                assertEquals(SIGNED_IN_USER_ID, call.userId)
            }
        }

    @Test
    fun `a foreign image an unknown id and a non-numeric id are all plain not found`() =
        withResource { resource, _ ->
            testApplication {
                val resolver = FakeResolver(guestOwned = mapOf(1L to "owned.webp"))
                val images = StubImageOperations(OperationResult.Success(resource))
                application { installGuestRouteTestApplication(images, resolver) }

                val guest = guestClient()
                listOf("2", "999", "abc").forEach { id ->
                    val response = guest.get("/api/images/guest/120/$id")
                    assertEquals(HttpStatusCode.NotFound, response.status, "id $id")
                    assertTrue(response.bodyAsText().contains("Image not found"))
                    assertNull(response.headers[HttpHeaders.SetCookie])
                }
                assertTrue(images.calls.isEmpty(), "An unowned image is never read from disk")
            }
        }

    @Test
    fun `an anonymous request without any identity owns nothing`() = withResource { resource, _ ->
        testApplication {
            val resolver = FakeResolver(guestOwned = mapOf(1L to "owned.webp"))
            val images = StubImageOperations(OperationResult.Success(resource))
            application { installGuestRouteTestApplication(images, resolver) }

            val response = client.get("/api/images/guest/120/1")

            assertEquals(HttpStatusCode.NotFound, response.status)
            assertNull(response.headers[HttpHeaders.SetCookie])
            val call = resolver.calls.single()
            assertNull(call.guestToken)
            assertNull(call.userId)
        }
    }

    @Test
    fun `an invalid size on an owned image is a validation failure`() = withResource { _, _ ->
        testApplication {
            val resolver = FakeResolver(guestOwned = mapOf(1L to "owned.webp"))
            val images =
                StubImageOperations(
                    OperationResult.Invalid(mapOf("size" to listOf("Invalid size")))
                )
            application { installGuestRouteTestApplication(images, resolver) }

            val response = guestClient().get("/api/images/guest/nope/1")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("Invalid size"))
            assertNull(response.headers[HttpHeaders.SetCookie])
        }
    }

    @Test
    fun `the guest route shares the conditional headers of the private route`() =
        withResource { resource, _ ->
            testApplication {
                val resolver = FakeResolver(guestOwned = mapOf(1L to "owned.webp"))
                val images = StubImageOperations(OperationResult.Success(resource))
                application { installGuestRouteTestApplication(images, resolver) }

                val guest = guestClient()
                val response = guest.get("/api/images/guest/120/1")
                val etag = assertNotNull(response.headers[HttpHeaders.ETag])
                assertNotNull(response.headers[HttpHeaders.LastModified])
                assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges])

                val unchanged =
                    guest.get("/api/images/guest/120/1") { header(HttpHeaders.IfNoneMatch, etag) }
                assertEquals(HttpStatusCode.NotModified, unchanged.status)
            }
        }

    private fun Application.installGuestRouteTestApplication(
        images: ImageOperations,
        resolver: GuestImageResolver,
    ) {
        val authSettings = AuthSettings("guest-image-route-contract-session-secret")
        installHttpRuntime()
        installAuthModule(authSettings)
        installImageModule(images)
        installGuestImageRoute(images, GuestTokens(authSettings), resolver)
        routing {
            post("/test/sign-in") {
                call.sessions.set(
                    UserSession(userId = SIGNED_IN_USER_ID.toString(), role = "CUSTOMER")
                )
                call.respond(HttpStatusCode.OK)
            }
            post("/test/become-guest") {
                GuestTokens(authSettings).getOrCreate(call)
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.signedInClient(): HttpClient = createClient {
        install(HttpCookies)
    }
        .also { assertEquals(HttpStatusCode.OK, it.post("/test/sign-in").status) }

    private suspend fun ApplicationTestBuilder.guestClient(): HttpClient = createClient {
        install(HttpCookies)
    }
        .also { assertEquals(HttpStatusCode.OK, it.post("/test/become-guest").status) }

    private fun withResource(test: (ImageResource, ByteArray) -> Unit) {
        val root = createTempDirectory("guest-image-route-test")
        try {
            val bytes = ByteArray(256) { it.toByte() }
            val path = Files.write(root.resolve("sample.webp"), bytes)
            test(
                ImageResource(
                    path = path,
                    contentType = ContentType("image", "webp"),
                    length = Files.size(path),
                    lastModifiedMillis = Files.getLastModifiedTime(path).toMillis(),
                ),
                bytes,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    /**
     * Stands in for the cart-owned lookup. It knows one guest-owned and one user-owned id and
     * answers `null` for everything else — exactly what the real resolver must do for a foreign
     * image, so the route cannot tell the two apart either.
     */
    private class FakeResolver(
        private val guestOwned: Map<Long, String> = emptyMap(),
        private val userOwned: Map<Long, String> = emptyMap(),
    ) : GuestImageResolver {
        val calls = mutableListOf<ResolverCall>()

        override suspend fun resolve(
            imageId: Long,
            guestToken: String?,
            userId: Long?,
        ): String? {
            calls += ResolverCall(imageId, guestToken, userId)
            return when {
                guestToken != null && guestOwned.containsKey(imageId) -> guestOwned[imageId]
                userId != null && userOwned.containsKey(imageId) -> userOwned[imageId]
                else -> null
            }
        }
    }

    private data class ResolverCall(
        val imageId: Long,
        val guestToken: String?,
        val userId: Long?,
    )

    private class StubImageOperations(private val result: OperationResult<ImageResource>) :
        ImageOperations {
        val calls = mutableListOf<Triple<ImageVisibility, String, String>>()

        override suspend fun get(
            visibility: ImageVisibility,
            requestedSize: String,
            filename: String,
        ): OperationResult<ImageResource> {
            calls += Triple(visibility, requestedSize, filename)
            return result
        }
    }

    private companion object {
        private const val SIGNED_IN_USER_ID = 11L
    }
}
