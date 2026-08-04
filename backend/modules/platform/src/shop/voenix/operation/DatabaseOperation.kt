package shop.voenix.operation

import kotlinx.coroutines.CancellationException
import org.slf4j.Logger

/**
 * Runs a database-backed operation and answers with [fallback] when it fails unexpectedly.
 *
 * Every service uses the same rule for unexpected persistence failures: log the exception with the
 * service's own logger and return the operation's failure result instead of letting the exception
 * reach the route. Coroutine cancellation is not a failure, so a [CancellationException] is always
 * rethrown.
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
        error(message, exception)
        fallback
    }
