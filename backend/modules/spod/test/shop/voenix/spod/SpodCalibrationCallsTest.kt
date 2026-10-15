package shop.voenix.spod

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * The two calls that calibrate a product type — its views and a preview of a design on it — held to
 * the same promises as every other API call of [SpodClient]: the exact request goes out with the
 * destination's token and timeout, refusals become the same bounded codes, and the calls are paced.
 */
internal class SpodCalibrationCallsTest {
    @Test
    fun `the views of a product type are read with the access token and the timeout`() =
        runBlocking {
            var url = ""
            var method = ""
            var token = ""
            var timeoutMillis: Long? = null
            val client = spodClient { request ->
                url = request.url.toString()
                method = request.method.value
                token = request.headers[SpodClient.ACCESS_TOKEN_HEADER].orEmpty()
                timeoutMillis =
                    request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis
                respondJson(VIEWS_ANSWER)
            }

            val result = client.views(access(), productTypeId = 812)

            assertEquals(
                SpodViews(
                    views =
                        listOf(
                            SpodView(
                                id = "1",
                                name = "FRONT",
                                hotspots = listOf(SpodHotspotName("MEDIUM_FRONT")),
                                images =
                                    listOf(
                                        SpodViewImage(
                                            appearanceId = "2",
                                            image = "https://image.cdn.example/front.png",
                                        )
                                    ),
                            )
                        )
                ),
                assertIs<SpodResult.Answered<SpodViews>>(result).value,
            )
            assertEquals("https://rest.spod-staging.com/productTypes/812/views", url)
            assertEquals("GET", method)
            assertEquals(ACCESS_TOKEN, token)
            assertEquals(30_000L, timeoutMillis)
        }

    @Test
    fun `a preview request posts exactly the configurations, the colour, and the size`() =
        runBlocking {
            var url = ""
            var method = ""
            var token = ""
            var timeoutMillis: Long? = null
            var contentType = ""
            var body = ""
            val client = spodClient { request ->
                url = request.url.toString()
                method = request.method.value
                token = request.headers[SpodClient.ACCESS_TOKEN_HEADER].orEmpty()
                timeoutMillis =
                    request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis
                contentType = request.body.contentType.toString()
                body = request.body.toByteArray().decodeToString()
                respondJson(PREVIEWS_ANSWER)
            }

            val result = client.previews(access(), productTypeId = 812, previewRequest())

            assertEquals(
                SpodPreviews(
                    images =
                        listOf(
                            SpodPreviewImage(
                                url = "https://image.cdn.example/preview.webp",
                                viewId = "1",
                                viewName = "FRONT",
                            )
                        )
                ),
                assertIs<SpodResult.Answered<SpodPreviews>>(result).value,
            )
            assertEquals("https://rest.spod-staging.com/productTypes/812/previews", url)
            assertEquals("POST", method)
            assertEquals(ACCESS_TOKEN, token)
            assertEquals(30_000L, timeoutMillis)
            assertContains(contentType, "application/json")
            assertEquals(
                Json.parseToJsonElement(
                    """{"configurations":[{"designId":"design-1","hotspot":"MEDIUM_FRONT"}],""" +
                        """"appearanceId":"2","width":1000,"height":1000}"""
                ),
                Json.parseToJsonElement(body),
            )
        }

    @Test
    fun `a views or previews answer without its array reads as empty`() = runBlocking {
        val client = spodClient { respondJson("""{"unknownField":"ignored"}""") }

        val views = client.views(access(), productTypeId = 812)
        val previews = client.previews(access(), productTypeId = 812, previewRequest())

        assertEquals(SpodViews(), assertIs<SpodResult.Answered<SpodViews>>(views).value)
        assertEquals(SpodPreviews(), assertIs<SpodResult.Answered<SpodPreviews>>(previews).value)
    }

    @Test
    fun `the calibration calls judge a refusal like every other call`() = runBlocking {
        val expected =
            mapOf(
                HttpStatusCode.TooManyRequests to SpodResult.Failed(SpodError.RATE_LIMITED, false),
                HttpStatusCode.BadGateway to
                    SpodResult.Failed(SpodError.PROVIDER_UNAVAILABLE, true),
                HttpStatusCode.BadRequest to SpodResult.Failed(SpodError.REFUSED, false),
            )

        for ((status, failure) in expected) {
            val client = spodClient { respondError(status, PROVIDER_BODY) }
            assertEquals(failure, client.views(access(), productTypeId = 812), "views, $status")
            assertEquals(
                failure,
                client.previews(access(), productTypeId = 812, previewRequest()),
                "previews, $status",
            )
        }
    }

    @Test
    fun `the calibration calls are paced like every other API call`() = runBlocking {
        val waits = mutableListOf<Long>()
        var now = 1_000L
        val client =
            SpodClient(
                engine = MockEngine { respondJson(VIEWS_ANSWER) },
                nowMillis = { now },
                pause = { millis ->
                    waits += millis
                    now += millis
                },
            )

        client.views(access(), productTypeId = 812)
        client.previews(access(), productTypeId = 812, previewRequest())

        assertEquals(listOf(SpodClient.MIN_REQUEST_INTERVAL_MILLIS), waits)
    }

    private fun previewRequest(): SpodPreviewRequest =
        SpodPreviewRequest(
            configurations =
                listOf(SpodPreviewConfiguration(designId = "design-1", hotspot = "MEDIUM_FRONT")),
            appearanceId = "2",
            width = 1000,
            height = 1000,
        )

    private companion object {
        /** One view with every field this module reads, plus one it does not. */
        val VIEWS_ANSWER =
            """
            {"views":[{"id":"1","name":"FRONT","unknownField":"ignored",
              "hotspots":[{"name":"MEDIUM_FRONT"}],
              "images":[{"appearanceId":"2","image":"https://image.cdn.example/front.png"}]}]}
            """
                .trimIndent()

        const val PREVIEWS_ANSWER =
            """{"images":[{"url":"https://image.cdn.example/preview.webp",""" +
                """"viewId":"1","viewName":"FRONT","unknownField":"ignored"}]}"""
    }
}
