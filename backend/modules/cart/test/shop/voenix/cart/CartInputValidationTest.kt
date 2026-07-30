package shop.voenix.cart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The complete field-rule matrix of the three request bodies the cart accepts. */
internal class CartInputValidationTest {
    @Test
    fun `a complete add is accepted`() {
        assertTrue(
            AddCartItemInput(
                    articleId = 10,
                    variantId = 20,
                    quantity = 1,
                    promptId = 5,
                    imageId = 7,
                )
                .validate()
                .isEmpty()
        )
    }

    @Test
    fun `an add without the optional references is accepted`() {
        assertTrue(
            AddCartItemInput(articleId = 10, variantId = 20, quantity = 99).validate().isEmpty()
        )
    }

    @Test
    fun `an empty add reports every required field once`() {
        assertEquals(
            setOf("articleId", "variantId", "quantity"),
            AddCartItemInput().validate().keys,
        )
    }

    @Test
    fun `identifiers must be positive, whether required or optional`() {
        val errors =
            AddCartItemInput(
                    articleId = 0,
                    variantId = -1,
                    quantity = 1,
                    promptId = 0,
                    imageId = -5,
                )
                .validate()

        assertEquals(setOf("articleId", "variantId", "promptId", "imageId"), errors.keys)
    }

    @Test
    fun `the add quantity has to be between one and ninety-nine`() {
        listOf(0, -1, 100, 1_000).forEach { quantity ->
            assertEquals(
                listOf("Quantity must be between 1 and 99"),
                AddCartItemInput(articleId = 1, variantId = 2, quantity = quantity)
                    .validate()["quantity"],
                "quantity $quantity",
            )
        }
    }

    @Test
    fun `the update quantity has the same range but no other field`() {
        assertTrue(CartQuantityInput(quantity = 1).validate().isEmpty())
        assertTrue(CartQuantityInput(quantity = 99).validate().isEmpty())
        assertEquals(setOf("quantity"), CartQuantityInput(quantity = 0).validate().keys)
        assertEquals(setOf("quantity"), CartQuantityInput(quantity = 100).validate().keys)
        assertEquals(
            listOf("Quantity is required"),
            CartQuantityInput().validate()["quantity"],
        )
    }

    @Test
    fun `a promotion code must be present, non-blank, and no longer than the column`() {
        assertTrue(PromotionCodeInput("SAVE10").validate().isEmpty())
        assertTrue(PromotionCodeInput("x".repeat(64)).validate().isEmpty())
        assertEquals(setOf("promotionCode"), PromotionCodeInput().validate().keys)
        assertEquals(setOf("promotionCode"), PromotionCodeInput("   ").validate().keys)
        assertEquals(setOf("promotionCode"), PromotionCodeInput("x".repeat(65)).validate().keys)
    }

    @Test
    fun `surrounding whitespace does not count towards the code length`() {
        assertTrue(PromotionCodeInput("  ${"x".repeat(64)}  ").validate().isEmpty())
    }
}
