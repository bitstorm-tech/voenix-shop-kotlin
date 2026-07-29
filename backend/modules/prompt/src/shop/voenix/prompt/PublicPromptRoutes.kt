package shop.voenix.prompt

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import shop.voenix.http.ApiError
import shop.voenix.operation.OperationResult

/**
 * The storefront prompt route.
 *
 * It is registered outside the `authenticate` block of [PromptRoutes], which is the whole point of
 * a separate object: a customer choosing a prompt has no session, so anonymous access is not a rule
 * this handler applies but the absence of the admin subtree around it. The path is `/api/prompts`
 * rather than `/api/admin/prompts`, so the two trees cannot be confused by a reader or by Ktor.
 *
 * `categoryId` is the only parameter, and the two ways it can go wrong are deliberately different
 * answers. A value that is not a number is a request this route cannot even ask a question about,
 * so it is rejected with `400` before the operation runs. A number that names no category is a
 * perfectly good question with the answer `[]` — a customer following a stale link sees an empty
 * list, not an error. An absent or empty parameter means "no filter", which is what a form that
 * submits its fields unconditionally sends.
 *
 * The answer is a bare JSON array. The route takes nothing else, so the only failure it can report
 * besides the rejected parameter is a database that did not answer.
 */
internal object PublicPromptRoutes {
    private const val PATH = "/api/prompts"
    private const val CATEGORY_PARAMETER = "categoryId"

    fun install(
        application: Application,
        prompts: PublicPromptOperations,
    ) {
        application.routing {
            get(PATH) {
                val requested =
                    call.request.queryParameters[CATEGORY_PARAMETER]?.takeIf(String::isNotBlank)
                val categoryId = requested?.toLongOrNull()
                if (requested != null && categoryId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiError("Invalid prompt category id"),
                    )
                    return@get
                }

                when (val result = prompts.list(categoryId)) {
                    is OperationResult.Success -> call.respond(result.value)
                    OperationResult.UnexpectedFailure ->
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ApiError("Internal server error"),
                        )

                    else ->
                        error("A public prompt read has no outcome besides success and a failure")
                }
            }
        }
    }
}
