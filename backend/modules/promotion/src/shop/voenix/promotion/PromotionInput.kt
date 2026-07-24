package shop.voenix.promotion

import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import shop.voenix.json.BigDecimalJsonNumberSerializer
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

/**
 * The shared create/update input. Create and update accept the same fields with the same rules, so
 * a single input type carries the one `validate()` implementation of the field-rule matrix.
 */
@Serializable
internal data class PromotionInput(
    val name: String? = null,
    val couponCode: String? = null,
    val discountType: String? = null,
    @Serializable(with = BigDecimalJsonNumberSerializer::class)
    val discountValue: BigDecimal? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
    val usageLimitTotal: Int? = null,
    val usageLimitPerUser: Int? = null,
    val isActive: Boolean = false,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        validateRequiredText("name", "Name", name, MAXIMUM_NAME_LENGTH)
        validateRequiredText("couponCode", "CouponCode", couponCode, MAXIMUM_COUPON_CODE_LENGTH)
        validateDiscountType()
        validateDiscountValue()
        validateDateWindow()
        validatePositiveLimit("usageLimitTotal", "UsageLimitTotal", usageLimitTotal)
        validatePositiveLimit("usageLimitPerUser", "UsageLimitPerUser", usageLimitPerUser)
    }

    /**
     * Whether this input leaves every configuration field of [promotion] as it is, so that at most
     * [isActive] differs. A redeemed promotion is locked against configuration changes, but an
     * administrator may still activate or deactivate it.
     *
     * Timestamps are compared as instants and the discount value by numeric value, so that
     * `2026-01-01T01:00:00+01:00` and `10` describe the same configuration as the stored
     * `2026-01-01T00:00:00Z` and `10.00`.
     */
    fun changesOnlyActivationOf(promotion: Promotion): Boolean =
        name == promotion.name &&
            couponCode == promotion.couponCode &&
            discountType == promotion.discount.discountType &&
            discountValue?.compareTo(promotion.discount.value) == 0 &&
            startsAt.toInstant() == promotion.startsAt.toInstant() &&
            endsAt.toInstant() == promotion.endsAt.toInstant() &&
            usageLimitTotal == promotion.usageLimitTotal &&
            usageLimitPerUser == promotion.usageLimitPerUser

    /** Parses a timestamp that [validate] has already accepted. */
    private fun String?.toInstant(): Instant? = this?.let(Instant::parse)

    private fun MutableMap<String, List<String>>.validateRequiredText(
        field: String,
        displayName: String,
        value: String?,
        maximumLength: Int,
    ) {
        if (value.isNullOrBlank()) {
            put(field, listOf("$displayName is required"))
        } else if (value.trim().length > maximumLength) {
            put(field, listOf("$displayName must be at most $maximumLength characters"))
        }
    }

    private fun MutableMap<String, List<String>>.validateDiscountType() {
        if (discountType == null) {
            put("discountType", listOf("DiscountType is required"))
        } else if (
            discountType != DISCOUNT_TYPE_PERCENTAGE && discountType != DISCOUNT_TYPE_FIXED_AMOUNT
        ) {
            put("discountType", listOf("DiscountType must be PERCENTAGE or FIXED_AMOUNT"))
        }
    }

    private fun MutableMap<String, List<String>>.validateDiscountValue() {
        when {
            discountValue == null -> put("discountValue", listOf("DiscountValue is required"))
            discountValue <= BigDecimal.ZERO ->
                put("discountValue", listOf("DiscountValue must be positive"))
            discountType == DISCOUNT_TYPE_PERCENTAGE &&
                discountValue > MAXIMUM_PERCENTAGE_DISCOUNT ->
                put(
                    "discountValue",
                    listOf("DiscountValue must be at most 100 for percentage promotions"),
                )
            discountType == DISCOUNT_TYPE_PERCENTAGE &&
                discountValue.stripTrailingZeros().scale() > PERCENTAGE_DISCOUNT_SCALE ->
                put(
                    "discountValue",
                    listOf(
                        "DiscountValue must have at most 2 decimal places for " +
                            "percentage promotions"
                    ),
                )
            discountType == DISCOUNT_TYPE_FIXED_AMOUNT &&
                discountValue.stripTrailingZeros().scale() > 0 ->
                put(
                    "discountValue",
                    listOf("DiscountValue must be whole cents for fixed amount promotions"),
                )
            discountType == DISCOUNT_TYPE_FIXED_AMOUNT &&
                discountValue > MAXIMUM_FIXED_AMOUNT_CENTS ->
                put(
                    "discountValue",
                    listOf(
                        "DiscountValue must be at most $MAXIMUM_FIXED_AMOUNT_CENTS for " +
                            "fixed amount promotions"
                    ),
                )
        }
    }

    private fun MutableMap<String, List<String>>.validateDateWindow() {
        val starts = validateTimestamp("startsAt", "StartsAt", startsAt)
        val ends = validateTimestamp("endsAt", "EndsAt", endsAt)
        if (starts != null && ends != null && starts > ends) {
            put("startsAt", listOf("StartsAt must not be after EndsAt"))
            put("endsAt", listOf("StartsAt must not be after EndsAt"))
        }
    }

    private fun MutableMap<String, List<String>>.validateTimestamp(
        field: String,
        displayName: String,
        value: String?,
    ): Instant? {
        if (value == null) return null
        return try {
            Instant.parse(value)
        } catch (_: DateTimeParseException) {
            put(field, listOf("$displayName must be an ISO-8601 timestamp"))
            null
        }
    }

    private fun MutableMap<String, List<String>>.validatePositiveLimit(
        field: String,
        displayName: String,
        value: Int?,
    ) {
        if (value != null && value <= 0) {
            put(field, listOf("$displayName must be positive"))
        }
    }

    companion object {
        private const val MAXIMUM_NAME_LENGTH = 255
        private const val MAXIMUM_COUPON_CODE_LENGTH = 64
        private const val PERCENTAGE_DISCOUNT_SCALE = 2
        private val MAXIMUM_PERCENTAGE_DISCOUNT = BigDecimal(100)
        private val MAXIMUM_FIXED_AMOUNT_CENTS = BigDecimal(9_999_999_999L)
    }
}
