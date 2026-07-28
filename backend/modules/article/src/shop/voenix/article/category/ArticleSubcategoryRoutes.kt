package shop.voenix.article.category

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
import shop.voenix.image.ExampleImageUpload
import shop.voenix.image.receiveExampleImageUpload
import shop.voenix.operation.OperationResult

/**
 * The admin subcategory routes.
 *
 * A subcategory can be rejected with `409` for three different reasons, and each route can only
 * produce one of them: writing a name produces a name conflict, deleting produces "still in use",
 * and reordering produces a lost race for a position. The routes therefore answer with a stable
 * message per route instead of an extra error code in the body.
 *
 * `POST /example-images` is the other half of the JSON contract: an image is uploaded before the
 * subcategory that refers to it is written, so create and update stay plain JSON bodies that carry
 * the returned file name.
 */
internal object ArticleSubcategoryRoutes {
    private const val BASE_PATH = "/api/admin/articles/subcategories"
    private const val NOT_FOUND_MESSAGE = "Article subcategory not found"
    private const val NAME_CONFLICT_MESSAGE =
        "Article subcategory name already exists in this article category"
    private const val IN_USE_MESSAGE =
        "Article subcategory is used by articles and cannot be deleted"
    private const val ORDER_CONFLICT_MESSAGE =
        "Article subcategory order changed concurrently, please retry"

    fun install(
        application: Application,
        subcategories: ArticleSubcategoryOperations,
    ) {
        application.routing {
            authenticate(AuthRouting.PROVIDER) {
                route(BASE_PATH) {
                    installAdminRouteProtection()
                    installCollectionRoutes(subcategories)
                    installExampleImageRoute(subcategories)
                    installItemRoutes(subcategories)
                }
            }
        }
    }

    private fun Route.installCollectionRoutes(subcategories: ArticleSubcategoryOperations) {
        get { call.respondResult(subcategories.list()) }

        post {
            val input = call.receive<ArticleSubcategoryInput>()
            when (val result = subcategories.create(input)) {
                is OperationResult.Success -> {
                    call.response.header(HttpHeaders.Location, "$BASE_PATH/${result.value.id}")
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
                    call.respond(HttpStatusCode.Conflict, ApiError(ORDER_CONFLICT_MESSAGE))

                else -> call.respondFailure(result)
            }
        }
    }

    private fun Route.installExampleImageRoute(subcategories: ArticleSubcategoryOperations) {
        post("/example-images") {
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
                    call.respondResult(
                        subcategories.storeExampleImage(upload.upload),
                        successStatus = HttpStatusCode.Created,
                    )
            }
        }
    }

    private fun Route.installItemRoutes(subcategories: ArticleSubcategoryOperations) {
        route("/{id}") {
            get {
                val id = call.subcategoryIdOrRespond() ?: return@get
                call.respondResult(subcategories.get(id))
            }

            put {
                val id = call.subcategoryIdOrRespond() ?: return@put
                val input = call.receive<ArticleSubcategoryInput>()
                call.respondResult(subcategories.update(id, input))
            }

            delete {
                val id = call.subcategoryIdOrRespond() ?: return@delete
                when (val result = subcategories.delete(id)) {
                    is OperationResult.Success -> call.response.status(HttpStatusCode.NoContent)

                    OperationResult.Conflict ->
                        call.respond(HttpStatusCode.Conflict, ApiError(IN_USE_MESSAGE))

                    else -> call.respondFailure(result)
                }
            }
        }
    }

    private suspend inline fun <reified T : Any> ApplicationCall.respondResult(
        result: OperationResult<T>,
        successStatus: HttpStatusCode = HttpStatusCode.OK,
    ) {
        when (result) {
            is OperationResult.Success -> respond(successStatus, result.value)
            else -> respondFailure(result)
        }
    }

    private suspend fun ApplicationCall.respondFailure(result: OperationResult<*>) {
        when (result) {
            OperationResult.NotFound ->
                respond(HttpStatusCode.NotFound, ApiError(NOT_FOUND_MESSAGE))
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
            respond(HttpStatusCode.BadRequest, ApiError("Invalid article subcategory id"))
        }
        return id
    }
}
