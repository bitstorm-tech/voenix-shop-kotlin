package shop.voenix.prompt.slot

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

/**
 * The admin slot routes.
 *
 * A slot can be rejected with `409` for two different reasons, and each route can only produce one
 * of them: writing a name produces a name conflict, deleting produces "still in use". The routes
 * therefore answer with a stable message per route instead of an extra error code in the body.
 */
internal object PromptSlotRoutes {
    private const val BASE_PATH = "/api/admin/prompts/slots"
    private const val IN_USE_MESSAGE = "Prompt slot is used by slot variants and cannot be deleted"

    fun install(
        application: Application,
        slots: PromptSlotOperations,
    ) {
        application.routing {
            authenticate(AuthRouting.PROVIDER) {
                route(BASE_PATH) {
                    installAdminRouteProtection()

                    get { call.respondResult(slots.list(), PROMPT_SLOT_RESPONSES) }

                    post {
                        val input = call.receive<PromptSlotInput>()
                        when (val result = slots.create(input)) {
                            is OperationResult.Success -> {
                                call.response.header(
                                    HttpHeaders.Location,
                                    "$BASE_PATH/${result.value.id}",
                                )
                                call.respond(HttpStatusCode.Created, result.value)
                            }

                            else -> call.respondFailure(result, PROMPT_SLOT_RESPONSES)
                        }
                    }

                    route("/{id}") {
                        get {
                            val id = call.slotIdOrRespond() ?: return@get
                            call.respondResult(slots.get(id), PROMPT_SLOT_RESPONSES)
                        }

                        put {
                            val id = call.slotIdOrRespond() ?: return@put
                            val input = call.receive<PromptSlotInput>()
                            call.respondResult(slots.update(id, input), PROMPT_SLOT_RESPONSES)
                        }

                        delete {
                            val id = call.slotIdOrRespond() ?: return@delete
                            when (val result = slots.delete(id)) {
                                is OperationResult.Success ->
                                    call.response.status(HttpStatusCode.NoContent)

                                OperationResult.Conflict ->
                                    call.respond(HttpStatusCode.Conflict, ApiError(IN_USE_MESSAGE))

                                else -> call.respondFailure(result, PROMPT_SLOT_RESPONSES)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val PROMPT_SLOT_RESPONSES =
    OperationResultHttpMapping(
        notFound = ApiError("Prompt slot not found"),
        conflict = ConflictHandling.Respond(ApiError("Prompt slot name already exists")),
    )

private suspend fun ApplicationCall.slotIdOrRespond(): Long? =
    longPathParameterOrRespond("id", HttpStatusCode.BadRequest, ApiError("Invalid prompt slot id"))
