package shop.voenix.prompt.category

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
import shop.voenix.prompt.ReorderInput

private const val BASE_PATH = "/api/admin/prompts/categories"
private const val IN_USE_MESSAGE =
    "Prompt category is used by subcategories or prompts and cannot be deleted"
private const val ORDER_CONFLICT_MESSAGE =
    "Prompt category order changed concurrently, please retry"

/**
 * The admin prompt-category routes.
 *
 * A category can be rejected with `409` for three different reasons, and each route can only
 * produce one of them: writing a name produces a name conflict, deleting produces "still in use",
 * and reordering produces a lost race for a position. The routes therefore answer with a stable
 * message per route instead of an extra error code in the body.
 *
 * `PUT /order` answers with the complete new order, so a client never has to reconstruct the
 * sequence from the one move it asked for.
 */
internal fun Application.installPromptCategoryRoutes(categories: PromptCategoryOperations) {
    routing {
        authenticate(AuthRouting.PROVIDER) {
            route(BASE_PATH) {
                installAdminRouteProtection()

                get { call.respondResult(categories.list(), PROMPT_CATEGORY_RESPONSES) }

                post {
                    val input = call.receive<PromptCategoryInput>()
                    when (val result = categories.create(input)) {
                        is OperationResult.Success -> {
                            call.response.header(
                                HttpHeaders.Location,
                                "$BASE_PATH/${result.value.id}",
                            )
                            call.respond(HttpStatusCode.Created, result.value)
                        }

                        else -> call.respondFailure(result, PROMPT_CATEGORY_RESPONSES)
                    }
                }

                put("/order") {
                    val input = call.receive<ReorderInput>()
                    when (val result = categories.reorder(input)) {
                        is OperationResult.Success -> call.respond(result.value)
                        OperationResult.Conflict ->
                            call.respond(
                                HttpStatusCode.Conflict,
                                ApiError(ORDER_CONFLICT_MESSAGE),
                            )

                        else -> call.respondFailure(result, PROMPT_CATEGORY_RESPONSES)
                    }
                }

                route("/{id}") {
                    get {
                        val id = call.categoryIdOrRespond() ?: return@get
                        call.respondResult(categories.get(id), PROMPT_CATEGORY_RESPONSES)
                    }

                    put {
                        val id = call.categoryIdOrRespond() ?: return@put
                        val input = call.receive<PromptCategoryInput>()
                        call.respondResult(
                            categories.update(id, input),
                            PROMPT_CATEGORY_RESPONSES,
                        )
                    }

                    delete {
                        val id = call.categoryIdOrRespond() ?: return@delete
                        when (val result = categories.delete(id)) {
                            is OperationResult.Success ->
                                call.response.status(HttpStatusCode.NoContent)

                            OperationResult.Conflict ->
                                call.respond(HttpStatusCode.Conflict, ApiError(IN_USE_MESSAGE))

                            else -> call.respondFailure(result, PROMPT_CATEGORY_RESPONSES)
                        }
                    }
                }
            }
        }
    }
}

private val PROMPT_CATEGORY_RESPONSES =
    OperationResultHttpMapping(
        notFound = ApiError("Prompt category not found"),
        conflict = ConflictHandling.Respond(ApiError("Prompt category name already exists")),
    )

private suspend fun ApplicationCall.categoryIdOrRespond(): Long? =
    longPathParameterOrRespond(
        "id",
        HttpStatusCode.BadRequest,
        ApiError("Invalid prompt category id"),
    )
