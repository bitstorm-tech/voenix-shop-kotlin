package shop.voenix.article.tshirt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.descriptors.elementNames
import shop.voenix.article.PrintAspectRatio

/**
 * The field-rule matrix of the one t-shirt write there is: the shop's half of a synced article.
 *
 * Since ADR 0003 the garment belongs to the Spreadconnect backoffice, so the rules about a name, a
 * description, a colour, a size, and the three printer ids are gone with the fields they judged.
 * What is left is what the shop decides: where the shirt is filed, how its preview places a design,
 * which variant is the default one, and whether it may be shown at all.
 *
 * Two activation rules are deliberately *not* here. Whether a price exists and whether the partner
 * still lists the article can both be facts about the stored row, so the write path owns them;
 * `TshirtArticleAdminIntegrationTest` proves them.
 */
internal class TshirtArticleInputValidationTest {
    @Test
    fun `a draft with a frame and nothing else is accepted`() {
        assertEquals(emptyMap(), draft().validate())
    }

    @Test
    fun `a subcategory without a category is rejected`() {
        assertEquals(
            mapOf("subcategoryId" to listOf("SubcategoryId requires CategoryId")),
            draft().copy(subcategoryId = 5).validate(),
        )
    }

    @Test
    fun `ids that reference another row must be positive`() {
        assertEquals(
            mapOf(
                "categoryId" to listOf("CategoryId must be positive"),
                "subcategoryId" to listOf("SubcategoryId must be positive"),
                "defaultVariantId" to listOf("DefaultVariantId must be positive"),
            ),
            draft().copy(categoryId = 0, subcategoryId = -1, defaultVariantId = 0).validate(),
        )
    }

    /**
     * The frame is required for every shirt, active or not: its four columns are `NOT NULL`,
     * because a shirt whose preview cannot place a design is not a described shirt.
     */
    @Test
    fun `the print frame is required and each percentage is one`() {
        assertEquals(
            mapOf("printFrame" to listOf("PrintFrame is required")),
            draft().copy(printFrame = null).validate(),
        )
        assertEquals(
            mapOf(
                "printFrame.leftPct" to listOf("LeftPct is required"),
                "printFrame.topPct" to listOf("TopPct is required"),
                "printFrame.widthPct" to listOf("WidthPct is required"),
                "printFrame.heightPct" to listOf("HeightPct is required"),
            ),
            draft().copy(printFrame = PrintFrame()).validate(),
        )
        assertEquals(
            mapOf(
                "printFrame.leftPct" to listOf("LeftPct must be between 0 and 100"),
                "printFrame.heightPct" to listOf("HeightPct must be between 0 and 100"),
            ),
            draft().copy(printFrame = frame(left = -0.01, height = 100.01)).validate(),
        )
        // Both edges are legal: a frame may start at the very left and cover the whole mockup.
        assertEquals(
            emptyMap(),
            draft()
                .copy(printFrame = frame(left = 0.0, top = 0.0, width = 100.0, height = 100.0))
                .validate(),
        )
    }

    @Test
    fun `the print frame may not leave the mockup`() {
        assertEquals(
            mapOf(
                "printFrame.widthPct" to listOf("LeftPct plus WidthPct must be at most 100"),
                "printFrame.heightPct" to listOf("TopPct plus HeightPct must be at most 100"),
            ),
            draft()
                .copy(printFrame = frame(left = 60.0, top = 60.0, width = 40.01, height = 40.01))
                .validate(),
        )
    }

    /**
     * The percentages are checked in the form they will be *stored* — two decimals — so that a
     * frame this validator accepts can never be rejected by the `numeric(5, 2)` CHECK afterwards.
     */
    @Test
    fun `the checked percentages are the stored ones`() {
        assertEquals(
            listOf("LeftPct plus WidthPct must be at most 100"),
            draft()
                .copy(printFrame = frame(left = 49.995, width = 50.005))
                .validate()["printFrame.widthPct"],
        )
    }

    @Test
    fun `an active shirt needs a default variant and a category`() {
        assertEquals(
            listOf("An active article requires an active default variant"),
            draft().copy(active = true, categoryId = 1).validate()["active"],
        )
        assertEquals(
            listOf("An active article requires a category"),
            draft().copy(active = true, defaultVariantId = 7).validate()["active"],
        )
        assertEquals(
            emptyMap(),
            draft().copy(active = true, categoryId = 1, defaultVariantId = 7).validate(),
        )
    }

    /** The ratio is submitted as text, so an unsupported one is a field error like every other. */
    @Test
    fun `the print aspect ratio must be one this shop prints`() {
        assertEquals(
            mapOf("printAspectRatio" to listOf("PrintAspectRatio must be one of 16:9, 1:1")),
            draft().copy(printAspectRatio = "4:3").validate(),
        )
        assertEquals(emptyMap(), draft().copy(printAspectRatio = " 16:9 ").validate())
        assertEquals(
            PrintAspectRatio.WIDE_16_9,
            draft().copy(printAspectRatio = " 16:9 ").printFormat,
        )
        // A shirt that says nothing is printed the way a shirt is printed: square.
        assertEquals(PrintAspectRatio.SQUARE, draft().printFormat)
    }

    /**
     * The write contract is the shop's half of the article and nothing else. Everything the sync
     * owns is absent, and so are the two fields that were never anybody's to send: the display
     * position is decided by the module, and a price id is never accepted.
     */
    @Test
    fun `the write contract carries no field the sync owns`() {
        val fields = TshirtArticleInput.serializer().descriptor.elementNames.toSet()

        assertEquals(
            setOf(
                "active",
                "categoryId",
                "subcategoryId",
                "printAspectRatio",
                "printFrame",
                "defaultVariantId",
                "price",
            ),
            fields,
        )
        assertFalse("printFormat" in fields)
        assertTrue("printAspectRatio" in fields)
    }

    private fun draft(): TshirtArticleInput = TshirtArticleInput(printFrame = frame())

    private fun frame(
        left: Double = 25.0,
        top: Double = 20.0,
        width: Double = 50.0,
        height: Double = 40.0,
    ): PrintFrame = PrintFrame(leftPct = left, topPct = top, widthPct = width, heightPct = height)
}
