package shop.voenix.article.mug

import kotlinx.serialization.Serializable
import shop.voenix.validation.ValidationErrors

/**
 * One entry of the `mugVariants` array of a create or update request.
 *
 * [id] is what makes the array a diff rather than a list of new rows: an entry with an id updates
 * that variant, an entry without one inserts a variant, and a stored variant that the array does
 * not mention is deleted together with its example image. An id that belongs to another article is
 * rejected — the array can only address the variants of the article it is sent to.
 *
 * [exampleImageFilename] is the name a previous pre-upload returned; `null` means the variant has
 * no example image. Whether the file really exists is not a field rule, so the service checks it
 * while saving.
 */
@Serializable
internal data class MugVariantInput(
    val id: Long? = null,
    val name: String? = null,
    val insideColorCode: String? = null,
    val outsideColorCode: String? = null,
    val isDefault: Boolean = false,
    val active: Boolean = true,
    val exampleImageFilename: String? = null,
) {
    /** The field errors of this entry, keyed by its path inside the request body. */
    fun validate(index: Int): ValidationErrors = buildMap {
        if (id != null && id <= 0) {
            put("$MUG_VARIANTS_FIELD[$index].id", listOf("Id must be positive"))
        }
        requiredText(index, "name", "Name", name)
        requiredText(index, "insideColorCode", "InsideColorCode", insideColorCode)
        requiredText(index, "outsideColorCode", "OutsideColorCode", outsideColorCode)
    }

    fun normalized(): MugVariantInput =
        copy(
            name = checkNotNull(name).trim(),
            insideColorCode = checkNotNull(insideColorCode).trim(),
            outsideColorCode = checkNotNull(outsideColorCode).trim(),
            exampleImageFilename = exampleImageFilename?.trim()?.ifBlank { null },
        )

    private fun MutableMap<String, List<String>>.requiredText(
        index: Int,
        field: String,
        displayName: String,
        value: String?,
    ) {
        val key = "$MUG_VARIANTS_FIELD[$index].$field"
        when {
            value.isNullOrBlank() -> put(key, listOf("$displayName is required"))
            value.trim().length > MAXIMUM_TEXT_LENGTH ->
                put(key, listOf("$displayName must be at most $MAXIMUM_TEXT_LENGTH characters"))
        }
    }

    /**
     * Not private: kotlinx serialization resolves the serializer of a received body through this
     * companion, and a private one is not reachable reflectively.
     */
    companion object {
        const val MUG_VARIANTS_FIELD: String = "mugVariants"

        private const val MAXIMUM_TEXT_LENGTH = 255
    }
}
