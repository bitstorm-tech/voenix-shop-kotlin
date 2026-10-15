package shop.voenix.article.spod

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import shop.voenix.article.ReorderInput
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.installAdminRouteProtection
import shop.voenix.http.ApiError
import shop.voenix.http.ConflictHandling
import shop.voenix.http.OperationResultHttpMapping
import shop.voenix.http.longPathParameterOrRespond
import shop.voenix.http.respondFailure
import shop.voenix.http.respondResult
import shop.voenix.operation.OperationResult

private const val BASE_PATH = "/api/admin/articles/spod"
private const val NOT_FOUND_MESSAGE = "Article not found"
private const val ORDER_CONFLICT_MESSAGE = "Article order changed concurrently, please retry"

/**
 * The admin SPOD article routes: five of them, and none that creates an article.
 *
 * A SPOD article is created by a sync run against the Spreadconnect backoffice (sync decision 1),
 * so there is no `POST` and no picture upload — every picture comes from the partner. What is left
 * is reading the catalog, writing the shop-owned half of one article, ordering the list, and
 * retiring an article that will not come back.
 *
 * Exactly one of them can answer `409`, and it is the one that writes positions: a reorder loses
 * its race when the stored sequence is not the one it read. The update has no conflict at all — an
 * article has no unique name, and it cannot collide on a position it does not touch — so a conflict
 * reaching it would not be something a client did but something that is broken, and it is answered
 * as such.
 *
 * `PUT /order` is a literal segment next to `/{id}`, and it is registered before it. Ktor prefers
 * the literal over the parameter either way, but a reader of this file should not have to know that
 * to see that `/order` is not an article id.
 */
internal fun Application.installSpodArticleRoutes(articles: SpodArticleOperations) {
    routing {
        authenticate(AuthRouting.PROVIDER) {
            route(BASE_PATH) {
                installAdminRouteProtection()
                installListRoute(articles)
                installReorderRoute(articles)
                installItemRoutes(articles)
            }
        }
    }
}

/** The overview list: a bare JSON array in display order, never an `{ "items": … }` wrapper. */
private fun Route.installListRoute(articles: SpodArticleOperations) {
    get { call.respondResult(articles.list(), SPOD_RESPONSES) }
}

/**
 * Moves one article to the place of another and answers with the complete new order, so a client
 * never has to reconstruct the positions it did not send.
 */
private fun Route.installReorderRoute(articles: SpodArticleOperations) {
    put("/order") {
        val input = call.receive<ReorderInput>()
        when (val result = articles.reorder(input)) {
            is OperationResult.Success -> call.respond(result.value)
            OperationResult.Conflict ->
                call.respond(HttpStatusCode.Conflict, ApiError(ORDER_CONFLICT_MESSAGE))

            else -> call.respondFailure(result, SPOD_RESPONSES)
        }
    }
}

private fun Route.installItemRoutes(articles: SpodArticleOperations) {
    route("/{id}") {
        get {
            val id = call.articleIdOrRespond() ?: return@get
            call.respondResult(articles.get(id), SPOD_RESPONSES)
        }

        put {
            val id = call.articleIdOrRespond() ?: return@put
            val input = call.receive<SpodArticleInput>()
            call.respondResult(articles.update(id, input), SPOD_RESPONSES)
        }

        delete {
            val id = call.articleIdOrRespond() ?: return@delete
            when (val result = articles.delete(id)) {
                is OperationResult.Success -> call.response.status(HttpStatusCode.NoContent)
                else -> call.respondFailure(result, SPOD_RESPONSES)
            }
        }
    }
}

private val SPOD_RESPONSES =
    OperationResultHttpMapping(
        notFound = ApiError(NOT_FOUND_MESSAGE),
        conflict =
            ConflictHandling.Unreachable(
                "Only the SPOD article reorder declares a conflict outcome, and it maps its own"
            ),
    )

private suspend fun ApplicationCall.articleIdOrRespond(): Long? =
    longPathParameterOrRespond("id", HttpStatusCode.BadRequest, ApiError("Invalid article id"))
