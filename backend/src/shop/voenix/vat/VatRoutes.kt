package shop.voenix.vat

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
import shop.voenix.auth.AdminRouteProtection
import shop.voenix.auth.ApplicationAuth
import shop.voenix.http.ApiError

internal object VatRoutes {
    fun install(
        application: Application,
        vats: VatOperations,
    ) {
        application.routing {
            authenticate(ApplicationAuth.PROVIDER) {
                route("/api/admin/vat") {
                    install(AdminRouteProtection)

                    get { call.respondResult(vats.list()) }

                    post {
                        val input = call.receive<VatInput>()
                        when (val result = vats.create(input)) {
                            is VatResult.Success -> {
                                call.response.header(
                                    HttpHeaders.Location,
                                    "/api/admin/vat/${result.value.id}",
                                )
                                call.respond(HttpStatusCode.Created, result.value)
                            }

                            else -> call.respondFailure(result)
                        }
                    }

                    route("/{id}") {
                        get {
                            val id = call.vatIdOrRespond() ?: return@get
                            call.respondResult(vats.get(id))
                        }

                        put {
                            val id = call.vatIdOrRespond() ?: return@put
                            call.respondResult(vats.update(id, call.receive<VatInput>()))
                        }

                        delete {
                            val id = call.vatIdOrRespond() ?: return@delete
                            when (val result = vats.delete(id)) {
                                is VatResult.Success ->
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

private suspend inline fun <reified T : Any> ApplicationCall.respondResult(result: VatResult<T>) {
    when (result) {
        is VatResult.Success -> respond(result.value)
        else -> respondFailure(result)
    }
}

private suspend fun ApplicationCall.respondFailure(result: VatResult<*>) {
    when (result) {
        VatResult.NotFound -> respond(HttpStatusCode.NotFound, ApiError("VAT not found"))
        VatResult.Conflict -> respond(HttpStatusCode.Conflict, ApiError("VAT entry already exists"))
        is VatResult.Invalid ->
            respond(HttpStatusCode.BadRequest, ApiError("Validation failed", result.errors))
        VatResult.DatabaseError ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
        is VatResult.Success -> error("A success result cannot be handled as a failure")
    }
}

private suspend fun ApplicationCall.vatIdOrRespond(): Long? {
    val id = parameters["id"]?.toLongOrNull()
    if (id == null) {
        respond(HttpStatusCode.BadRequest, ApiError("Invalid VAT id"))
    }
    return id
}
