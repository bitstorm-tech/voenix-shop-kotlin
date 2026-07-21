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
import kotlin.test.assertTrue
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.UserSession
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.operation.OperationResult

internal class ImageRoutesTest {
    @Test
    fun `public files support caching conditional requests and byte ranges`() =
        withResource { resource, bytes ->
            testApplication {
                val images = StubImageOperations(OperationResult.Success(resource))
                application { installImageTestApplication(images) }

                val response = client.get("/api/images/public/120/nested/sample.png")
                assertEquals(HttpStatusCode.OK, response.status)
                assertContentEquals(bytes, response.bodyAsBytes())
                assertEquals(
                    ContentType.Image.PNG.toString(),
                    response.headers[HttpHeaders.ContentType],
                )
                assertEquals(bytes.size.toString(), response.headers[HttpHeaders.ContentLength])
                assertEquals("public, max-age=86400", response.headers[HttpHeaders.CacheControl])
                assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges])
                assertNotNull(response.headers[HttpHeaders.LastModified])
                val etag = assertNotNull(response.headers[HttpHeaders.ETag])
                assertEquals(
                    Triple(ImageVisibility.PUBLIC, "120", "nested/sample.png"),
                    images.calls.single(),
                )

                val unchanged =
                    client.get("/api/images/public/120/nested/sample.png") {
                        header(HttpHeaders.IfNoneMatch, etag)
                    }
                assertEquals(HttpStatusCode.NotModified, unchanged.status)

                val range =
                    client.get("/api/images/public/120/nested/sample.png") {
                        header(HttpHeaders.Range, "bytes=0-9")
                    }
                assertEquals(HttpStatusCode.PartialContent, range.status)
                assertContentEquals(bytes.copyOfRange(0, 10), range.bodyAsBytes())
            }
        }

    @Test
    fun `private files enforce authentication before image operations`() =
        withResource { resource, _ ->
            testApplication {
                val images = StubImageOperations(OperationResult.Success(resource))
                application { installImageTestApplication(images) }

                val anonymous = client.get("/api/images/private/120/private.png")
                assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
                assertTrue(anonymous.bodyAsText().contains("Authentication required"))
                assertEquals(0, images.calls.size)

                val signedIn = signedInClient()
                val response = signedIn.get("/api/images/private/120/private.png")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("private, max-age=3600", response.headers[HttpHeaders.CacheControl])
                assertEquals(ImageVisibility.PRIVATE, images.calls.single().first)
            }
        }

    @Test
    fun `operation failures map to shared api errors`() = withResource { _, _ ->
        testApplication {
            val images = StubImageOperations(OperationResult.NotFound)
            application { installImageTestApplication(images) }

            val notFound = client.get("/api/images/public/100/missing.png")
            assertEquals(HttpStatusCode.NotFound, notFound.status)
            assertTrue(notFound.bodyAsText().contains("Image not found"))

            images.result = OperationResult.Invalid(mapOf("size" to listOf("Invalid size")))
            val invalid = client.get("/api/images/public/nope/image.png")
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertTrue(invalid.bodyAsText().contains("Validation failed"))
            assertTrue(invalid.bodyAsText().contains("Invalid size"))

            images.result = OperationResult.UnexpectedFailure
            val failed = client.get("/api/images/public/100/image.png")
            assertEquals(HttpStatusCode.InternalServerError, failed.status)
            assertTrue(failed.bodyAsText().contains("Internal server error"))
        }
    }

    private fun Application.installImageTestApplication(images: ImageOperations) {
        installHttpRuntime()
        installAuthModule(AuthSettings("image-route-contract-session-secret"))
        installImageModule(images)
        routing {
            post("/test/sign-in") {
                call.sessions.set(UserSession(userId = "11", role = "CUSTOMER"))
                call.respond(HttpStatusCode.OK)
            }
        }
    }

    private suspend fun ApplicationTestBuilder.signedInClient(): HttpClient = createClient {
        install(HttpCookies)
    }
        .also { signedIn -> assertEquals(HttpStatusCode.OK, signedIn.post("/test/sign-in").status) }

    private fun withResource(test: (ImageResource, ByteArray) -> Unit) {
        val root = createTempDirectory("image-route-test")
        try {
            val bytes = ByteArray(256) { it.toByte() }
            val path = Files.write(root.resolve("sample.png"), bytes)
            test(
                ImageResource(
                    path = path,
                    contentType = ContentType.Image.PNG,
                    length = Files.size(path),
                    lastModifiedMillis = Files.getLastModifiedTime(path).toMillis(),
                ),
                bytes,
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class StubImageOperations(var result: OperationResult<ImageResource>) :
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
}
