package shop.voenix.article.mug

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import shop.voenix.http.ApiError
import shop.voenix.operation.OperationResult

private const val BASE_PATH = "/api/articles/mugs"

/**
 * The storefront mug routes.
 *
 * They are registered outside the `authenticate` block of [installMugArticleRoutes], which is the
 * whole point of a separate installer: a customer browsing the shop has no session, so anonymous
 * access is not a rule these handlers apply but the absence of the admin subtree around them. The
 * paths are `/api/articles/...` rather than `/api/admin/articles/...`, so the two trees cannot be
 * confused by a reader or by Ktor.
 *
 * Both routes answer a bare JSON array. Neither takes a parameter, so the only failure they can
 * report is a database that did not answer — every other [OperationResult] would mean the service
 * invented an outcome these reads do not have.
 */
internal fun Application.installPublicMugRoutes(mugs: PublicMugOperations) {
    routing {
        get(BASE_PATH) { call.respondPublicResult(mugs.list()) }
        get("$BASE_PATH/categories") { call.respondPublicResult(mugs.listCategories()) }
    }
}

private suspend inline fun <reified T : Any> ApplicationCall.respondPublicResult(
    result: OperationResult<T>
) {
    when (result) {
        is OperationResult.Success -> respond(result.value)
        OperationResult.UnexpectedFailure ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
        else -> error("A public mug read has no outcome besides success and an unexpected failure")
    }
}
