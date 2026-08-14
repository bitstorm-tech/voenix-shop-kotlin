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
import shop.voenix.operation.OperationResult
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

internal object CountryRoutes {
    fun install(
        application: Application,
        countries: CountryOperations,
    ) {
        application.routing {
            get("/api/countries") { call.respondResult(countries.listPublic()) }

            authenticate(AuthRouting.PROVIDER) {
                route("/api/admin/countries") {
                    installAdminRouteProtection()

                    get { call.respondResult(countries.listAdmin()) }

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
                                call.respondFailure(result)
                            }
                        }
                    }

                    route("/{id}") {
                        get {
                            val id = call.countryIdOrRespond() ?: return@get
                            call.respondResult(countries.get(id))
                        }

                        put {
                            val id = call.countryIdOrRespond() ?: return@put
                            val input = call.receive<CountryInput>()
                            call.respondResult(countries.update(id, input))
                        }

                        delete {
                            val id = call.countryIdOrRespond() ?: return@delete
                            when (val result = countries.delete(id)) {
                                is OperationResult.Success ->
                                    call.response.status(HttpStatusCode.NoContent)
                                else -> call.respondFailure(result)
                            }
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
    override fun validate(): ValidationErrors = buildMap {
        if (name.isNullOrBlank()) {
            put("name", listOf("Name is required"))
        } else if (name.trim().length > MAXIMUM_COUNTRY_NAME_LENGTH) {
            put("name", listOf("Name must be at most 255 characters"))
        }

        val trimmedCode = countryCode?.trim()
        if (countryCode.isNullOrBlank()) {
            put("countryCode", listOf("Country code is required"))
        } else if (trimmedCode?.length != COUNTRY_CODE_LENGTH) {
            put("countryCode", listOf("Country code must be exactly 2 characters"))
        } else if (
            !trimmedCode.all { character -> character in 'A'..'Z' || character in 'a'..'z' }
        ) {
            put("countryCode", listOf("Country code must contain only letters"))
        }
    }

    companion object {
        private const val MAXIMUM_COUNTRY_NAME_LENGTH = 255
        private const val COUNTRY_CODE_LENGTH = 2
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.respondResult(
    result: OperationResult<T>
) {
    when (result) {
        is OperationResult.Success -> respond(result.value)
        else -> respondFailure(result)
    }
}

private suspend fun ApplicationCall.respondFailure(result: OperationResult<*>) {
    when (result) {
        OperationResult.NotFound -> {
            respond(HttpStatusCode.NotFound, ApiError("Country not found"))
        }

        OperationResult.Conflict -> {
            respond(HttpStatusCode.Conflict, ApiError("Country name or code already exists"))
        }

        is OperationResult.Invalid -> {
            respond(
                HttpStatusCode.BadRequest,
                ApiError("Validation failed", result.errors),
            )
        }

        OperationResult.UnexpectedFailure -> {
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
        }

        is OperationResult.Success -> {
            error("A success result cannot be handled as a failure")
        }
    }
}

private suspend fun ApplicationCall.countryIdOrRespond(): Long? {
    val id = parameters["id"]?.toLongOrNull()
    if (id == null) {
        respond(HttpStatusCode.BadRequest, ApiError("Invalid country id"))
    }
    return id
}
