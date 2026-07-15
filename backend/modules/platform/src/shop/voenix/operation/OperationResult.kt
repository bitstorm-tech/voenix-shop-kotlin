package shop.voenix.operation

import shop.voenix.validation.ValidationErrors

public sealed interface OperationResult<out T> {
    public data class Success<T>(public val value: T) : OperationResult<T>

    public data object UnexpectedFailure : OperationResult<Nothing>

    public data object NotFound : OperationResult<Nothing>

    public data object Conflict : OperationResult<Nothing>

    public data class Invalid(public val errors: ValidationErrors) : OperationResult<Nothing>
}
