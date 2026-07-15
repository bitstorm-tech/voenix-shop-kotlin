package shop.voenix.pricing

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.installAdminRouteProtection
import shop.voenix.http.ApiError
import shop.voenix.operation.OperationResult

internal object PriceRoutes {
    fun install(
        application: Application,
        prices: PriceOperations,
    ) {
        application.routing {
            authenticate(AuthRouting.PROVIDER) {
                route("/api/admin/prices") {
                    installAdminRouteProtection()

                    post {
                        when (val result = prices.create(call.receive<PriceInput>())) {
                            is OperationResult.Success -> {
                                call.response.header(
                                    HttpHeaders.Location,
                                    "/api/admin/prices/${result.value.id}",
                                )
                                call.respond(HttpStatusCode.Created, result.value)
                            }
                            else -> call.respondFailure(result)
                        }
                    }

                    post("/calculate") {
                        call.respondResult(prices.calculate(call.receive<PriceInput>()))
                    }

                    get("/default") {
                        when (val result = prices.default()) {
                            is OperationResult.Invalid ->
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    ApiError("No VAT is configured", result.errors),
                                )
                            else -> call.respondResult(result)
                        }
                    }

                    route("/{id}") {
                        get {
                            val id = call.priceIdOrRespond() ?: return@get
                            call.respondResult(prices.get(id))
                        }

                        put {
                            val id = call.priceIdOrRespond() ?: return@put
                            call.respondResult(prices.update(id, call.receive<PriceInput>()))
                        }
                    }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.respondResult(result: OperationResult<CalculatedPrice>) {
    when (result) {
        is OperationResult.Success -> respond(result.value)
        else -> respondFailure(result)
    }
}

private suspend fun ApplicationCall.respondFailure(result: OperationResult<*>) {
    when (result) {
        OperationResult.NotFound -> respond(HttpStatusCode.NotFound, ApiError("Price not found"))
        OperationResult.Conflict -> error("Price operations do not return conflict results")
        is OperationResult.Invalid ->
            respond(HttpStatusCode.BadRequest, ApiError("Validation failed", result.errors))
        OperationResult.UnexpectedFailure ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
        is OperationResult.Success -> error("A success result cannot be handled as a failure")
    }
}

private suspend fun ApplicationCall.priceIdOrRespond(): Long? {
    val id = parameters["id"]?.toLongOrNull()
    if (id == null) {
        respond(HttpStatusCode.BadRequest, ApiError("Invalid price id"))
    }
    return id
}
