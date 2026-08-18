package shop.voenix.generator

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What this adapter promises the rest of the module: exactly one request shape goes to fal.ai, and
 * everything that can go wrong afterwards comes back as an absent image.
 *
 * Every test drives a [MockEngine], so no test ever reaches the real provider — which is also why
 * the settings here carry the production URL: what the tests pin is the constant the deployment
 * uses, and the engine answers it instead of the internet.
 */
internal class FalImageGeneratorTest {
    @Test
    fun `sends the exact fal contract and answers with the downloaded image`() = runBlocking {
        var generationUrl = ""
        var authorization = ""
        var contentType = ""
        var body = ""
        var downloadUrl = ""
        var downloadAuthorization: String? = "not asked yet"
        val generator = falImageGenerator { request ->
            if (request.url.host == FAL_HOST) {
                generationUrl = request.url.toString()
                authorization = request.headers[HttpHeaders.Authorization].orEmpty()
                contentType = request.body.contentType.toString()
                body = request.body.toByteArray().decodeToString()
                respondGeneration(url = RESULT_URL, contentType = "image/png")
            } else {
                downloadUrl = request.url.toString()
                downloadAuthorization = request.headers[HttpHeaders.Authorization]
                respond(GENERATED_BYTES)
            }
        }

        val generated = assertNotNull(generator.generate(uploadedImage(), PROMPT))

        assertEquals("https://fal.run/fal-ai/nano-banana-2/edit", generationUrl)
        assertEquals("Key secret-key", authorization)
        assertContains(contentType, "application/json")

        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals(
            setOf("image_urls", "prompt", "num_images", "aspect_ratio"),
            sent.keys,
            "the provider contract is snake_case and carries these four fields only",
        )
        assertEquals(
            listOf("data:image/png;base64,aGVsbG8="),
            sent.getValue("image_urls").jsonArray.map { url -> url.jsonPrimitive.content },
        )
        assertEquals(PROMPT, sent.getValue("prompt").jsonPrimitive.content)
        assertEquals(1, sent.getValue("num_images").jsonPrimitive.int)
        assertEquals("16:9", sent.getValue("aspect_ratio").jsonPrimitive.content)

        assertEquals(RESULT_URL, downloadUrl, "the result is fetched from the URL fal.ai named")
        assertNull(
            downloadAuthorization,
            "the API key must never travel to the host serving the result",
        )
        assertContentEquals(GENERATED_BYTES, generated.bytes)
        assertEquals("image/png", generated.contentType)
    }

    @Test
    fun `a refused generation is an absent image`() = runBlocking {
        val generator = falImageGenerator {
            respondError(HttpStatusCode.TooManyRequests, "rate limited")
        }

        assertNull(generator.generate(uploadedImage(), PROMPT))
    }

    @Test
    fun `an answer without an image is an absent image`() = runBlocking {
        val generator = falImageGenerator { respondJson("""{"images":[]}""") }

        assertNull(generator.generate(uploadedImage(), PROMPT))
    }

    @Test
    fun `an unreadable answer is an absent image`() = runBlocking {
        val generator = falImageGenerator { respondJson("{ this is not json") }

        assertNull(generator.generate(uploadedImage(), PROMPT))
    }

