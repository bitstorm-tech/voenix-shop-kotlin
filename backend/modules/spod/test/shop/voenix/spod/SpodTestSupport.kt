package shop.voenix.spod

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf

/** The destination's token; no log line may ever contain it. */
internal const val ACCESS_TOKEN = "spod-access-token-must-never-be-logged"

/** A marker no log line may ever contain: it stands in for whatever the partner writes. */
internal const val PROVIDER_BODY = "PROVIDER-SECRET-ECHO"

/** A client on a [MockEngine] whose pacer never waits, so a test sees only its own requests. */
internal fun spodClient(
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
): SpodClient = SpodClient(engine = MockEngine(handler), nowMillis = { 0 }, pause = {})

internal fun MockRequestHandleScope.respondJson(body: String): HttpResponseData =
    respond(
        content = body,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

/** The staging environment, which is also what pins the base URL a deployment would use. */
internal fun access(): SpodAccess =
    SpodAccess(
        destinationId = 5,
        environment = SpodEnvironment.STAGING,
        accessToken = ACCESS_TOKEN,
        timeoutSeconds = 30,
    )
