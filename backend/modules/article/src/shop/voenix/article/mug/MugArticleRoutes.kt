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
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import shop.voenix.article.ExampleImageUpload
import shop.voenix.article.receiveExampleImageUpload
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.installAdminRouteProtection
import shop.voenix.http.ApiError
import shop.voenix.operation.OperationResult

/**
 * The admin mug routes.
 *
 * Unlike the taxonomy routes, none of these can answer `409`. Mugs have no unique name, and the
 * only unique rule they have — the position — cannot collide while the type anchor is locked. A
 * conflict here would therefore not be something a client did but something that is broken, and it
 * is answered as such.
 *
 * `POST /variant-example-images` is the other half of the JSON contract: an image is uploaded
 * before the variant that refers to it is written, so create and update stay plain JSON bodies that
 * carry the returned file name.
 */
internal object MugArticleRoutes {
    private const val BASE_PATH = "/api/admin/articles/mugs"
    private const val NOT_FOUND_MESSAGE = "Article not found"

    fun install(
        application: Application,
        mugs: MugArticleOperations,
    ) {
        application.routing {
            authenticate(AuthRouting.PROVIDER) {
                route(BASE_PATH) {
                    installAdminRouteProtection()
                    installCreateRoute(mugs)
                    installVariantExampleImageRoute(mugs)
                    installItemRoutes(mugs)
                }
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

    private fun Route.installVariantExampleImageRoute(mugs: MugArticleOperations) {
        post("/variant-example-images") {
            when (val upload = call.receiveExampleImageUpload()) {
                ExampleImageUpload.Missing ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("An example image file part is required"),
                    )

                ExampleImageUpload.TooLarge ->
                    call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        ApiError("Example image must not exceed 10 MiB"),
                    )

                is ExampleImageUpload.Received ->
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
            OperationResult.Conflict -> error("A mug write declares no conflict outcome")
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
