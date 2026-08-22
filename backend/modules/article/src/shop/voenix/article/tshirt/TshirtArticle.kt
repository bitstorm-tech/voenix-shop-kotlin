package shop.voenix.article.tshirt

import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.serialization.Serializable
import shop.voenix.article.PrintAspectRatio
import shop.voenix.pricing.CalculatedPrice
import shop.voenix.validation.ValidationErrors
import shop.voenix.validation.ValidationErrorsBuilder
import shop.voenix.validation.buildValidationErrors

/**
 * The single admin representation of a t-shirt: what create, update, list detail, and reorder
 * answer with.
 *
 * It is [shop.voenix.article.mug.MugArticle] read a second time, and it leaves out the same two
 * fields for the same reasons: `articleType` would say `"TSHIRT"` on every row of a route that only
 * serves shirts, and a `priceId` would duplicate what the embedded price already carries.
 *
 * [position] is response-only. Create appends behind the last shirt, delete closes the gap, and the
 * reorder route moves one shirt to the place of another.
 *
 * Where a mug carries its measurements, a shirt carries two things a mug has no use for: the
 * [printFrame] the preview places the generated design in, and the [sizeChartImageFilename] an
 * admin uploads so a customer can pick a size. Both are properties of the article, not of a variant
 * — every variant of one shirt is printed in the same rectangle and measured by the same chart.
 */
@Serializable
internal data class TshirtArticle(
    val id: Long,
    val position: Int,
    val name: String,
    val descriptionShort: String,
    val descriptionLong: String,
    val active: Boolean,
    val categoryId: Long?,
    val subcategoryId: Long?,
    val supplierId: Long?,
    val printAspectRatio: PrintAspectRatio,
    val sizeChartImageFilename: String?,
    val printFrame: PrintFrame,
    val tshirtVariants: List<TshirtVariant>,
    val price: CalculatedPrice?,
)

/**
 * The rectangle of the product mockup the generated design is placed in, in percent of the mockup.
 *
 * One type serves both directions of the contract, exactly like `MugDetails` does for a mug: a
 * request submits the four percentages and a response answers with the four stored ones. The four
 * are nullable so that an omitted percentage is a precise field error instead of a body that fails
 * to parse; a stored frame always carries all four, because the columns are `NOT NULL`.
 *
 * The stored form is [BigDecimal] with two decimals, because the columns are `numeric(5, 2)` and a
 * binary floating-point type cannot carry two decimals back unchanged. The rounding happens *here*
 * rather than in the repository, so that the numbers the rules below check are exactly the numbers
 * the database will store — otherwise a frame whose raw sum is 100 could round to 100.01 and turn a
 * field error into a CHECK violation.
 */
