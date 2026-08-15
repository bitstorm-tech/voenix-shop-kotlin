package shop.voenix.promotion

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

internal fun Application.installPromotionRoutes(promotions: PromotionOperations) {
    routing {
        authenticate(AuthRouting.PROVIDER) {
            route("/api/admin/promotions") {
                installAdminRouteProtection()

                get { call.respondResult(promotions.list(), PROMOTION_RESPONSES) }

                post {
                    val input = call.receive<PromotionInput>()
                    when (val result = promotions.create(input)) {
                        is OperationResult.Success -> {
                            call.response.header(
                                HttpHeaders.Location,
                                "/api/admin/promotions/${result.value.id}",
                            )
                            call.respond(HttpStatusCode.Created, result.value)
                        }

                        else -> call.respondFailure(result, PROMOTION_RESPONSES)
                    }
                }

                route("/{id}") {
                    get {
                        val id = call.promotionIdOrRespond() ?: return@get
                        call.respondResult(promotions.get(id), PROMOTION_RESPONSES)
                    }

                    put {
                        val id = call.promotionIdOrRespond() ?: return@put
                        val input = call.receive<PromotionInput>()
                        when (val result = promotions.update(id, input)) {
                            is OperationResult.Success -> call.respond(result.value)
                            OperationResult.Conflict ->
                                call.respond(
                                    HttpStatusCode.Conflict,
                                    ApiError(
                                        "Coupon code is already in use or " +
                                            "the promotion is locked"
                                    ),
                                )
                            else -> call.respondFailure(result, PROMOTION_RESPONSES)
                        }
                    }

                    delete {
                        val id = call.promotionIdOrRespond() ?: return@delete
                        when (val result = promotions.delete(id)) {
                            is OperationResult.Success ->
                                call.response.status(HttpStatusCode.NoContent)
                            OperationResult.Conflict ->
                                call.respond(
                                    HttpStatusCode.Conflict,
                                    ApiError("Promotion is still in use and cannot be deleted"),
                                )
                            else -> call.respondFailure(result, PROMOTION_RESPONSES)
                        }
                    }
                }
            }
        }
    }
}

private val PROMOTION_RESPONSES =
    OperationResultHttpMapping(
        notFound = ApiError("Promotion not found"),
        conflict = ConflictHandling.Respond(ApiError("Coupon code is already in use")),
    )

private suspend fun ApplicationCall.promotionIdOrRespond(): Long? =
    longPathParameterOrRespond("id", HttpStatusCode.BadRequest, ApiError("Invalid promotion id"))
