package shop.voenix.cart

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

/**
 * What a customer sends to put one line into their cart.
 *
 * Every field is nullable although three of them are required: a missing `articleId` has to reach
 * [validate] and become a field error instead of failing deserialization with a serializer message
 * no client can act on.
 *
 * The image is referenced by id, never uploaded here. `POST /api/cart/images` stores the file
 * first, so a rejected add never leaves a file behind and this request stays plain JSON.
 */
@Serializable
internal data class AddCartItemInput(
    val articleId: Long? = null,
    val variantId: Long? = null,
    val quantity: Int? = null,
    val promptId: Long? = null,
    val imageId: Long? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        validateIdentifier("articleId", "ArticleId", articleId, required = true)
        validateIdentifier("variantId", "VariantId", variantId, required = true)
        validateIdentifier("promptId", "PromptId", promptId, required = false)
        validateIdentifier("imageId", "ImageId", imageId, required = false)
        when {
            quantity == null -> put("quantity", listOf("Quantity is required"))
            quantity !in 1..MAXIMUM_LINE_QUANTITY ->
                put(
                    "quantity",
                    listOf("Quantity must be between 1 and $MAXIMUM_LINE_QUANTITY"),
                )
        }
    }

    private fun MutableMap<String, List<String>>.validateIdentifier(
        field: String,
        displayName: String,
        value: Long?,
        required: Boolean,
    ) {
        when {
            value == null -> if (required) put(field, listOf("$displayName is required"))
            value <= 0 -> put(field, listOf("$displayName must be positive"))
        }
    }
}
