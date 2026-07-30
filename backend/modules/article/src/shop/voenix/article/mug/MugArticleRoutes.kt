package shop.voenix.article.mug

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
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
import shop.voenix.image.UploadedImage
import shop.voenix.image.receiveUploadedImage
import shop.voenix.operation.OperationResult

/**
 * The admin mug routes.
 *
 * Exactly one of these routes can answer `409`, and it is the one that writes positions: a reorder
 * loses its race when the stored sequence is not the one it read. Every other mug write has no
 * conflict at all — mugs have no unique name, and a create or an update cannot collide on a
 * position while the type anchor is locked — so a conflict reaching them would not be something a
 * client did but something that is broken, and it is answered as such.
 *
 * `PUT /order` is a literal segment next to `/{id}`, and it is registered before it. Ktor prefers
 * the literal over the parameter either way, but a reader of this file should not have to know that
 * to see that `/order` is not an article id.
 *
 * `POST /variant-example-images` is the other half of the JSON contract: an image is uploaded
 * before the variant that refers to it is written, so create and update stay plain JSON bodies that
 * carry the returned file name.
 */
internal object MugArticleRoutes {
    private const val BASE_PATH = "/api/admin/articles/mugs"
    private const val NOT_FOUND_MESSAGE = "Article not found"
    private const val ORDER_CONFLICT_MESSAGE = "Article order changed concurrently, please retry"

    fun install(
        application: Application,
        mugs: MugArticleOperations,
    ) {
        application.routing {
            authenticate(AuthRouting.PROVIDER) {
                route(BASE_PATH) {
                    installAdminRouteProtection()
                    installListRoute(mugs)
                    installCreateRoute(mugs)
                    installReorderRoute(mugs)
                    installVariantExampleImageRoute(mugs)
                    installItemRoutes(mugs)
                }
            }
        }
    }

    /** The overview list: a bare JSON array in display order, never an `{ "items": … }` wrapper. */
    private fun Route.installListRoute(mugs: MugArticleOperations) {
        get {
            when (val result = mugs.list()) {
                is OperationResult.Success -> call.respond(result.value)
                else -> call.respondFailure(result)
            }
        }
    }

    private fun Route.installCreateRoute(mugs: MugArticleOperations) {
        post {
            val input = call.receive<MugArticleInput>()
            when (val result = mugs.create(input)) {
                is OperationResult.Success -> {
                    call.response.header(HttpHeaders.Location, "$BASE_PATH/${result.value.id}")
                    call.respond(HttpStatusCode.Created, result.value)
                }

                else -> call.respondFailure(result)
            }
        }
    }

    /**
     * Moves one mug to the place of another and answers with the complete new order, so a client
     * never has to reconstruct the positions it did not send.
     */
    private fun Route.installReorderRoute(mugs: MugArticleOperations) {
        put("/order") {
            val input = call.receive<ReorderInput>()
            when (val result = mugs.reorder(input)) {
                is OperationResult.Success -> call.respond(result.value)
                OperationResult.Conflict ->
                    call.respond(HttpStatusCode.Conflict, ApiError(ORDER_CONFLICT_MESSAGE))

                else -> call.respondFailure(result)
            }
        }
    }

    private fun Route.installVariantExampleImageRoute(mugs: MugArticleOperations) {
        post("/variant-example-images") {
            when (val upload = call.receiveUploadedImage()) {
                UploadedImage.Missing ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("An example image file part is required"),
                    )

                UploadedImage.TooLarge ->
                    call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        ApiError("Example image must not exceed 10 MiB"),
                    )

                is UploadedImage.Received ->
                    when (val result = mugs.storeVariantExampleImage(upload.upload)) {
                        is OperationResult.Success ->
                            call.respond(HttpStatusCode.Created, result.value)

                        else -> call.respondFailure(result)
                    }
            }
        }
    }

    private fun Route.installItemRoutes(mugs: MugArticleOperations) {
        route("/{id}") {
            get {
                val id = call.mugIdOrRespond() ?: return@get
                when (val result = mugs.get(id)) {
                    is OperationResult.Success -> call.respond(result.value)
                    else -> call.respondFailure(result)
                }
            }

            put {
                val id = call.mugIdOrRespond() ?: return@put
                val input = call.receive<MugArticleInput>()
                when (val result = mugs.update(id, input)) {
                    is OperationResult.Success -> call.respond(result.value)
                    else -> call.respondFailure(result)
                }
            }

            delete {
                val id = call.mugIdOrRespond() ?: return@delete
                when (val result = mugs.delete(id)) {
                    is OperationResult.Success -> call.response.status(HttpStatusCode.NoContent)
                    else -> call.respondFailure(result)
                }
            }
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
            OperationResult.Conflict ->
                error("Only the mug reorder declares a conflict outcome, and it maps its own")
            is OperationResult.Success -> error("A success result cannot be handled as a failure")
        }
    }

    private suspend fun ApplicationCall.mugIdOrRespond(): Long? {
        val id = parameters["id"]?.toLongOrNull()
        if (id == null) {
            respond(HttpStatusCode.BadRequest, ApiError("Invalid article id"))
        }
        return id
    }
}
