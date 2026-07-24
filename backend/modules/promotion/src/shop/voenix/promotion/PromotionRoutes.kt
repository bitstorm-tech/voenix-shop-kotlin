package shop.voenix.promotion

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.installAdminRouteProtection
import shop.voenix.http.ApiError
import shop.voenix.operation.OperationResult

internal object PromotionRoutes {
    fun install(
        application: Application,
        promotions: PromotionOperations,
    ) {
        application.routing {
            authenticate(AuthRouting.PROVIDER) {
                route("/api/admin/promotions") {
                    installAdminRouteProtection()

                    get { call.respondResult(promotions.list()) }

                    route("/{id}") {
                        get {
                            val id = call.promotionIdOrRespond() ?: return@get
                            call.respondResult(promotions.get(id))
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
        OperationResult.NotFound ->
            respond(HttpStatusCode.NotFound, ApiError("Promotion not found"))
        OperationResult.Conflict -> respond(HttpStatusCode.Conflict, ApiError("Conflict"))
        is OperationResult.Invalid ->
            respond(HttpStatusCode.BadRequest, ApiError("Validation failed", result.errors))
        OperationResult.UnexpectedFailure ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
    }
}

private suspend fun ApplicationCall.promotionIdOrRespond(): Long? {
    val id = parameters["id"]?.toLongOrNull()
    if (id == null) {
        respond(HttpStatusCode.BadRequest, ApiError("Invalid promotion id"))
    }
    return id
}
