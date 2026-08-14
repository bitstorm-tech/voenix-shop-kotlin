package shop.voenix.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import shop.voenix.operation.OperationResult

/**
 * How one route file answers the failure variants of [OperationResult].
 *
 * Every module used to carry its own private `respondResult`/`respondFailure` pair, and all of them
 * differed only in a handful of message strings. The mapping keeps those strings — the part that is
 * genuinely module-specific — and shares the `when` over the result variants.
 *
 * A route file declares one mapping and passes it to [respondResult] or [respondFailure]:
 * ```kotlin
 * private val VAT_RESPONSES =
 *     OperationResultHttpMapping(
 *         notFound = ApiError("VAT not found"),
 *         conflict = ConflictHandling.Respond(ApiError("VAT entry already exists")),
 *     )
 *
 * get { call.respondResult(vats.list(), VAT_RESPONSES) }
 * ```
 *
 * [OperationResult.UnexpectedFailure] is not configurable: it always answers `500` with
 * `ApiError("Internal server error")`, because an unexpected failure must never describe itself.
 */
public data class OperationResultHttpMapping(
    /** The `404` body for [OperationResult.NotFound]. */
    public val notFound: ApiError,
    /** What a [OperationResult.Conflict] means for this route file. */
    public val conflict: ConflictHandling,
    /** What an [OperationResult.Invalid] means for this route file. */
    public val invalid: InvalidHandling = InvalidHandling.RespondValidationErrors,
)

/**
 * Whether the operations behind a route file can answer with [OperationResult.Conflict] at all.
 *
 * Some operations have no conflict to report. Declaring that with [Unreachable] keeps the promise
 * visible in the mapping instead of hiding a never-used message in it.
 */
public sealed interface ConflictHandling {
    /** Answer `409` with [error]. */
    public data class Respond(public val error: ApiError) : ConflictHandling

    /** The operations cannot produce a conflict; reaching this is a bug described by [reason]. */
    public data class Unreachable(public val reason: String) : ConflictHandling
}

/** The same distinction as [ConflictHandling], for [OperationResult.Invalid]. */
public sealed interface InvalidHandling {
    /** Answer `400` with `ApiError("Validation failed", result.errors)`. */
    public data object RespondValidationErrors : InvalidHandling

    /** The operations cannot produce field errors; reaching this is a bug described by [reason]. */
    public data class Unreachable(public val reason: String) : InvalidHandling
}

/**
 * Answers with [result]'s success value under [successStatus], or with the failure [mapping].
 *
 * The function is `inline` and `reified` because Ktor's `respond` needs the runtime type of the
 * value to serialize it.
 */
public suspend inline fun <reified T : Any> ApplicationCall.respondResult(
    result: OperationResult<T>,
    mapping: OperationResultHttpMapping,
    successStatus: HttpStatusCode = HttpStatusCode.OK,
) {
    when (result) {
        is OperationResult.Success -> respond(successStatus, result.value)
        else -> respondFailure(result, mapping)
    }
}

/**
 * Answers a failure [result] with the [mapping].
 *
 * Routes call this directly when they handle [OperationResult.Success] themselves — a `201 Created`
 * with a `Location` header, or a `204 No Content` delete. Passing a success here is a programming
 * error and fails loudly.
 */
public suspend fun ApplicationCall.respondFailure(
    result: OperationResult<*>,
    mapping: OperationResultHttpMapping,
) {
    when (result) {
        OperationResult.NotFound -> respond(HttpStatusCode.NotFound, mapping.notFound)
        OperationResult.Conflict ->
            when (val conflict = mapping.conflict) {
                is ConflictHandling.Respond -> respond(HttpStatusCode.Conflict, conflict.error)
                is ConflictHandling.Unreachable -> error(conflict.reason)
            }
        is OperationResult.Invalid ->
            when (val invalid = mapping.invalid) {
                InvalidHandling.RespondValidationErrors ->
                    respond(
                        HttpStatusCode.BadRequest,
                        ApiError("Validation failed", result.errors),
                    )
                is InvalidHandling.Unreachable -> error(invalid.reason)
            }
        OperationResult.UnexpectedFailure ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
        is OperationResult.Success -> error("A success result cannot be handled as a failure")
    }
}

/**
 * Reads the path parameter [parameterName] as a `Long`, or answers with [malformedStatus] and
 * [malformedError] and returns `null`.
 *
 * The parse is exactly [String.toLongOrNull]: a `0` and a negative number are valid `Long`s and are
 * passed on to the operation, which answers `NotFound` for them like for any other unknown id.
 *
 * A route uses the `?: return@get` idiom, so the answer is already written when it returns:
 * ```kotlin
 * get {
 *     val id = call.vatIdOrRespond() ?: return@get
 *     call.respondResult(vats.get(id), VAT_RESPONSES)
 * }
 * ```
 */
public suspend fun ApplicationCall.longPathParameterOrRespond(
    parameterName: String,
    malformedStatus: HttpStatusCode,
    malformedError: ApiError,
): Long? {
    val value = parameters[parameterName]?.toLongOrNull()
    if (value == null) {
        respond(malformedStatus, malformedError)
    }
    return value
}
