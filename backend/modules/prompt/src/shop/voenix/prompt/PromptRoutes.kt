package shop.voenix.prompt

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
import shop.voenix.operation.OperationResult

/**
 * The admin prompt routes.
 *
 * Two things are absent here, and both are the contract rather than an omission:
 * - there is **no delete route**. A prompt is retired by setting `archived`, because carts, orders,
 *   and generated images keep referring to it;
 * - **no route answers `409`**. Every reference a prompt write can get wrong is something the body
 *   named, so it comes back as a field error of that body. A conflict reaching these routes would
 *   not be something a client did but something that is broken, and it is answered as such.
 *
 * The list is a bare JSON array in display order, never an `{ "items": … }` wrapper.
 */
internal object PromptRoutes {
    private const val BASE_PATH = "/api/admin/prompts"
    private const val NOT_FOUND_MESSAGE = "Prompt not found"

    fun install(
        application: Application,
        prompts: PromptOperations,
    ) {
        application.routing {
            authenticate(AuthRouting.PROVIDER) {
                route(BASE_PATH) {
                    installAdminRouteProtection()

                    get { call.respondResult(prompts.list()) }

                    post {
                        val input = call.receive<PromptInput>()
                        when (val result = prompts.create(input)) {
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

                    route("/{id}") {
                        get {
                            val id = call.promptIdOrRespond() ?: return@get
                            call.respondResult(prompts.get(id))
                        }

                        put {
                            val id = call.promptIdOrRespond() ?: return@put
                            val input = call.receive<PromptInput>()
                            call.respondResult(prompts.update(id, input))
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
                respond(HttpStatusCode.NotFound, ApiError(NOT_FOUND_MESSAGE))
            is OperationResult.Invalid ->
                respond(HttpStatusCode.BadRequest, ApiError("Validation failed", result.errors))
            OperationResult.UnexpectedFailure ->
                respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
            OperationResult.Conflict -> error("No prompt operation returns a conflict result")
            is OperationResult.Success -> error("A success result cannot be handled as a failure")
        }
    }

    private suspend fun ApplicationCall.promptIdOrRespond(): Long? {
        val id = parameters["id"]?.toLongOrNull()
        if (id == null) {
            respond(HttpStatusCode.BadRequest, ApiError("Invalid prompt id"))
        }
        return id
    }
}