    /**
     * The generation answer is capped like the download is. A JSON body that large is not one this
     * adapter could use anyway, and reading it would let the provider decide how much memory a
     * generation costs.
     */
    @Test
    fun `a generation answer larger than an image may be is an absent image`() = runBlocking {
        val generator = falImageGenerator {
            respond(
                content = ByteArray(MAX_IMAGE_BYTES + 1),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        assertNull(generator.generate(uploadedImage(), PROMPT))
    }

    @Test
    fun `a timeout is an absent image`() = runBlocking {
        val generator = falImageGenerator { throw SocketTimeoutException("Read timed out") }

        assertNull(generator.generate(uploadedImage(), PROMPT))
    }

    @Test
    fun `an unreachable provider is an absent image`() = runBlocking {
        val generator = falImageGenerator { throw IOException("Connection reset") }

        assertNull(generator.generate(uploadedImage(), PROMPT))
    }

    @Test
    fun `a failed download is an absent image`() = runBlocking {
        val generator = falImageGenerator { request ->
            if (request.url.host == FAL_HOST) {
                respondGeneration(url = RESULT_URL, contentType = "image/png")
            } else {
                respondError(HttpStatusCode.NotFound)
            }
        }

        assertNull(generator.generate(uploadedImage(), PROMPT))
    }

    @Test
    fun `an unreachable download host is an absent image`() = runBlocking {
        val generator = falImageGenerator { request ->
            if (request.url.host == FAL_HOST) {
                respondGeneration(url = RESULT_URL, contentType = "image/png")
            } else {
                throw IOException("Connection reset")
            }
        }

        assertNull(generator.generate(uploadedImage(), PROMPT))
    }

    @Test
    fun `a result content type this shop does not serve becomes jpeg`() = runBlocking {
        val generator = falImageGenerator { request ->
            if (request.url.host == FAL_HOST) {
                respondGeneration(url = RESULT_URL, contentType = "application/octet-stream")
            } else {
                respond(GENERATED_BYTES)
            }
        }

        assertEquals(
            "image/jpeg",
            assertNotNull(generator.generate(uploadedImage(), PROMPT)).contentType,
        )
    }

    @Test
    fun `a missing result content type becomes jpeg`() = runBlocking {
        val generator = falImageGenerator { request ->
            if (request.url.host == FAL_HOST) {
                respondJson("""{"images":[{"url":"$RESULT_URL"}]}""")
            } else {
                respond(GENERATED_BYTES)
            }
        }

        assertEquals(
            "image/jpeg",
            assertNotNull(generator.generate(uploadedImage(), PROMPT)).contentType,
        )
    }

    @Test
    fun `a result URL that is not HTTPS is never fetched`() = runBlocking {
        var downloadAttempted = false
        val generator = falImageGenerator { request ->
            if (request.url.host == FAL_HOST) {
                respondGeneration(url = "http://cdn.example.com/result.png", "image/png")
            } else {
                downloadAttempted = true
                respond(GENERATED_BYTES)
            }
        }

        assertNull(generator.generate(uploadedImage(), PROMPT))
        assertEquals(false, downloadAttempted, "a plaintext result URL is refused, not fetched")
    }

    @Test
    fun `a result larger than an upload may be is an absent image`() = runBlocking {
        val generator = falImageGenerator { request ->
            if (request.url.host == FAL_HOST) {
                respondGeneration(url = RESULT_URL, contentType = "image/png")
            } else {
                respond(ByteArray(MAX_IMAGE_BYTES + 1))
            }
        }

        assertNull(generator.generate(uploadedImage(), PROMPT))
    }

    @Test
    fun `a cancelled request stays cancelled`(): Unit = runBlocking {
        val generator = falImageGenerator { throw CancellationException("The visitor left") }

        assertFailsWith<CancellationException> { generator.generate(uploadedImage(), PROMPT) }
    }

    /**
     * The timeouts are the adapter's own, not the test's: the generator builds its client around
     * the `MockEngine` handed in, so what a request carries here is what a deployment sends. Two
     * minutes for the request and the socket, because generating an image takes far longer than an
     * ordinary API call.
     */
    @Test
    fun `the client fal ai is called through carries the configured timeouts`() = runBlocking {
        var timeouts: HttpTimeoutConfig? = null
        val generator = falImageGenerator { request ->
            if (request.url.host == FAL_HOST) {
                timeouts = request.getCapabilityOrNull(HttpTimeoutCapability)
                respondGeneration(url = RESULT_URL, contentType = "image/png")
            } else {
                respond(GENERATED_BYTES)
            }
        }

        assertNotNull(generator.generate(uploadedImage(), PROMPT))

        val configured = assertNotNull(timeouts)
        assertEquals(10_000L, configured.connectTimeoutMillis)
        assertEquals(120_000L, configured.requestTimeoutMillis)
        assertEquals(120_000L, configured.socketTimeoutMillis)
    }

    /**
     * Redirects are followed on the *download*, because the generated image usually lives behind a
     * CDN that redirects. Walking one is safe precisely here: the download carries no credential,
     * and the URL it started from had to be HTTPS to be fetched at all. The paid generation call is
     * never redirected — this test only ever answers the `GET` with a `302`.
     */
    @Test
    fun `a redirecting download is followed`() = runBlocking {
        val requested = mutableListOf<String>()
        val generator = falImageGenerator { request ->
            when {
                request.url.host == FAL_HOST ->
                    respondGeneration(url = RESULT_URL, contentType = "image/png")
                request.url.toString() == RESULT_URL -> {
                    requested += request.url.toString()
                    respond(
                        content = "",
                        status = HttpStatusCode.Found,
                        headers = headersOf(HttpHeaders.Location, FINAL_URL),
                    )
                }
                request.url.toString() == FINAL_URL -> {
                    requested += request.url.toString()
                    respond(GENERATED_BYTES)
                }
                else -> error("unexpected request to ${request.url}")
            }
        }

        val generated = assertNotNull(generator.generate(uploadedImage(), PROMPT))

        assertContentEquals(GENERATED_BYTES, generated.bytes)
        assertEquals(listOf(RESULT_URL, FINAL_URL), requested, "the redirect is walked once")
    }

    private fun MockRequestHandleScope.respondGeneration(
        url: String,
        contentType: String,
    ): HttpResponseData =
        respondJson("""{"images":[{"url":"$url","content_type":"$contentType"}],"seed":42}""")

    /** An unknown field rides along in every generation answer: the adapter must ignore it. */
    private fun MockRequestHandleScope.respondJson(payload: String): HttpResponseData =
        respond(
            content = payload,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )

    /**
     * The adapter under test, answering out of [handler] instead of out of the network. Only the
     * engine is the test's; the client around it is built by the adapter itself, so these tests
     * drive the very configuration a deployment runs.
     */
    private fun falImageGenerator(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
    ): FalImageGenerator =
        FalImageGenerator(
            GeneratorSettings(dummyMode = false, apiKey = "secret-key"),
            MockEngine(handler),
        )

    private fun uploadedImage(): RawImage = RawImage("hello".toByteArray(), "image/png")

    private companion object {
        const val FAL_HOST = "fal.run"
        const val RESULT_URL = "https://cdn.example.com/result.png"
        const val FINAL_URL = "https://cdn.example.com/final.png"
        const val PROMPT = "Ein Mops im Weltall"

        val GENERATED_BYTES = byteArrayOf(1, 2, 3, 4, 5)
    }
}
