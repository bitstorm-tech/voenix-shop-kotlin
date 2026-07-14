package shop.voenix.supplier

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
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

internal object SupplierRoutes {
    fun install(
        application: Application,
        suppliers: SupplierOperations,
    ) {
        application.routing {
            authenticate(ApplicationAuth.PROVIDER) {
                route("/api/admin/suppliers") {
                    install(AdminRouteProtection)

                    get { call.respondResult(suppliers.list()) }

                    post {
                        val input = call.receive<SupplierInput>()
                        when (val result = suppliers.create(input)) {
                            is SupplierResult.Success -> {
                                call.response.header(
                                    HttpHeaders.Location,
                                    "/api/admin/suppliers/${result.value.id}",
                                )
                                call.respond(HttpStatusCode.Created, result.value)
                            }

                            else -> call.respondFailure(result)
                        }
                    }

                    route("/{id}") {
                        get {
                            val id = call.supplierIdOrRespond() ?: return@get
                            call.respondResult(suppliers.get(id))
                        }

                        put {
                            val id = call.supplierIdOrRespond() ?: return@put
                            call.respondResult(suppliers.update(id, call.receive<SupplierInput>()))
                        }

                        delete {
                            val id = call.supplierIdOrRespond() ?: return@delete
                            when (val result = suppliers.delete(id)) {
                                is SupplierResult.Success ->
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
    result: SupplierResult<T>
) {
    when (result) {
        is SupplierResult.Success -> respond(result.value)
        else -> respondFailure(result)
    }
}

private suspend fun ApplicationCall.respondFailure(result: SupplierResult<*>) {
    when (result) {
        SupplierResult.NotFound -> respond(HttpStatusCode.NotFound, ApiError("Supplier not found"))
        SupplierResult.CountryNotFound ->
            respond(HttpStatusCode.BadRequest, ApiError("Supplier country not found"))
        SupplierResult.InUse ->
            respond(
                HttpStatusCode.Conflict,
                ApiError("Supplier is referenced by articles and cannot be deleted"),
            )
        is SupplierResult.Invalid ->
            respond(HttpStatusCode.BadRequest, ApiError("Validation failed", result.errors))
        SupplierResult.DatabaseError ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
        is SupplierResult.Success -> error("A success result cannot be handled as a failure")
    }
}

private suspend fun ApplicationCall.supplierIdOrRespond(): Long? {
    val id = parameters["id"]?.toLongOrNull()
    if (id == null) {
        respond(HttpStatusCode.BadRequest, ApiError("Invalid supplier id"))
    }
    return id
}
