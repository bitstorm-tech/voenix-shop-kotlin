package shop.voenix.country

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.plugins.requestvalidation.ValidationResult
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import shop.voenix.auth.AdminRouteProtection
import shop.voenix.auth.ApplicationAuth
import shop.voenix.http.ApiError

internal object CountryRoutes {
    fun install(
        application: Application,
        countries: CountryOperations,
    ) {
        application.install(RequestValidation) {
            validate<CountryInput> { input ->
                val errors = CountryInputValidator.validate(input)
                if (errors.isEmpty()) {
                    ValidationResult.Valid
                } else {
                    ValidationResult.Invalid(errors.values.flatten())
                }
            }
        }
        application.routing {
            get("/api/countries") { call.respondResult(countries.listPublic()) }

            authenticate(ApplicationAuth.PROVIDER) {
                route("/api/admin/countries") {
                    install(AdminRouteProtection)

                    get { call.respondResult(countries.listAdmin()) }

                    post {
                        val input = call.receive<CountryInput>()
                        when (val result = countries.create(input)) {
                            is CountryResult.Success -> {
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
                                is CountryResult.Success ->
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

private suspend inline fun <reified T : Any> ApplicationCall.respondResult(
    result: CountryResult<T>
) {
    when (result) {
        is CountryResult.Success -> respond(result.value)
        else -> respondFailure(result)
    }
}

private suspend fun ApplicationCall.respondFailure(result: CountryResult<*>) {
    when (result) {
        CountryResult.NotFound -> {
            respond(HttpStatusCode.NotFound, ApiError("Country not found"))
        }

        CountryResult.NameConflict -> {
            respond(HttpStatusCode.Conflict, ApiError("Country name already exists"))
        }

        CountryResult.CodeConflict -> {
            respond(HttpStatusCode.Conflict, ApiError("Country code already exists"))
        }

        is CountryResult.Invalid -> {
            respond(
                HttpStatusCode.BadRequest,
                ApiError("Validation failed", result.errors),
            )
        }

        CountryResult.DatabaseError -> {
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
        }

        is CountryResult.Success -> {
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
