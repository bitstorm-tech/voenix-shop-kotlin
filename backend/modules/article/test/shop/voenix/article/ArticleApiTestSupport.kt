package shop.voenix.article

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import shop.voenix.http.ApiError
import shop.voenix.spod.SpodClient

/** The CSRF token the antiforgery route hands a signed-in client for its next write. */
internal suspend fun antiforgeryToken(client: HttpClient): String =
    Json.parseToJsonElement(client.get("/api/antiforgery/token").bodyAsText())
        .jsonObject
        .getValue("requestToken")
        .jsonPrimitive
        .content

/**
 * Asserts that [response] is exactly the error body [status], [message], and [errors] describe —
 * the whole JSON object, so a field the route added would fail the assertion too.
 */
internal suspend fun assertApiError(
    response: HttpResponse,
    status: HttpStatusCode,
    message: String,
    errors: Map<String, List<String>> = emptyMap(),
) {
    assertEquals(status, response.status)
    assertEquals(
        apiErrorJson.encodeToJsonElement(ApiError(message, errors)).jsonObject,
        Json.parseToJsonElement(response.bodyAsText()).jsonObject,
    )
}

private val apiErrorJson = Json { encodeDefaults = true }

/**
 * The Spreadconnect client of every test that does not sync anything.
 *
 * `installArticleModule` needs one because the t-shirt sync is part of the module, and a test of a
 * route that never triggers a sync should not have to invent a catalog. The engine refuses every
 * request, so a test that reaches the partner by accident fails instead of hanging.
 */
internal fun unreachableSpodClient(): SpodClient =
    SpodClient(engine = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })
