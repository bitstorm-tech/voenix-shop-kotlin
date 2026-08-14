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

/**
 * The admin prompt-subcategory routes.
 *
 * A subcategory can be rejected with `409` for three different reasons, and each route can only
 * produce one of them: writing a name produces a name conflict, deleting produces "still in use",
 * and reordering produces a lost race for a position. The routes therefore answer with a stable
 * message per route instead of an extra error code in the body.
 *
 * `PUT /order` answers with the new order of the affected category only, because subcategory
 * positions count per category and no other category can have moved.
 */
internal object PromptSubcategoryRoutes {
    private const val BASE_PATH = "/api/admin/prompts/subcategories"
    private const val NOT_FOUND_MESSAGE = "Prompt subcategory not found"
    private const val NAME_CONFLICT_MESSAGE =
        "Prompt subcategory name already exists in this prompt category"
    private const val IN_USE_MESSAGE = "Prompt subcategory is used by prompts and cannot be deleted"
    private const val ORDER_CONFLICT_MESSAGE =
        "Prompt subcategory order changed concurrently, please retry"

    fun install(
        application: Application,
        subcategories: PromptSubcategoryOperations,
    ) {
        application.routing {
            authenticate(AuthRouting.PROVIDER) {
                route(BASE_PATH) {
                    installAdminRouteProtection()

                    get { call.respondResult(subcategories.list(), PROMPT_SUBCATEGORY_RESPONSES) }

                    post {
                        val input = call.receive<PromptSubcategoryInput>()
                        when (val result = subcategories.create(input)) {
                            is OperationResult.Success -> {
                                call.response.header(
                                    HttpHeaders.Location,
                                    "$BASE_PATH/${result.value.id}",
                                )
                                call.respond(HttpStatusCode.Created, result.value)
                            }

                            else -> call.respondFailure(result, PROMPT_SUBCATEGORY_RESPONSES)
                        }
                    }

                    put("/order") {
                        val input = call.receive<ReorderInput>()
                        when (val result = subcategories.reorder(input)) {
                            is OperationResult.Success -> call.respond(result.value)
                            OperationResult.Conflict ->
                                call.respond(
                                    HttpStatusCode.Conflict,
                                    ApiError(ORDER_CONFLICT_MESSAGE),
                                )

                            else -> call.respondFailure(result, PROMPT_SUBCATEGORY_RESPONSES)
                        }
                    }

                    route("/{id}") {
                        get {
                            val id = call.subcategoryIdOrRespond() ?: return@get
                            call.respondResult(subcategories.get(id), PROMPT_SUBCATEGORY_RESPONSES)
                        }

                        put {
                            val id = call.subcategoryIdOrRespond() ?: return@put
                            val input = call.receive<PromptSubcategoryInput>()
                            call.respondResult(
                                subcategories.update(id, input),
                                PROMPT_SUBCATEGORY_RESPONSES,
                            )
                        }

                        delete {
                            val id = call.subcategoryIdOrRespond() ?: return@delete
                            when (val result = subcategories.delete(id)) {
                                is OperationResult.Success ->
                                    call.response.status(HttpStatusCode.NoContent)

                                OperationResult.Conflict ->
                                    call.respond(HttpStatusCode.Conflict, ApiError(IN_USE_MESSAGE))

                                else -> call.respondFailure(result, PROMPT_SUBCATEGORY_RESPONSES)
                            }
                        }
                    }
                }
            }
        }
    }

    private val PROMPT_SUBCATEGORY_RESPONSES =
        OperationResultHttpMapping(
            notFound = ApiError(NOT_FOUND_MESSAGE),
            conflict = ConflictHandling.Respond(ApiError(NAME_CONFLICT_MESSAGE)),
        )

    private suspend fun ApplicationCall.subcategoryIdOrRespond(): Long? =
        longPathParameterOrRespond(
            "id",
            HttpStatusCode.BadRequest,
            ApiError("Invalid prompt subcategory id"),
        )
}
