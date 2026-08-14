package shop.voenix.operation

import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import shop.voenix.validation.ValidationErrors

public sealed interface OperationResult<out T> {
    public data class Success<T>(public val value: T) : OperationResult<T>

    public data object UnexpectedFailure : OperationResult<Nothing>

    public data object NotFound : OperationResult<Nothing>

    public data object Conflict : OperationResult<Nothing>

    public data class Invalid(public val errors: ValidationErrors) : OperationResult<Nothing>
}

/**
 * The same failure with the value type the caller expects. A failed [OperationResult] carries no
 * value, so re-typing it is safe — and it keeps a failure of one module's operation from being
 * copied outcome by outcome into the answer of the calling module's own operation.
 *
 * The receiver must be a failure: calling this on an [OperationResult.Success] throws, because a
 * success has a value and therefore no failure to re-type.
 */
public fun OperationResult<*>.asFailure(): OperationResult<Nothing> =
    when (this) {
        is OperationResult.Success -> error("A success result is not a failure")
        is OperationResult.Invalid -> this
        OperationResult.NotFound -> OperationResult.NotFound
        OperationResult.Conflict -> OperationResult.Conflict
        OperationResult.UnexpectedFailure -> OperationResult.UnexpectedFailure
    }

/**
 * Runs a database-backed operation and answers with [fallback] when it fails unexpectedly.
 *
 * Every service uses the same rule for unexpected persistence failures: log the exception with the
 * service's own logger and return the operation's failure result instead of letting the exception
 * reach the route. Coroutine cancellation is not a failure, so a [CancellationException] is always
 * rethrown — also when it arrives wrapped in another exception, which is why the cause chain is
 * searched the same way `installHttpRuntime`'s `Throwable` handler searches it.
 *
 * The catch is deliberately a superset of "the database failed": it also swallows a bug inside the
 * operation's own mapping code. That is accepted because the alternative — catching `SQLException`
 * only — would push the same `try`/`catch` back into every service. It does mean [message] names
 * the *operation*, not the cause; the cause is in the logged exception.
 *
 * The function is generic in the *result* type, not only in a success value, so it also serves
 * operations that answer with a module-specific result such as `RegisterResult` or
 * `CartPromotionResult`:
 * ```kotlin
 * logger.databaseOperation(
 *     "Database error while listing VAT entries",
 *     OperationResult.UnexpectedFailure,
 * ) {
 *     OperationResult.Success(repository.list())
 * }
 * ```
 *
 * The message is a plain string, so callers use Kotlin string templates (`"… entry $id"`) instead
 * of SLF4J's `{}` placeholders.
 */
public suspend fun <T> Logger.databaseOperation(
    message: String,
    fallback: T,
    operation: suspend () -> T,
): T =
    try {
        operation()
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        generateSequence(exception as Throwable?) { throwable -> throwable.cause }
            .filterIsInstance<CancellationException>()
            .firstOrNull()
            ?.let { cancellation -> throw cancellation }
        error(message, exception)
        fallback
    }
