package shop.voenix.supplier

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
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.installAdminRouteProtection
import shop.voenix.http.ApiError
import shop.voenix.http.ConflictHandling
import shop.voenix.http.OperationResultHttpMapping
import shop.voenix.http.longPathParameterOrRespond
import shop.voenix.http.respondFailure
import shop.voenix.http.respondResult
import shop.voenix.operation.OperationResult

internal fun Application.installSupplierRoutes(suppliers: SupplierOperations) {
    routing {
        authenticate(AuthRouting.PROVIDER) {
            route("/api/admin/suppliers") {
                installAdminRouteProtection()

                get { call.respondResult(suppliers.list(), SUPPLIER_RESPONSES) }

                post {
                    val input = call.receive<SupplierInput>()
                    when (val result = suppliers.create(input)) {
                        is OperationResult.Success -> {
                            call.response.header(
                                HttpHeaders.Location,
                                "/api/admin/suppliers/${result.value.id}",
                            )
                            call.respond(HttpStatusCode.Created, result.value)
                        }

                        else -> call.respondFailure(result, SUPPLIER_RESPONSES)
                    }
                }

                route("/{id}") {
                    get {
                        val id = call.supplierIdOrRespond() ?: return@get
                        call.respondResult(suppliers.get(id), SUPPLIER_RESPONSES)
                    }

                    put {
                        val id = call.supplierIdOrRespond() ?: return@put
                        call.respondResult(
                            suppliers.update(id, call.receive<SupplierInput>()),
                            SUPPLIER_RESPONSES,
                        )
                    }

                    delete {
                        val id = call.supplierIdOrRespond() ?: return@delete
                        when (val result = suppliers.delete(id)) {
                            is OperationResult.Success ->
                                call.response.status(HttpStatusCode.NoContent)
                            else -> call.respondFailure(result, SUPPLIER_RESPONSES)
                        }
                    }
                }
            }
        }
    }
}

private val SUPPLIER_RESPONSES =
    OperationResultHttpMapping(
        notFound = ApiError("Supplier not found"),
        conflict = ConflictHandling.Respond(ApiError("Supplier is in use and cannot be deleted")),
    )

private suspend fun ApplicationCall.supplierIdOrRespond(): Long? =
    longPathParameterOrRespond("id", HttpStatusCode.BadRequest, ApiError("Invalid supplier id"))
