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
import shop.voenix.auth.ApplicationAuth
import shop.voenix.auth.installAdminRouteProtection
import shop.voenix.http.ApiError
import shop.voenix.operation.OperationResult

internal object CountryRoutes {
    fun install(
        application: Application,
        countries: CountryOperations,
    ) {
        application.routing {
            get("/api/countries") { call.respondResult(countries.listPublic()) }

            authenticate(ApplicationAuth.PROVIDER) {
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
