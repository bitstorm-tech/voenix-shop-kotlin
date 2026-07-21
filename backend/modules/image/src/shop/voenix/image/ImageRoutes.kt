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
import shop.voenix.http.ApiError
import shop.voenix.operation.OperationResult

internal object ImageRoutes {
    fun install(
        application: Application,
        images: ImageOperations,
    ) {
        application.routing {
            route("/api/images") {
                install(ConditionalHeaders)
                install(PartialContent)

                imageRoute("public", ImageVisibility.PUBLIC, images)
                authenticate(AuthRouting.PROVIDER) {
                    imageRoute("private", ImageVisibility.PRIVATE, images)
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
            val size = call.parameters["size"].orEmpty()
            val filename = call.parameters.getAll("filename")?.joinToString("/").orEmpty()
            when (val result = images.get(visibility, size, filename)) {
                is OperationResult.Success -> {
                    call.response.header(HttpHeaders.CacheControl, visibility.cacheControl)
                    val resource = result.value
                    val content =
                        LocalPathContent(
                            path = result.value.path,
                            contentType = result.value.contentType,
                        )
                    content.versions =
                        listOf(
                            EntityTagVersion(
                                resource.length.toString(VERSION_RADIX) +
                                    "-" +
                                    resource.lastModifiedMillis.toString(VERSION_RADIX)
                            ),
                            LastModifiedVersion(GMTDate(resource.lastModifiedMillis)),
                        )
                    call.respond(content)
                }
                else -> call.respondFailure(result)
            }
        }
    }

    private const val VERSION_RADIX = 16
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
