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
import shop.voenix.operation.OperationResult
import shop.voenix.prompt.ReorderInput

private const val BASE_PATH = "/api/admin/prompts/subcategories"
private const val NOT_FOUND_MESSAGE = "Prompt subcategory not found"
private const val NAME_CONFLICT_MESSAGE =
    "Prompt subcategory name already exists in this prompt category"
private const val IN_USE_MESSAGE = "Prompt subcategory is used by prompts and cannot be deleted"
private const val ORDER_CONFLICT_MESSAGE =
    "Prompt subcategory order changed concurrently, please retry"

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
internal fun Application.installPromptSubcategoryRoutes(
    subcategories: PromptSubcategoryOperations
) {
    routing {
        authenticate(AuthRouting.PROVIDER) {
            route(BASE_PATH) {
                installAdminRouteProtection()

                get { call.respondResult(subcategories.list()) }

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

                        else -> call.respondFailure(result)
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

                        else -> call.respondFailure(result)
                    }
                }

                route("/{id}") {
                    get {
                        val id = call.subcategoryIdOrRespond() ?: return@get
                        call.respondResult(subcategories.get(id))
                    }

                    put {
                        val id = call.subcategoryIdOrRespond() ?: return@put
                        val input = call.receive<PromptSubcategoryInput>()
                        call.respondResult(subcategories.update(id, input))
                    }

                    delete {
                        val id = call.subcategoryIdOrRespond() ?: return@delete
                        when (val result = subcategories.delete(id)) {
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
        OperationResult.NotFound -> respond(HttpStatusCode.NotFound, ApiError(NOT_FOUND_MESSAGE))
        OperationResult.Conflict ->
            respond(HttpStatusCode.Conflict, ApiError(NAME_CONFLICT_MESSAGE))
        is OperationResult.Invalid ->
            respond(HttpStatusCode.BadRequest, ApiError("Validation failed", result.errors))
        OperationResult.UnexpectedFailure ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
        is OperationResult.Success -> error("A success result cannot be handled as a failure")
    }
}

private suspend fun ApplicationCall.subcategoryIdOrRespond(): Long? {
    val id = parameters["id"]?.toLongOrNull()
    if (id == null) {
        respond(HttpStatusCode.BadRequest, ApiError("Invalid prompt subcategory id"))
    }
    return id
}
