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
import shop.voenix.http.ConflictHandling
import shop.voenix.http.OperationResultHttpMapping
import shop.voenix.http.longPathParameterOrRespond
import shop.voenix.http.respondFailure
import shop.voenix.http.respondResult
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
                            else -> call.respondFailure(result, PRICE_RESPONSES)
                        }
                    }

                    post("/calculate") {
                        call.respondResult(
                            prices.calculate(call.receive<PriceInput>()),
                            PRICE_RESPONSES,
                        )
                    }

                    get("/default") {
                        when (val result = prices.default()) {
                            is OperationResult.Invalid ->
                                call.respond(
                                    HttpStatusCode.BadRequest,
                                    ApiError("No VAT is configured", result.errors),
                                )
                            else -> call.respondResult(result, PRICE_RESPONSES)
                        }
                    }

                    route("/{id}") {
                        get {
                            val id = call.priceIdOrRespond() ?: return@get
                            call.respondResult(prices.get(id), PRICE_RESPONSES)
                        }

                        put {
                            val id = call.priceIdOrRespond() ?: return@put
                            call.respondResult(
                                prices.update(id, call.receive<PriceInput>()),
                                PRICE_RESPONSES,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val PRICE_RESPONSES =
    OperationResultHttpMapping(
        notFound = ApiError("Price not found"),
        conflict = ConflictHandling.Unreachable("Price operations do not return conflict results"),
    )

private suspend fun ApplicationCall.priceIdOrRespond(): Long? =
    longPathParameterOrRespond("id", HttpStatusCode.BadRequest, ApiError("Invalid price id"))
