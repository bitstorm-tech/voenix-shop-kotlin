package shop.voenix.http

import io.ktor.http.ContentType
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.IgnoreTrailingSlash
import kotlinx.serialization.json.Json

object HttpRuntime {
    fun install(application: Application) {
        application.install(IgnoreTrailingSlash)
        application.install(ContentNegotiation) {
            json(
                json,
                contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8),
            )
        }
    }

    internal val json =
        Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = true
        }
}
