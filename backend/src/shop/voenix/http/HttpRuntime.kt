package shop.voenix.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

object HttpRuntime {
    fun install(application: Application) {
        application.install(ContentNegotiation) {
            json(
                json,
                contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8),
            )
        }
        application.install(StatusPages) {
            exception<RequestValidationException> { call, cause ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(
                        message = "Validation failed",
                        errors =
                            (cause.value as? RequestValidationInput)?.validationErrors().orEmpty(),
                    ),
                )
            }
            exception<UnsupportedMediaTypeException> { call, _ ->
                call.respond(
                    HttpStatusCode.UnsupportedMediaType,
                    ApiError(message = "Unsupported media type"),
                )
            }
            exception<CannotTransformContentToTypeException> { call, _ ->
                call.respond(
                    HttpStatusCode.UnsupportedMediaType,
                    ApiError(message = "Unsupported media type"),
                )
            }
            exception<ContentTransformationException> { call, _ ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(message = "Invalid request body"),
                )
            }
            exception<BadRequestException> { call, _ ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError(message = "Invalid request body"),
                )
            }
            exception<Throwable> { call, cause ->
                generateSequence(cause as Throwable?) { throwable -> throwable.cause }
                    .filterIsInstance<CancellationException>()
                    .firstOrNull()
                    ?.let { cancellation -> throw cancellation }
                call.application.log.error("Unhandled server error", cause)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiError(message = "Internal server error"),
                )
            }
        }
    }

    internal val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }
}
