package shop.voenix.article.tshirt

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
import shop.voenix.article.ExampleImage
import shop.voenix.article.ReorderInput
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.installAdminRouteProtection
import shop.voenix.http.ApiError
import shop.voenix.http.ConflictHandling
import shop.voenix.http.OperationResultHttpMapping
import shop.voenix.http.longPathParameterOrRespond
import shop.voenix.http.respondFailure
import shop.voenix.http.respondResult
import shop.voenix.image.ImageUpload
import shop.voenix.image.UploadedImage
import shop.voenix.image.receiveUploadedImage
import shop.voenix.image.respondUploadRejection
import shop.voenix.operation.OperationResult

private const val BASE_PATH = "/api/admin/articles/tshirts"
private const val NOT_FOUND_MESSAGE = "Article not found"
private const val ORDER_CONFLICT_MESSAGE = "Article order changed concurrently, please retry"

/**
 * The admin t-shirt routes, laid out exactly like the mug ones.
 *
 * Exactly one of them can answer `409`, and it is the one that writes positions: a reorder loses
 * its race when the stored sequence is not the one it read. Every other shirt write has no conflict
 * at all — shirts have no unique name, and a create or an update cannot collide on a position while
 * the type anchor is locked — so a conflict reaching them would not be something a client did but
 * something that is broken, and it is answered as such.
 *
 * `PUT /order` is a literal segment next to `/{id}`, and it is registered before it. Ktor prefers
 * the literal over the parameter either way, but a reader of this file should not have to know that
 * to see that `/order` is not an article id.
 *
 * The two pre-upload routes are the other half of the JSON contract: a picture is uploaded before
 * the article that refers to it is written, so create and update stay plain JSON bodies that carry
 * the returned file names. They are two routes rather than one with a parameter, because the two
 * kinds of picture are stored in two folders and a name from one is not a name in the other.
 */
internal fun Application.installTshirtArticleRoutes(tshirts: TshirtArticleOperations) {
    routing {
        authenticate(AuthRouting.PROVIDER) {
            route(BASE_PATH) {
                installAdminRouteProtection()
                installListRoute(tshirts)
                installCreateRoute(tshirts)
                installReorderRoute(tshirts)
                installImageRoutes(tshirts)
                installItemRoutes(tshirts)
            }
        }
    }
}

/** The overview list: a bare JSON array in display order, never an `{ "items": … }` wrapper. */
private fun Route.installListRoute(tshirts: TshirtArticleOperations) {
    get { call.respondResult(tshirts.list(), TSHIRT_RESPONSES) }
}

private fun Route.installCreateRoute(tshirts: TshirtArticleOperations) {
    post {
        val input = call.receive<TshirtArticleInput>()
        when (val result = tshirts.create(input)) {
            is OperationResult.Success -> {
                call.response.header(HttpHeaders.Location, "$BASE_PATH/${result.value.id}")
                call.respond(HttpStatusCode.Created, result.value)
            }

            else -> call.respondFailure(result, TSHIRT_RESPONSES)
        }
    }
}

/**
 * Moves one shirt to the place of another and answers with the complete new order, so a client
 * never has to reconstruct the positions it did not send.
 */
private fun Route.installReorderRoute(tshirts: TshirtArticleOperations) {
    put("/order") {
        val input = call.receive<ReorderInput>()
        when (val result = tshirts.reorder(input)) {
            is OperationResult.Success -> call.respond(result.value)
            OperationResult.Conflict ->
                call.respond(HttpStatusCode.Conflict, ApiError(ORDER_CONFLICT_MESSAGE))

            else -> call.respondFailure(result, TSHIRT_RESPONSES)
        }
    }
}

private fun Route.installImageRoutes(tshirts: TshirtArticleOperations) {
    post("/variant-example-images") {
        call.storeUploadedImage(
            missing = "An example image file part is required",
            tooLarge = "Example image must not exceed 10 MiB",
        ) { upload ->
            tshirts.storeVariantExampleImage(upload)
        }
    }
    post("/size-charts") {
        call.storeUploadedImage(
            missing = "A size chart file part is required",
            tooLarge = "Size chart must not exceed 10 MiB",
        ) { upload ->
            tshirts.storeSizeChartImage(upload)
        }
    }
}

/**
 * Reads one uploaded file part and hands it to [store]. Both pre-uploads answer the same three
 * outcomes and differ only in the two messages that name the picture.
 */
private suspend fun ApplicationCall.storeUploadedImage(
    missing: String,
    tooLarge: String,
    store: suspend (ImageUpload) -> OperationResult<ExampleImage>,
) {
    when (val upload = receiveUploadedImage()) {
        UploadedImage.Missing -> respondUploadRejection(missing)

        UploadedImage.TooLarge -> respondUploadRejection(tooLarge)

        is UploadedImage.Received ->
            when (val result = store(upload.upload)) {
                is OperationResult.Success -> respond(HttpStatusCode.Created, result.value)

                else -> respondFailure(result, TSHIRT_RESPONSES)
            }
    }
}

private fun Route.installItemRoutes(tshirts: TshirtArticleOperations) {
    route("/{id}") {
        get {
            val id = call.tshirtIdOrRespond() ?: return@get
            call.respondResult(tshirts.get(id), TSHIRT_RESPONSES)
        }

        put {
            val id = call.tshirtIdOrRespond() ?: return@put
            val input = call.receive<TshirtArticleInput>()
            call.respondResult(tshirts.update(id, input), TSHIRT_RESPONSES)
        }

        delete {
            val id = call.tshirtIdOrRespond() ?: return@delete
            when (val result = tshirts.delete(id)) {
                is OperationResult.Success -> call.response.status(HttpStatusCode.NoContent)
                else -> call.respondFailure(result, TSHIRT_RESPONSES)
            }
        }
    }
}

private val TSHIRT_RESPONSES =
    OperationResultHttpMapping(
        notFound = ApiError(NOT_FOUND_MESSAGE),
        conflict =
            ConflictHandling.Unreachable(
                "Only the t-shirt reorder declares a conflict outcome, and it maps its own"
            ),
    )

private suspend fun ApplicationCall.tshirtIdOrRespond(): Long? =
    longPathParameterOrRespond("id", HttpStatusCode.BadRequest, ApiError("Invalid article id"))
