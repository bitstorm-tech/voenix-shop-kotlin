package shop.voenix.promotion

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class PromotionInputValidationTest {
    @Test
    fun `valid inputs have no errors`() {
        val validInputs =
            listOf(
                PromotionInput(
                    name = " Winter sale ",
                    couponCode = " Winter10 ",
                    discountType = "PERCENTAGE",
                    discountValue = BigDecimal("100"),
                    startsAt = "2026-01-01T00:00:00Z",
                    endsAt = "2026-01-01T00:00:00Z",
                    usageLimitTotal = 1,
                    usageLimitPerUser = 1,
                    isActive = true,
                ),
                PromotionInput(
                    name = "Autumn sale",
                    couponCode = "Autumn5",
                    discountType = "FIXED_AMOUNT",
                    discountValue = BigDecimal("500.00"),
                ),
                PromotionInput(
                    name = "n".repeat(255),
                    couponCode = "c".repeat(64),
                    discountType = "FIXED_AMOUNT",
                    discountValue = BigDecimal("9999999999"),
                    startsAt = "2026-01-01T00:00:00+01:00",
                ),
            )

        validInputs.forEach { input -> assertTrue(input.validate().isEmpty()) }
    }

    @Test
    fun `invalid input returns every applicable field error`() {
        val cases =
            listOf(
                PromotionInput() to
                    linkedMapOf(
                        "name" to listOf("Name is required"),
                        "couponCode" to listOf("CouponCode is required"),
                        "discountType" to listOf("DiscountType is required"),
                        "discountValue" to listOf("DiscountValue is required"),
                    ),
                percentage(name = "   ", couponCode = "   ") to
                    linkedMapOf(
                        "name" to listOf("Name is required"),
                        "couponCode" to listOf("CouponCode is required"),
                    ),
                percentage(name = " ${"n".repeat(256)} ", couponCode = " ${"c".repeat(65)} ") to
                    linkedMapOf(
                        "name" to listOf("Name must be at most 255 characters"),
                        "couponCode" to listOf("CouponCode must be at most 64 characters"),
                    ),
                percentage(discountType = "INVALID") to
                    mapOf(
                        "discountType" to listOf("DiscountType must be PERCENTAGE or FIXED_AMOUNT")
                    ),
                percentage(discountValue = BigDecimal.ZERO) to
                    mapOf("discountValue" to listOf("DiscountValue must be positive")),
                percentage(discountValue = BigDecimal("-10")) to
                    mapOf("discountValue" to listOf("DiscountValue must be positive")),
                fixedAmount(discountValue = BigDecimal("-500")) to
                    mapOf("discountValue" to listOf("DiscountValue must be positive")),
                percentage(discountValue = BigDecimal("100.01")) to
                    mapOf(
                        "discountValue" to
                            listOf("DiscountValue must be at most 100 for percentage promotions")
                    ),
                percentage(discountValue = BigDecimal("10.005")) to
                    mapOf(
                        "discountValue" to
                            listOf(
                                "DiscountValue must have at most 2 decimal places for " +
                                    "percentage promotions"
                            )
                    ),
                fixedAmount(discountValue = BigDecimal("5.50")) to
                    mapOf(
                        "discountValue" to
                            listOf("DiscountValue must be whole cents for fixed amount promotions")
                    ),
                fixedAmount(discountValue = BigDecimal("10000000000")) to
                    mapOf(
                        "discountValue" to
                            listOf(
                                "DiscountValue must be at most 9999999999 for " +
                                    "fixed amount promotions"
                            )
                    ),
                percentage(startsAt = "not-a-timestamp", endsAt = "2026-13-01T00:00:00") to
                    linkedMapOf(
                        "startsAt" to listOf("StartsAt must be an ISO-8601 timestamp"),
                        "endsAt" to listOf("EndsAt must be an ISO-8601 timestamp"),
                    ),
                percentage(startsAt = "2026-03-01T00:00:00Z", endsAt = "2026-01-01T00:00:00Z") to
                    linkedMapOf(
                        "startsAt" to listOf("StartsAt must not be after EndsAt"),
                        "endsAt" to listOf("StartsAt must not be after EndsAt"),
                    ),
                percentage(usageLimitTotal = 0, usageLimitPerUser = -1) to
                    linkedMapOf(
                        "usageLimitTotal" to listOf("UsageLimitTotal must be positive"),
                        "usageLimitPerUser" to listOf("UsageLimitPerUser must be positive"),
                    ),
            )

        cases.forEach { (input, expected) -> assertEquals(expected, input.validate()) }
    }

    private fun percentage(
        name: String? = "Winter sale",
        couponCode: String? = "Winter10",
        discountType: String? = "PERCENTAGE",
        discountValue: BigDecimal? = BigDecimal("10.00"),
        startsAt: String? = null,
        endsAt: String? = null,
        usageLimitTotal: Int? = null,
        usageLimitPerUser: Int? = null,
    ): PromotionInput =
        PromotionInput(
            name = name,
            couponCode = couponCode,
            discountType = discountType,
            discountValue = discountValue,
            startsAt = startsAt,
            endsAt = endsAt,
            usageLimitTotal = usageLimitTotal,
            usageLimitPerUser = usageLimitPerUser,
        )

    private fun fixedAmount(discountValue: BigDecimal): PromotionInput =
        percentage(discountType = "FIXED_AMOUNT", discountValue = discountValue)
}
