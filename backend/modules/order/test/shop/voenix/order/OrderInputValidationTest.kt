package shop.voenix.order

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The complete field-rule matrix of [PlaceOrderInput], proven once and without a database.
 *
 * These rules are the last line before an order becomes an immutable record. Two of them are worth
 * naming: an order must have someone to show it to, which is the same rule as the owner CHECK on
 * the table, and its money must describe its own lines, which nothing in the database checks at
 * all.
 */
internal class OrderInputValidationTest {
    @Test
    fun `a complete placement has no errors`() {
        assertEquals(emptyMap(), OrderTestSupport.placeOrderInput().validate())
    }

    @Test
    fun `a placement without a billing address is complete, because shipping stands in`() {
        val input = OrderTestSupport.placeOrderInput(billingAddress = null)

        assertEquals(emptyMap(), input.validate())
        assertEquals(input.shippingAddress, input.effectiveBillingAddress)
    }

    @Test
    fun `a billing address of its own is validated too`() {
        val input =
            OrderTestSupport.placeOrderInput(
                billingAddress = OrderTestSupport.address(firstName = " ")
            )

        assertEquals(
            listOf("Must not be blank"),
            input.validate()["billingAddress.firstName"],
        )
        assertEquals(
            input.billingAddress,
            input.effectiveBillingAddress,
            "A given billing address must not be replaced by the shipping one",
        )
    }

    @Test
    fun `every address field is required and bounded`() {
        val blank =
            PlaceOrderInput.Address(
                firstName = "",
                lastName = " ",
                street = "",
                houseNumber = "",
                postalCode = "",
                city = "",
                country = "DE",
            )

        assertEquals(
            listOf(
                "shippingAddress.firstName",
                "shippingAddress.lastName",
                "shippingAddress.street",
                "shippingAddress.houseNumber",
                "shippingAddress.postalCode",
                "shippingAddress.city",
            ),
            blank.validate("shippingAddress").keys.toList(),
        )

        val tooLong = OrderTestSupport.address(firstName = "a".repeat(101), city = "b".repeat(101))
        assertEquals(
            listOf("Must be at most 100 characters"),
            tooLong.validate("shippingAddress")["shippingAddress.firstName"],
        )
        assertEquals(
            listOf("Must be at most 100 characters"),
            tooLong.validate("shippingAddress")["shippingAddress.city"],
        )
    }

    @Test
    fun `the country is a two-letter code`() {
        listOf("D", "DEU", "D1", "").forEach { country ->
            assertEquals(
                listOf("Country must be a two-letter code"),
                OrderTestSupport.address(country = country)
                    .validate("shippingAddress")["shippingAddress.country"],
                "Country '$country' must be refused",
            )
        }
        assertTrue(OrderTestSupport.address(country = "at").validate("shippingAddress").isEmpty())
    }

    @Test
    fun `an order needs a guest token or a user`() {
        val orphan = OrderTestSupport.placeOrderInput(userId = null, guestToken = null)

        assertEquals(
            listOf("An order needs a guest token or a user"),
            orphan.validate()["guestToken"],
        )
        assertTrue(
            OrderTestSupport.placeOrderInput(userId = OrderTestSupport.USER_ID, guestToken = null)
                .validate()
                .isEmpty()
        )
        assertEquals(
            listOf("GuestToken must not be blank"),
            OrderTestSupport.placeOrderInput(guestToken = " ").validate()["guestToken"],
        )
    }

    @Test
    fun `every identifier must be positive`() {
        assertEquals(
            listOf("CartId must be positive"),
            OrderTestSupport.placeOrderInput(cartId = 0).validate()["cartId"],
        )
        assertEquals(
            listOf("UserId must be positive"),
            OrderTestSupport.placeOrderInput(userId = -1).validate()["userId"],
        )
        assertEquals(
            listOf("PromotionId must be positive"),
            OrderTestSupport.placeOrderInput(promotionId = 0).validate()["promotionId"],
        )
        val line =
            OrderTestSupport.line(articleId = 0, variantId = -2, promptId = 0, printImageId = 0)
        val errors = OrderTestSupport.placeOrderInput(lines = listOf(line)).validate()
        assertEquals(listOf("Must be positive"), errors["lines[0].articleId"])
        assertEquals(listOf("Must be positive"), errors["lines[0].variantId"])
        assertEquals(listOf("PromptId must be positive"), errors["lines[0].promptId"])
        assertEquals(listOf("ImageId must be positive"), errors["lines[0].imageId"])
    }

