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

private const val BASE_PATH = "/api/admin/prompts/slot-variants"
private const val IN_USE_MESSAGE = "Prompt slot variant is used by prompts and cannot be deleted"

/**
 * The admin slot-variant routes.
 *
 * The create accepts the slot, the update does not: a variant belongs to one slot for its whole
 * life. A slot that does not exist is answered as a field error on `slotId` and not as a conflict,
 * so the only `409` a write can produce is the globally unique name — and the delete's only one is
 * "still in use".
 */
internal fun Application.installPromptSlotVariantRoutes(variants: PromptSlotVariantOperations) {
    routing {
        authenticate(AuthRouting.PROVIDER) {
            route(BASE_PATH) {
                installAdminRouteProtection()

                get { call.respondResult(variants.list(), PROMPT_SLOT_VARIANT_RESPONSES) }

                post {
                    val input = call.receive<PromptSlotVariantInput>()
                    when (val result = variants.create(input)) {
                        is OperationResult.Success -> {
                            call.response.header(
                                HttpHeaders.Location,
                                "$BASE_PATH/${result.value.id}",
                            )
                            call.respond(HttpStatusCode.Created, result.value)
                        }

                        else -> call.respondFailure(result, PROMPT_SLOT_VARIANT_RESPONSES)
                    }
                }

                route("/{id}") {
                    get {
                        val id = call.variantIdOrRespond() ?: return@get
                        call.respondResult(variants.get(id), PROMPT_SLOT_VARIANT_RESPONSES)
                    }

                    put {
                        val id = call.variantIdOrRespond() ?: return@put
                        val input = call.receive<PromptSlotVariantUpdate>()
                        call.respondResult(
                            variants.update(id, input),
                            PROMPT_SLOT_VARIANT_RESPONSES,
                        )
                    }

                    delete {
                        val id = call.variantIdOrRespond() ?: return@delete
                        when (val result = variants.delete(id)) {
                            is OperationResult.Success ->
                                call.response.status(HttpStatusCode.NoContent)

                            OperationResult.Conflict ->
                                call.respond(HttpStatusCode.Conflict, ApiError(IN_USE_MESSAGE))

                            else -> call.respondFailure(result, PROMPT_SLOT_VARIANT_RESPONSES)
                        }
                    }
                }
            }
        }
    }
}

private val PROMPT_SLOT_VARIANT_RESPONSES =
    OperationResultHttpMapping(
        notFound = ApiError("Prompt slot variant not found"),
        conflict = ConflictHandling.Respond(ApiError("Prompt slot variant name already exists")),
    )

private suspend fun ApplicationCall.variantIdOrRespond(): Long? =
    longPathParameterOrRespond(
        "id",
        HttpStatusCode.BadRequest,
        ApiError("Invalid prompt slot variant id"),
    )
