package shop.voenix.image

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.EntityTagVersion
import io.ktor.http.content.LastModifiedVersion
import io.ktor.http.content.versions
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.http.content.LocalPathContent
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.date.GMTDate
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.currentUserSession
import shop.voenix.http.ApiError
import shop.voenix.operation.OperationResult

internal object ImageRoutes {
    fun install(
        application: Application,
        images: ImageOperations,
    ) {
        application.routing {
            route(BASE_PATH) {
                install(ConditionalHeaders)
                install(PartialContent)

                imageRoute("public", ImageVisibility.PUBLIC, images)
                authenticate(AuthRouting.PROVIDER) {
                    imageRoute("private", ImageVisibility.PRIVATE, images)
                }
            }
        }
    }

    /**
     * Installs the guest delivery route as its own seam, because the resolver only exists once the
     * module owning the ownership records is composed.
     *
     * The route deliberately sits outside an `authenticate` block: it has to serve a guest who has
     * no session at all. It reads whatever identity the request happens to carry — a decryptable
     * guest cookie through [GuestTokens.tryGet], which never creates one, and a logged-in user
     * through the session — and lets the resolver decide. A resolver answering `null` produces
     * `404` whether the image does not exist or belongs to somebody else, so an id cannot be probed
     * for existence. Sizing, caching, ETag, and range handling are the private route's, because
     * this route hangs on the same `/api/images` node and inherits its plugins.
     *
     * Install it after [install].
     */
    fun installGuestRoute(
        application: Application,
        images: ImageOperations,
        guestTokens: GuestTokens,
        resolver: GuestImageResolver,
    ) {
        application.routing {
            route(BASE_PATH) {
                get("/guest/{size}/{id}") {
                    val filename =
                        call.parameters["id"]?.toLongOrNull()?.let { imageId ->
                            resolver.resolve(
                                imageId = imageId,
                                guestToken = guestTokens.tryGet(call),
                                userId = call.currentUserSession()?.userId?.toLongOrNull(),
                            )
                        }
                    if (filename == null) {
                        call.respond(HttpStatusCode.NotFound, ApiError("Image not found"))
                        return@get
                    }
                    call.respondImage(
                        images = images,
                        visibility = ImageVisibility.PRIVATE,
                        size = call.parameters["size"].orEmpty(),
                        filename = "$PRINT_IMAGE_FOLDER/$filename",
                    )
                }
            }
        }
    }

    private fun Route.imageRoute(
        path: String,
        visibility: ImageVisibility,
        images: ImageOperations,
    ) {
        get("/$path/{size}/{filename...}") {
            call.respondImage(
                images = images,
                visibility = visibility,
                size = call.parameters["size"].orEmpty(),
                filename = call.parameters.getAll("filename")?.joinToString("/").orEmpty(),
            )
        }
    }

    private suspend fun ApplicationCall.respondImage(
        images: ImageOperations,
        visibility: ImageVisibility,
        size: String,
        filename: String,
    ) {
        when (val result = images.get(visibility, size, filename)) {
            is OperationResult.Success -> {
                response.header(HttpHeaders.CacheControl, visibility.cacheControl)
                val resource = result.value
                val content =
                    LocalPathContent(path = resource.path, contentType = resource.contentType)
                content.versions =
                    listOf(
                        EntityTagVersion(
                            resource.length.toString(VERSION_RADIX) +
                                "-" +
                                resource.lastModifiedMillis.toString(VERSION_RADIX)
                        ),
                        LastModifiedVersion(GMTDate(resource.lastModifiedMillis)),
                    )
                respond(content)
            }
            else -> respondFailure(result)
        }
    }

    private const val BASE_PATH = "/api/images"
    private const val VERSION_RADIX = 16
}

/**
 * Answers the only question the guest delivery route cannot answer itself: does this caller own the
 * private image with this id, and under which stored file name?
 *
 * The port is defined here, not by the module that owns the ownership records, so that the image
 * module never has to depend on that module. The composition root binds the implementation after
 * both sides exist.
 *
 * The interface is deliberately path-free and answer-free: it takes an id and whatever identity the
 * request carried, and returns a stored file name or `null`. It must not distinguish "no such
 * image" from "someone else's image" — the route turns both into `404`, so an id cannot be probed
 * for existence.
 */
public fun interface GuestImageResolver {
    /**
     * Returns the stored file name of [imageId] when it belongs to the caller identified by
     * [guestToken] or [userId], and `null` otherwise. Both identities are optional: an anonymous
     * request without a guest cookie owns nothing.
     */
    public suspend fun resolve(
        imageId: Long,
        guestToken: String?,
        userId: Long?,
    ): String?
}

private suspend fun ApplicationCall.respondFailure(result: OperationResult<*>) {
    when (result) {
        is OperationResult.Invalid ->
            respond(
                HttpStatusCode.BadRequest,
                ApiError("Validation failed", result.errors),
            )
        OperationResult.NotFound -> respond(HttpStatusCode.NotFound, ApiError("Image not found"))
        OperationResult.UnexpectedFailure ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
        OperationResult.Conflict -> error("Image operations do not return conflicts")
        is OperationResult.Success -> error("A success result cannot be handled as a failure")
    }
}
