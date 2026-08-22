package shop.voenix.article

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import shop.voenix.http.ApiError
import shop.voenix.operation.OperationResult

/**
 * Answers one storefront read.
 *
 * Every anonymous route of this module — the mugs, the shirts, the navigation — takes no parameter,
 * no body, and no token, so the only failure any of them can report is a database that did not
 * answer. Every other [OperationResult] would mean a service invented an outcome these reads do not
 * have, and that is a bug rather than a status code, which is why the branch is an `error` and not
 * a fallback.
 */
internal suspend inline fun <reified T : Any> ApplicationCall.respondPublicRead(
    result: OperationResult<T>
) {
    when (result) {
        is OperationResult.Success -> respond(result.value)
        OperationResult.UnexpectedFailure ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))

        else -> error("A storefront read has no outcome besides success and an unexpected failure")
    }
}
