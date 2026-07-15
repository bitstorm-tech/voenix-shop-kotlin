package shop.voenix.validation

import io.ktor.server.plugins.requestvalidation.ValidationResult

public interface Validatable {
    public fun validate(): ValidationErrors
}

public fun Validatable.toRequestValidationResult(): ValidationResult =
    validate().let { errors ->
        if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors.values.flatten())
        }
    }