    @Test
    fun `the address must be reachable`() {
        assertEquals(
            listOf("Email is required"),
            OrderTestSupport.placeOrderInput(email = "  ").validate()["email"],
        )
        assertEquals(
            listOf("Email is not a valid address"),
            OrderTestSupport.placeOrderInput(email = "customer@example").validate()["email"],
        )
        assertEquals(
            listOf("Email must be at most 255 characters"),
            OrderTestSupport.placeOrderInput(email = "a".repeat(250) + "@example.com")
                .validate()["email"],
        )
        assertTrue(
            OrderTestSupport.placeOrderInput(email = "a.b+tag@sub.example.co.uk")
                .validate()
                .isEmpty()
        )
    }

    @Test
    fun `a phone number is optional but not empty`() {
        assertTrue(OrderTestSupport.placeOrderInput().copy(phone = null).validate().isEmpty())
        assertEquals(
            listOf("Phone must not be blank"),
            OrderTestSupport.placeOrderInput().copy(phone = " ").validate()["phone"],
        )
        assertEquals(
            listOf("Phone must be at most 50 characters"),
            OrderTestSupport.placeOrderInput().copy(phone = "1".repeat(51)).validate()["phone"],
        )
    }

    @Test
    fun `no amount may be negative`() {
        assertEquals(
            listOf("Subtotal must not be negative"),
            OrderTestSupport.placeOrderInput(subtotalCents = -1).validate()["subtotalCents"],
        )
        assertEquals(
            listOf("Shipping cost must not be negative"),
            OrderTestSupport.placeOrderInput(shippingCostCents = -1)
                .validate()["shippingCostCents"],
        )
        assertEquals(
            listOf("Discount must not be negative"),
            OrderTestSupport.placeOrderInput(discountCents = -1).validate()["discountCents"],
        )
    }

    @Test
    fun `the discount cannot exceed what is being paid`() {
        assertEquals(
            listOf("The discount cannot exceed the order"),
            OrderTestSupport.placeOrderInput(discountCents = 4_471).validate()["discountCents"],
        )
        // A hundred-percent coupon: everything but the shipping is discounted away, and that is a
        // valid order — the schema stores total = 490 for it.
        val free = OrderTestSupport.placeOrderInput(discountCents = 3_980)
        assertTrue(free.validate().isEmpty())
        assertEquals(490, free.totalCents)
    }

    @Test
    fun `both discount rules report when both are broken`() {
        // A negative discount that still leaves a negative total: the discount is invalid on its
        // own *and* it does not fit the order. Neither message hides the other.
        val input = OrderTestSupport.placeOrderInput(subtotalCents = -10_000, discountCents = -1)

        assertEquals(
            listOf("Discount must not be negative", "The discount cannot exceed the order"),
            input.validate()["discountCents"],
        )
    }

    @Test
    fun `the subtotal must be the sum of the ordered lines`() {
        val input = OrderTestSupport.placeOrderInput(subtotalCents = 3_979)

        assertEquals(
            listOf("Subtotal must be the sum of the ordered lines"),
            input.validate()["subtotalCents"],
        )
        // Two lines of two units at 1490 + 500 each.
        val two =
            OrderTestSupport.placeOrderInput(
                subtotalCents = 7_960,
                lines = listOf(OrderTestSupport.line(), OrderTestSupport.line()),
            )
        assertTrue(two.validate().isEmpty())
    }

    @Test
    fun `an order needs at least one line`() {
        assertEquals(
            listOf("An order needs at least one line"),
            OrderTestSupport.placeOrderInput(subtotalCents = 0, lines = emptyList())
                .validate()["lines"],
        )
    }

    @Test
    fun `a line quantity outside one to ninety-nine is refused`() {
        listOf(0, -1, 100).forEach { quantity ->
            val input =
                OrderTestSupport.placeOrderInput(
                    lines = listOf(OrderTestSupport.line(quantity = quantity))
                )
            assertEquals(
                listOf("Quantity must be between 1 and 99"),
                input.validate()["lines[0].quantity"],
                "Quantity $quantity must be refused",
            )
        }
    }

    @Test
    fun `a line price below zero is refused`() {
        val input =
            OrderTestSupport.placeOrderInput(
                subtotalCents = 0,
                lines = listOf(OrderTestSupport.line(priceCents = -1, promptPriceCents = -1)),
            )

        val errors = input.validate()
        assertEquals(listOf("Price must not be negative"), errors["lines[0].price"])
        assertEquals(listOf("Prompt price must not be negative"), errors["lines[0].promptPrice"])
    }

    @Test
    fun `every failing line is reported under its own position`() {
        val input =
            OrderTestSupport.placeOrderInput(
                lines =
                    listOf(
                        OrderTestSupport.line(),
                        OrderTestSupport.line(quantity = 0),
                        OrderTestSupport.line(articleId = -1),
                    )
            )

        val errors = input.validate()
        assertEquals(
            setOf("lines[1].quantity", "lines[2].articleId"),
            errors.keys.filter { key -> key.startsWith("lines[") }.toSet(),
        )
    }
}
