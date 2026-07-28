package shop.voenix.article

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

/**
 * The request body of every reorder route: move [sourceId] to the place currently held by
 * [targetId]. Categories, subcategories, and mugs all order the same way, so they share this one
 * input and its rules instead of three identical bodies with three different field names.
 *
 * Whether the two ids exist is not a field rule — it is a question only the database can answer, so
 * an unknown id becomes a not-found result rather than a validation error.
 */
@Serializable
internal data class ReorderInput(
    val sourceId: Long? = null,
    val targetId: Long? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        validateId("sourceId", "SourceId", sourceId)
        validateId("targetId", "TargetId", targetId)
        if (sourceId != null && sourceId == targetId) {
            put("targetId", listOf("TargetId must be different from SourceId"))
        }
    }

    private fun MutableMap<String, List<String>>.validateId(
        field: String,
        displayName: String,
        value: Long?,
    ) {
        when {
            value == null -> put(field, listOf("$displayName is required"))
            value <= 0 -> put(field, listOf("$displayName must be positive"))
        }
    }
}
