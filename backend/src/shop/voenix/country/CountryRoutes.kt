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
import shop.voenix.http.ApiError

internal object CountryRoutes {
    fun install(
        application: Application,
        countries: CountryOperations,
    ) {
        application.routing {
            get("/api/countries") {
                call.respondResult(countries.listPublic())
            }

            authenticate(ApplicationAuth.PROVIDER) {
                route("/api/admin/countries") {
                    get {
                        if (!ApplicationAuth.requireAdmin(call)) return@get
                        call.respondResult(countries.listAdmin())
                    }

                    post {
                        if (!ApplicationAuth.requireAdmin(call)) return@post
                        if (!ApplicationAuth.requireCsrf(call)) return@post
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
                            if (!ApplicationAuth.requireAdmin(call)) return@get
                            val id = call.countryIdOrRespond() ?: return@get
                            call.respondResult(countries.get(id))
                        }

                        put {
                            if (!ApplicationAuth.requireAdmin(call)) return@put
                            if (!ApplicationAuth.requireCsrf(call)) return@put
                            val id = call.countryIdOrRespond() ?: return@put
                            val input = call.receive<CountryInput>()
                            call.respondResult(countries.update(id, input))
                        }

                        delete {
                            if (!ApplicationAuth.requireAdmin(call)) return@delete
                            if (!ApplicationAuth.requireCsrf(call)) return@delete
                            val id = call.countryIdOrRespond() ?: return@delete
                            when (val result = countries.delete(id)) {
                                is CountryResult.Success -> call.response.status(HttpStatusCode.NoContent)
                                else -> call.respondFailure(result)
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.respondResult(result: CountryResult<T>) {
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
