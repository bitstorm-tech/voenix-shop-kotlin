package shop.voenix.prompt

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
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
import shop.voenix.image.UploadedImage
import shop.voenix.image.receiveUploadedImage
import shop.voenix.image.respondUploadRejection
import shop.voenix.operation.OperationResult

/**
 * The admin prompt routes.
 *
 * Two things are absent here, and both are the contract rather than an omission:
 * - there is **no delete route**. A prompt is retired by setting `archived`, because carts, orders,
 *   and generated images keep referring to it;
 * - **no route but `PUT /order` answers `409`**. Every reference a prompt write can get wrong is
 *   something the body named, so it comes back as a field error of that body. The one conflict that
 *   is left is a lost race for a position, which only the reorder can lose, and it says so with a
 *   stable message the client may retry on.
 *
 * The list is a bare JSON array in display order, never an `{ "items": … }` wrapper, and `PUT
 * /order` answers with the complete new order in exactly those rows — a client never has to
 * reconstruct the sequence from the one move it asked for.
 *
 * `POST /example-images` is the other half of that JSON contract: an image is uploaded before the
 * prompt that refers to it is written, so create and update stay plain JSON bodies that carry the
 * returned file name.
 */
internal object PromptRoutes {
    private const val BASE_PATH = "/api/admin/prompts"
    private const val NOT_FOUND_MESSAGE = "Prompt not found"
    private const val ORDER_CONFLICT_MESSAGE = "Prompt order changed concurrently, please retry"

    fun install(
        application: Application,
        prompts: PromptOperations,
    ) {
        application.routing {
            authenticate(AuthRouting.PROVIDER) {
                route(BASE_PATH) {
                    installAdminRouteProtection()
                    installCollectionRoutes(prompts)
                    installExampleImageRoute(prompts)
                    installItemRoutes(prompts)
                }
            }
        }
    }

    private fun Route.installCollectionRoutes(prompts: PromptOperations) {
        get { call.respondResult(prompts.list(), PROMPT_RESPONSES) }

        post {
            val input = call.receive<PromptInput>()
            when (val result = prompts.create(input)) {
                is OperationResult.Success -> {
                    call.response.header(HttpHeaders.Location, "$BASE_PATH/${result.value.id}")
                    call.respond(HttpStatusCode.Created, result.value)
                }

                else -> call.respondFailure(result, PROMPT_RESPONSES)
            }
        }

        put("/order") {
            val input = call.receive<ReorderInput>()
            when (val result = prompts.reorder(input)) {
                is OperationResult.Success -> call.respond(result.value)
                OperationResult.Conflict ->
                    call.respond(HttpStatusCode.Conflict, ApiError(ORDER_CONFLICT_MESSAGE))

                else -> call.respondFailure(result, PROMPT_RESPONSES)
            }
        }
    }

    private fun Route.installExampleImageRoute(prompts: PromptOperations) {
        post("/example-images") {
            when (val upload = call.receiveUploadedImage()) {
                UploadedImage.Missing ->
                    call.respondUploadRejection("An example image file part is required")

                UploadedImage.TooLarge ->
                    call.respondUploadRejection("Example image must not exceed 10 MiB")

                is UploadedImage.Received ->
                    when (val result = prompts.storeExampleImage(upload.upload)) {
                        is OperationResult.Success ->
                            call.respond(HttpStatusCode.Created, result.value)

                        else -> call.respondFailure(result, PROMPT_RESPONSES)
                    }
            }
        }
    }

    private fun Route.installItemRoutes(prompts: PromptOperations) {
        route("/{id}") {
            get {
                val id = call.promptIdOrRespond() ?: return@get
                call.respondResult(prompts.get(id), PROMPT_RESPONSES)
            }

            put {
                val id = call.promptIdOrRespond() ?: return@put
                val input = call.receive<PromptInput>()
                call.respondResult(prompts.update(id, input), PROMPT_RESPONSES)
            }
        }
    }

    private val PROMPT_RESPONSES =
        OperationResultHttpMapping(
            notFound = ApiError(NOT_FOUND_MESSAGE),
            conflict =
                ConflictHandling.Unreachable(
                    "Only the reorder route answers a conflict, and it answers its own"
                ),
        )

    private suspend fun ApplicationCall.promptIdOrRespond(): Long? =
        longPathParameterOrRespond("id", HttpStatusCode.BadRequest, ApiError("Invalid prompt id"))
}
