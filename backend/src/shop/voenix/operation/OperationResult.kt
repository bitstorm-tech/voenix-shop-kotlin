package shop.voenix.operation

import shop.voenix.validation.ValidationErrors

sealed interface OperationResult<out T> {
    data class Success<T>(val value: T) : OperationResult<T>

    data object UnexpectedFailure : OperationResult<Nothing>

    data object NotFound : OperationResult<Nothing>

    data object Conflict : OperationResult<Nothing>

    data class Invalid(val errors: ValidationErrors) : OperationResult<Nothing>
}
