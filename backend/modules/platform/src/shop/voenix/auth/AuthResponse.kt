package shop.voenix.auth

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class AuthResponse(
    val success: Boolean,
    val message: String,
    val code: String?,
)

/** The one shape every authentication and authorization refusal of this module answers with. */
internal suspend fun ApplicationCall.respondAuth(
    status: HttpStatusCode,
    message: String,
) {
    respondBytes(
        bytes = authResponseJson.encodeToString(AuthResponse(false, message, null)).toByteArray(),
        contentType = ContentType.Application.Json,
        status = status,
    )
}

private val authResponseJson = Json { encodeDefaults = true }
