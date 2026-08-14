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
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import shop.voenix.validation.Validatable

public fun Application.installHttpRuntime() {
    install(ContentNegotiation) {
        json(httpJson, contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8))
    }
    install(RequestBodyLimit) { bodyLimit { MAX_REQUEST_BODY_BYTES } }
    installErrorResponses()
}

/**
 * How many bytes of request body the application accepts at all, on every route, before any route
 * handler sees a single one of them.
 *
 * This is the outer transfer bound, not a feature limit: a module that reads an upload still bounds
 * what it *processes* (the Generator's 10 MiB per image and 20 MiB of file parts, the Image
 * module's 10 MiB), and those limits are the ones a client normally meets. This one exists for the
 * body that never reaches them — a request whose sheer size is the attack. It is deliberately the
 * 30,000,000 bytes the legacy application's Kestrel refused at, so the migrated backend accepts
 * exactly what the old one accepted.
 */
internal const val MAX_REQUEST_BODY_BYTES: Long = 30_000_000

/** The one place an exception becomes an HTTP status and an [ApiError] body. */
private fun Application.installErrorResponses() {
    install(StatusPages) {
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ApiError(message = "Request body too large"),
            )
        }
        exception<RequestValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiError(
                    message = "Validation failed",
                    errors = (cause.value as? Validatable)?.validate().orEmpty(),
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

private val httpJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
}
