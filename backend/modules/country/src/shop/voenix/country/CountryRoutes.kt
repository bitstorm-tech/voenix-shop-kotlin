package shop.voenix.country

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.installAdminRouteProtection
import shop.voenix.http.ApiError
import shop.voenix.http.ConflictHandling
import shop.voenix.http.OperationResultHttpMapping
import shop.voenix.http.longPathParameterOrRespond
import shop.voenix.http.respondFailure
import shop.voenix.http.respondResult
import shop.voenix.operation.OperationResult
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors
import shop.voenix.validation.buildValidationErrors

internal fun Application.installCountryRoutes(countries: CountryOperations) {
    routing {
        get("/api/countries") { call.respondResult(countries.listPublic(), COUNTRY_RESPONSES) }

        authenticate(AuthRouting.PROVIDER) {
            route("/api/admin/countries") {
                installAdminRouteProtection()

                get { call.respondResult(countries.listAdmin(), COUNTRY_RESPONSES) }

                post {
                    val input = call.receive<CountryInput>()
                    when (val result = countries.create(input)) {
                        is OperationResult.Success -> {
                            call.response.header(
                                HttpHeaders.Location,
                                "/api/admin/countries/${result.value.id}",
                            )
                            call.respond(HttpStatusCode.Created, result.value)
                        }

                        else -> {
                            call.respondFailure(result, COUNTRY_RESPONSES)
                        }
                    }
                }

                route("/{id}") {
                    get {
                        val id = call.countryIdOrRespond() ?: return@get
                        call.respondResult(countries.get(id), COUNTRY_RESPONSES)
                    }

                    put {
                        val id = call.countryIdOrRespond() ?: return@put
                        val input = call.receive<CountryInput>()
                        call.respondResult(countries.update(id, input), COUNTRY_RESPONSES)
                    }

                    delete {
                        val id = call.countryIdOrRespond() ?: return@delete
                        when (val result = countries.delete(id)) {
                            is OperationResult.Success ->
                                call.response.status(HttpStatusCode.NoContent)
                            else -> call.respondFailure(result, COUNTRY_RESPONSES)
                        }
                    }
                }
            }
        }
    }
}

@Serializable
internal data class CountryInput(
    val name: String? = null,
    val countryCode: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildValidationErrors {
        if (name.isNullOrBlank()) {
            add("name", "Name is required")
        } else if (name.trim().length > MAXIMUM_COUNTRY_NAME_LENGTH) {
            add("name", "Name must be at most 255 characters")
        }

        val trimmedCode = countryCode?.trim()
        if (countryCode.isNullOrBlank()) {
            add("countryCode", "Country code is required")
        } else if (trimmedCode?.length != COUNTRY_CODE_LENGTH) {
            add("countryCode", "Country code must be exactly 2 characters")
        } else if (
            !trimmedCode.all { character -> character in 'A'..'Z' || character in 'a'..'z' }
        ) {
            add("countryCode", "Country code must contain only letters")
        }
    }

    companion object {
        private const val MAXIMUM_COUNTRY_NAME_LENGTH = 255
        private const val COUNTRY_CODE_LENGTH = 2
    }
}

private val COUNTRY_RESPONSES =
    OperationResultHttpMapping(
        notFound = ApiError("Country not found"),
        conflict = ConflictHandling.Respond(ApiError("Country name or code already exists")),
    )

private suspend fun ApplicationCall.countryIdOrRespond(): Long? =
    longPathParameterOrRespond("id", HttpStatusCode.BadRequest, ApiError("Invalid country id"))
