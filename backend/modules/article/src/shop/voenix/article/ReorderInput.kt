package shop.voenix.article

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors
import shop.voenix.validation.ValidationErrorsBuilder
import shop.voenix.validation.buildValidationErrors

/**
 * The request body of every reorder route: move [sourceId] to the place currently held by
 * [targetId]. Categories, subcategories, mugs, and t-shirts all order the same way, so they share
 * this one input and its rules instead of four identical bodies with four different field names.
 *
 * Whether the two ids exist is not a field rule — it is a question only the database can answer, so
 * an unknown id becomes a not-found result rather than a validation error.
 */
@Serializable
internal data class ReorderInput(
    val sourceId: Long? = null,
    val targetId: Long? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildValidationErrors {
        validateId("sourceId", "SourceId", sourceId)
        validateId("targetId", "TargetId", targetId)
        if (sourceId != null && sourceId == targetId) {
            add("targetId", "TargetId must be different from SourceId")
        }
    }

    private fun ValidationErrorsBuilder.validateId(
        field: String,
        displayName: String,
        value: Long?,
    ) {
        when {
            value == null -> add(field, "$displayName is required")
            value <= 0 -> add(field, "$displayName must be positive")
        }
    }
}
