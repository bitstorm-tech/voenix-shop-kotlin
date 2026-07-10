package shop.voenix.country

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentType
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.Charset
import java.util.UUID

object CountryRoutes {
    fun install(
        application: Application,
        countries: CountryOperations,
    ) {
        application.install(ContentNegotiation) {
            json(
                json,
                contentType = ContentType.Application.Json.withCharset(Charsets.UTF_8),
            )
        }

        application.routing {
            caseInsensitiveRoute("/api/countries") {
                get {
                    call.respondValue(countries.listPublic())
                }
            }

            authenticate(CountryAuth.PROVIDER) {
                caseInsensitiveRoute("/api/admin/countries") {
                    get {
                        if (!CountryAuth.requireAdmin(call)) return@get
                        call.respondValue(countries.listAdmin())
                    }

                    post {
                        if (!CountryAuth.requireAdmin(call)) return@post
                        if (!call.requireCsrfToken()) return@post
                        val fields = call.receiveValidatedFields(CREATE_REQUEST_TYPE) ?: return@post
                        val request = CreateAdminCountryRequest(fields.name, fields.countryCode)
                        when (val result = countries.create(request)) {
                            is CountryResult.Success -> {
                                call.response.header(
                                    HttpHeaders.Location,
                                    call.countryLocation(result.value.id),
                                )
                                call.respond(HttpStatusCode.Created, result.value)
                            }

                            else -> {
                                call.respondFailure(result)
                            }
                        }
                    }

                    longPathSegment("id") {
                        get {
                            if (!CountryAuth.requireAdmin(call)) return@get
                            call.respondValue(countries.get(call.countryId()))
                        }

                        put {
                            if (!CountryAuth.requireAdmin(call)) return@put
                            if (!call.requireCsrfToken()) return@put
                            val fields = call.receiveValidatedFields(UPDATE_REQUEST_TYPE) ?: return@put
                            call.respondValue(
                                countries.update(
                                    call.countryId(),
                                    UpdateAdminCountryRequest(fields.name, fields.countryCode),
                                ),
                            )
                        }

                        delete {
                            if (!CountryAuth.requireAdmin(call)) return@delete
                            if (!call.requireCsrfToken()) return@delete
                            when (val result = countries.delete(call.countryId())) {
                                is CountryResult.Success -> {
                                    call.response.status(HttpStatusCode.NoContent)
                                }

                                else -> {
                                    call.respondFailure(result)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend inline fun <reified T : Any> ApplicationCall.respondValue(result: CountryResult<T>) {
        when (result) {
            is CountryResult.Success -> {
                respond(result.value)
            }

            else -> {
                respondFailure(result)
            }
        }
    }

    private suspend fun ApplicationCall.respondFailure(result: CountryResult<*>) {
        when (result) {
            CountryResult.NotFound -> {
                respond(HttpStatusCode.NotFound, ProblemDetails(404, "Country not found"))
            }

            CountryResult.NameConflict -> {
                respond(HttpStatusCode.Conflict, ProblemDetails(409, "Country name already exists"))
            }

            CountryResult.CodeConflict -> {
                respond(HttpStatusCode.Conflict, ProblemDetails(409, "Country code already exists"))
            }

            CountryResult.DatabaseError,
            is CountryResult.Invalid,
            -> {
                respond(
                    HttpStatusCode.InternalServerError,
                    ProblemDetails(500, "Internal server error"),
                )
            }

            is CountryResult.Success -> {
                error("A success result cannot be handled as a failure")
            }
        }
    }

    private suspend fun ApplicationCall.receiveValidatedFields(requestTypeName: String): CountryRequestFields? {
        val contentType = runCatching { request.contentType() }.getOrNull()
        val isJsonMediaType =
            contentType?.match(ContentType.Application.Json) == true ||
                contentType?.match(TEXT_JSON) == true ||
                (
                    contentType?.contentType == "application" &&
                        contentType.contentSubtype.endsWith("+json", ignoreCase = true)
                )
        val isJson = isJsonMediaType && contentType.hasSupportedJsonCharset()
        if (!isJson) {
            respondHttpProblem(
                status = HttpStatusCode.UnsupportedMediaType,
                section = "15.5.16",
                title = "Unsupported Media Type",
            )
            return null
        }

        val fields =
            try {
                receiveCountryFields(requestTypeName)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: CountryRequestException) {
                respondValidationProblem(exception.errors)
                return null
            } catch (exception: Exception) {
                respondRequestParsingProblem("$", "The JSON payload is invalid. Path: $.")
                return null
            }

        val errors = countryValidationErrors(fields.name, fields.countryCode)
        if (errors.isNotEmpty()) {
            respondValidationProblem(errors)
            return null
        }
        return fields
    }

    private suspend fun ApplicationCall.respondRequestParsingProblem(
        path: String,
        detail: String,
    ) {
        respondValidationProblem(
            linkedMapOf(
                "request" to listOf("The request field is required."),
                path to listOf(detail),
            ),
        )
    }

    private suspend fun ApplicationCall.respondValidationProblem(errors: Map<String, List<String>>) {
        val problem =
            ValidationProblemDetails(
                type = "https://tools.ietf.org/html/rfc9110#section-15.5.1",
                title = "One or more validation errors occurred.",
                status = 400,
                errors = errors,
                traceId = newTraceId(request.headers["traceparent"]),
            )
        respondText(
            text = json.encodeToString(problem),
            contentType = ContentType.Application.ProblemJson.withCharset(Charsets.UTF_8),
            status = HttpStatusCode.BadRequest,
        )
    }

    private suspend fun ApplicationCall.requireCsrfToken(): Boolean {
        if (CountryAuth.hasValidCsrfToken(this)) return true
        val problem =
            HttpProblemDetails(
                type = "https://tools.ietf.org/html/rfc9110#section-15.5.1",
                title = "Bad Request",
                status = 400,
                traceId = newTraceId(request.headers["traceparent"]),
            )
        respondText(
            text = json.encodeToString(problem),
            contentType = ContentType.Application.ProblemJson.withCharset(Charsets.UTF_8),
            status = HttpStatusCode.BadRequest,
        )
        return false
    }

    private suspend fun ApplicationCall.respondHttpProblem(
        status: HttpStatusCode,
        section: String,
        title: String,
    ) {
        val problem =
            HttpProblemDetails(
                type = "https://tools.ietf.org/html/rfc9110#section-$section",
                title = title,
                status = status.value,
                traceId = newTraceId(request.headers["traceparent"]),
            )
        respondText(
            text = json.encodeToString(problem),
            contentType = ContentType.Application.ProblemJson.withCharset(Charsets.UTF_8),
            status = status,
        )
    }

    private fun ApplicationCall.countryLocation(id: Long): String {
        val origin = request.origin
        val defaultPort =
            (origin.scheme == "http" && origin.serverPort == 80) ||
                (origin.scheme == "https" && origin.serverPort == 443)
        val port = if (defaultPort) "" else ":${origin.serverPort}"
        return "${origin.scheme}://${origin.serverHost}$port/api/admin/countries/$id"
    }

    private fun ApplicationCall.countryId(): Long = checkNotNull(parameters["id"]).toLong()

    private fun ContentType.hasSupportedJsonCharset(): Boolean {
        val charsetName =
            parameters
                .firstOrNull { parameter -> parameter.name.equals("charset", ignoreCase = true) }
                ?.value
                ?: return true
        val canonicalName = runCatching { Charset.forName(charsetName).name() }.getOrNull()
        return canonicalName in SUPPORTED_JSON_CHARSETS
    }

    private fun newTraceId(traceparent: String?): String {
        val parent =
            traceparent
                ?.let(TRACEPARENT_PATTERN::matchEntire)
                ?.takeIf { match ->
                    match.groupValues[1].any { character -> character != '0' } &&
                        match.groupValues[2].any { character -> character != '0' }
                }
        val trace =
            parent
                ?.groupValues
                ?.get(1)
                ?: UUID.randomUUID().toString().replace("-", "")
        val span =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(16)
        val flags = parent?.groupValues?.get(3) ?: "00"
        return "00-$trace-$span-$flags"
    }

    private val json =
        Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = true
        }
    private val TRACEPARENT_PATTERN = Regex("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")
    private val TEXT_JSON = ContentType("text", "json")
    private val SUPPORTED_JSON_CHARSETS = setOf("UTF-8", "UTF-16", "UTF-16LE")
    private const val CREATE_REQUEST_TYPE =
        "Voenix.Features.Country.Dtos.CreateAdminCountryRequest"
    private const val UPDATE_REQUEST_TYPE =
        "Voenix.Features.Country.Dtos.UpdateAdminCountryRequest"
}
