package shop.voenix.article.taxonomy

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
import shop.voenix.article.ReorderInput
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.installAdminRouteProtection
import shop.voenix.http.ApiError
import shop.voenix.operation.OperationResult

/**
 * The admin category routes.
 *
 * A category can be rejected with `409` for three different reasons, and each route can only
 * produce one of them: writing a name produces a name conflict, deleting produces "still in use",
 * and reordering produces a lost race for a position. The routes therefore answer with a stable
 * message per route instead of an extra error code in the body.
 */
internal object ArticleCategoryRoutes {
    private const val BASE_PATH = "/api/admin/articles/categories"
    private const val IN_USE_MESSAGE =
        "Article category is used by subcategories or articles and cannot be deleted"
    private const val ORDER_CONFLICT_MESSAGE =
        "Article category order changed concurrently, please retry"

    fun install(
        application: Application,
        categories: ArticleCategoryOperations,
    ) {
        application.routing {
            authenticate(AuthRouting.PROVIDER) {
                route(BASE_PATH) {
                    installAdminRouteProtection()

                    get { call.respondResult(categories.list()) }

                    post {
                        val input = call.receive<ArticleCategoryInput>()
                        when (val result = categories.create(input)) {
                            is OperationResult.Success -> {
                                call.response.header(
                                    HttpHeaders.Location,
                                    "$BASE_PATH/${result.value.id}",
                                )
                                call.respond(HttpStatusCode.Created, result.value)
                            }

                            else -> call.respondFailure(result)
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

                            else -> call.respondFailure(result)
                        }
                    }

                    route("/{id}") {
                        get {
                            val id = call.categoryIdOrRespond() ?: return@get
                            call.respondResult(categories.get(id))
                        }

                        put {
                            val id = call.categoryIdOrRespond() ?: return@put
                            val input = call.receive<ArticleCategoryInput>()
                            call.respondResult(categories.update(id, input))
                        }

                        delete {
                            val id = call.categoryIdOrRespond() ?: return@delete
                            when (val result = categories.delete(id)) {
                                is OperationResult.Success ->
                                    call.response.status(HttpStatusCode.NoContent)

                                OperationResult.Conflict ->
                                    call.respond(HttpStatusCode.Conflict, ApiError(IN_USE_MESSAGE))

                                else -> call.respondFailure(result)
                            }
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
        else -> respondFailure(result)
    }
}

private suspend fun ApplicationCall.respondFailure(result: OperationResult<*>) {
    when (result) {
        OperationResult.NotFound ->
            respond(HttpStatusCode.NotFound, ApiError("Article category not found"))
        OperationResult.Conflict ->
            respond(HttpStatusCode.Conflict, ApiError("Article category name already exists"))
        is OperationResult.Invalid ->
            respond(HttpStatusCode.BadRequest, ApiError("Validation failed", result.errors))
        OperationResult.UnexpectedFailure ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
        is OperationResult.Success -> error("A success result cannot be handled as a failure")
    }
}

private suspend fun ApplicationCall.categoryIdOrRespond(): Long? {
    val id = parameters["id"]?.toLongOrNull()
    if (id == null) {
        respond(HttpStatusCode.BadRequest, ApiError("Invalid article category id"))
    }
    return id
}