@Serializable
internal data class PrintFrame(
    val leftPct: Double? = null,
    val topPct: Double? = null,
    val widthPct: Double? = null,
    val heightPct: Double? = null,
) {
    /** The stored value of [leftPct]. Only meaningful once [validate] reported nothing. */
    val left: BigDecimal
        get() = scaled(checkNotNull(leftPct))

    /** The stored value of [topPct]. Only meaningful once [validate] reported nothing. */
    val top: BigDecimal
        get() = scaled(checkNotNull(topPct))

    /** The stored value of [widthPct]. Only meaningful once [validate] reported nothing. */
    val width: BigDecimal
        get() = scaled(checkNotNull(widthPct))

    /** The stored value of [heightPct]. Only meaningful once [validate] reported nothing. */
    val height: BigDecimal
        get() = scaled(checkNotNull(heightPct))

    /** The field errors of this frame, keyed by its path inside the request body. */
    fun validate(): ValidationErrors = buildValidationErrors {
        percentage("leftPct", "LeftPct", leftPct)
        percentage("topPct", "TopPct", topPct)
        percentage("widthPct", "WidthPct", widthPct)
        percentage("heightPct", "HeightPct", heightPct)
        addExtentErrors()
    }

    /**
     * A percentage is required and lies between 0 and 100. Both edges are legal: a frame may start
     * at the very left of the mockup, and it may cover the whole of it.
     */
    private fun ValidationErrorsBuilder.percentage(
        field: String,
        displayName: String,
        value: Double?,
    ) {
        when {
            value == null -> add("$FIELD_PREFIX.$field", "$displayName is required")
            storable(value) == null ->
                add("$FIELD_PREFIX.$field", "$displayName must be between 0 and 100")
        }
    }

    /**
     * The frame is a rectangle *inside* the mockup, so neither edge may leave it. The rule is
     * reported on the extent rather than on the offset, because the offset is where an admin placed
     * the frame and the extent is what makes it fit.
     */
    private fun ValidationErrorsBuilder.addExtentErrors() {
        val left = leftPct?.let(::storable)
        val top = topPct?.let(::storable)
        val width = widthPct?.let(::storable)
        val height = heightPct?.let(::storable)
        if (left != null && width != null && left + width > HUNDRED) {
            add("$FIELD_PREFIX.widthPct", "LeftPct plus WidthPct must be at most 100")
        }
        if (top != null && height != null && top + height > HUNDRED) {
            add("$FIELD_PREFIX.heightPct", "TopPct plus HeightPct must be at most 100")
        }
    }

    /** [value] as it would be stored, or `null` when it is not a percentage at all. */
    private fun storable(value: Double): BigDecimal? =
        scaled(value).takeIf { stored -> stored in ZERO..HUNDRED }

    /**
     * Not private: kotlinx serialization resolves the serializer of a received body through this
     * companion, and a private one is not reachable reflectively.
     */
    companion object {
        private const val FIELD_PREFIX = "printFrame"
        private const val STORED_SCALE = 2

        private val ZERO: BigDecimal = BigDecimal.ZERO
        private val HUNDRED: BigDecimal = BigDecimal(100)

        private fun scaled(value: Double): BigDecimal =
            BigDecimal.valueOf(value).setScale(STORED_SCALE, RoundingMode.HALF_UP)
    }
}

/**
 * One stored variant of a t-shirt: a colour in a size, and the printable product those two name at
 * the print-on-demand partner.
 *
 * It is not the same type as [TshirtVariantInput], for the reason the mug slice separates its two:
 * the id always exists here, while its absence in a request is what asks for a new variant.
 *
 * [name] is composed rather than stored — `"Black / M"` — and it is composed in exactly one place,
 * `tshirtVariantName` in the persistence package, so the admin list, the storefront, the exported
 * catalog, and an order line cannot spell a variant three different ways.
 *
 * Variants come back with the default first and are otherwise ordered by colour and size, so a
 * client never has to sort them to show the variant a customer sees first.
 */
@Serializable
internal data class TshirtVariant(
    val id: Long,
    val name: String,
    val colorName: String,
    val colorHex: String,
    val sizeLabel: String,
    val spodProductTypeId: Long,
    val spodAppearanceId: Long,
    val spodSizeId: Long,
    val isDefault: Boolean,
    val active: Boolean,
    val exampleImageFilename: String?,
)

/**
 * One row of the admin t-shirt list.
 *
 * It carries the same twelve fields the mug list row carries, and for the same reasons: the
 * overview table needs what an article *references* spelled out — the names of its category, its
 * subcategory, and its supplier — and it needs neither the descriptions, nor the frame, nor the
 * variants, nor the calculated price that [TshirtArticle] carries.
 *
 * [exampleImageFilename] is the picture the table shows: the image of the default variant, or —
 * when the default has none — the first variant that has one, by id.
 */
@Serializable
internal data class TshirtArticleListItem(
    val id: Long,
    val position: Int,
    val name: String,
    val active: Boolean,
    val categoryId: Long?,
    val categoryName: String?,
    val subcategoryId: Long?,
    val subcategoryName: String?,
    val supplierId: Long?,
    val supplierName: String?,
    val variantCount: Int,
    val exampleImageFilename: String?,
)
