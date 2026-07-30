package shop.voenix.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class ApiErrorTest {
    @Test
    fun `an error without a code serializes without the field`() {
        assertEquals(
            """{"message":"Invalid CSRF token","errors":{}}""",
            json.encodeToString(ApiError(message = "Invalid CSRF token")),
        )
    }

    @Test
    fun `an explicit null code stays omitted`() {
        assertEquals(
            """{"message":"Validation failed","errors":{"name":["is required"]}}""",
            json.encodeToString(
                ApiError(
                    message = "Validation failed",
                    errors = mapOf("name" to listOf("is required")),
                    code = null,
                )
            ),
        )
    }

    @Test
    fun `a code is serialized when present`() {
        assertEquals(
            """{"message":"Promotion code expired","errors":{},"code":"EXPIRED"}""",
            json.encodeToString(ApiError(message = "Promotion code expired", code = "EXPIRED")),
        )
    }

    private companion object {
        // Mirrors the runtime configuration installed by installHttpRuntime.
        val json = Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = true
        }
    }
}
