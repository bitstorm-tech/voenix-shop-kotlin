package shop.voenix.article.tshirt

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

private const val BASE_PATH = "/api/admin/articles/tshirts"
private const val NOT_FOUND_MESSAGE = "Article not found"
private const val ORDER_CONFLICT_MESSAGE = "Article order changed concurrently, please retry"

/**
 * The admin t-shirt routes: five of them, and none that creates a shirt.
 *
 * A shirt is created by a sync run against the Spreadconnect backoffice (ADR 0003), so `POST` is
 * gone, and so are the two pre-uploads that fed a variant's example image and the article's size
 * chart — both pictures come from the partner now. What is left is reading the catalog, writing the
 * shop-owned half of one shirt, ordering the list, and retiring a shirt that will not come back.
 *
 * Exactly one of them can answer `409`, and it is the one that writes positions: a reorder loses
 * its race when the stored sequence is not the one it read. The update has no conflict at all — a
 * shirt has no unique name, and it cannot collide on a position it does not touch — so a conflict
 * reaching it would not be something a client did but something that is broken, and it is answered
 * as such.
 *
 * `PUT /order` is a literal segment next to `/{id}`, and it is registered before it. Ktor prefers
 * the literal over the parameter either way, but a reader of this file should not have to know that
 * to see that `/order` is not an article id.
 */
internal fun Application.installTshirtArticleRoutes(tshirts: TshirtArticleOperations) {
    routing {
        authenticate(AuthRouting.PROVIDER) {
            route(BASE_PATH) {
                installAdminRouteProtection()
                installListRoute(tshirts)
                installReorderRoute(tshirts)
                installItemRoutes(tshirts)
            }
        }
    }
}

/** The overview list: a bare JSON array in display order, never an `{ "items": … }` wrapper. */
private fun Route.installListRoute(tshirts: TshirtArticleOperations) {
    get { call.respondResult(tshirts.list(), TSHIRT_RESPONSES) }
}

/**
 * Moves one shirt to the place of another and answers with the complete new order, so a client
 * never has to reconstruct the positions it did not send.
 */
private fun Route.installReorderRoute(tshirts: TshirtArticleOperations) {
    put("/order") {
        val input = call.receive<ReorderInput>()
        when (val result = tshirts.reorder(input)) {
            is OperationResult.Success -> call.respond(result.value)
            OperationResult.Conflict ->
                call.respond(HttpStatusCode.Conflict, ApiError(ORDER_CONFLICT_MESSAGE))

            else -> call.respondFailure(result, TSHIRT_RESPONSES)
        }
    }
}

private fun Route.installItemRoutes(tshirts: TshirtArticleOperations) {
    route("/{id}") {
        get {
            val id = call.tshirtIdOrRespond() ?: return@get
            call.respondResult(tshirts.get(id), TSHIRT_RESPONSES)
        }

        put {
            val id = call.tshirtIdOrRespond() ?: return@put
            val input = call.receive<TshirtArticleInput>()
            call.respondResult(tshirts.update(id, input), TSHIRT_RESPONSES)
        }

        delete {
            val id = call.tshirtIdOrRespond() ?: return@delete
            when (val result = tshirts.delete(id)) {
                is OperationResult.Success -> call.response.status(HttpStatusCode.NoContent)
                else -> call.respondFailure(result, TSHIRT_RESPONSES)
            }
        }
    }
}

private val TSHIRT_RESPONSES =
    OperationResultHttpMapping(
        notFound = ApiError(NOT_FOUND_MESSAGE),
        conflict =
            ConflictHandling.Unreachable(
                "Only the t-shirt reorder declares a conflict outcome, and it maps its own"
            ),
    )

private suspend fun ApplicationCall.tshirtIdOrRespond(): Long? =
    longPathParameterOrRespond("id", HttpStatusCode.BadRequest, ApiError("Invalid article id"))
