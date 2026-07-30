package shop.voenix.cart

import kotlinx.serialization.Serializable
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

/**
 * The body of `PATCH /api/cart/items/{itemId}`: the new quantity of one line.
 *
 * It is its own type rather than a reused [AddCartItemInput] with everything but the quantity left
 * out. The two contracts differ in what they *require*, and a shared type would have to accept a
 * quantity update that also silently carries an article id.
 */
@Serializable
internal data class CartQuantityInput(val quantity: Int? = null) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        when {
            quantity == null -> put("quantity", listOf("Quantity is required"))
            quantity !in 1..MAXIMUM_LINE_QUANTITY ->
                put(
                    "quantity",
                    listOf("Quantity must be between 1 and $MAXIMUM_LINE_QUANTITY"),
                )
        }
    }
}
