package shop.voenix.validation

import io.ktor.server.plugins.requestvalidation.ValidationResult

public interface Validatable {
    public fun validate(): ValidationErrors
}

/**
 * Validation messages grouped by lower-camel-case field name.
 *
 * An empty map means the input is valid.
 */
public typealias ValidationErrors = Map<String, List<String>>

public fun Validatable.toRequestValidationResult(): ValidationResult =
    validate().let { errors ->
        if (errors.isEmpty()) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(errors.values.flatten())
        }
    }
