package shop.voenix.article.tshirt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.descriptors.elementNames
import shop.voenix.article.PrintAspectRatio

/**
 * The field-rule matrix of a t-shirt: the mug matrix adapted to what a shirt really is.
 *
 * Four rules have no counterpart in the mug slice, and each of them comes from the shirt's own
 * shape: the print frame is required and has to fit inside the mockup, a variant's colour is a hex
 * code, a variant carries the three ids its printable product is named by, and every variant of one
 * shirt names the same SPOD product type — the rule the council round of issue #205 decided to keep
 * in the input rather than in the schema.
 *
 * The rule "an active shirt needs a price" is deliberately *not* here. Whether a price exists can
 * be a fact about the stored article, so the write path owns it;
 * `TshirtArticleAdminIntegrationTest` proves it.
 */
internal class TshirtArticleInputValidationTest {
    @Test
    fun `a draft with a frame and no variants is accepted`() {
        assertEquals(emptyMap(), draft().validate())
    }

    @Test
    fun `name and both descriptions are required`() {
        val errors =
            TshirtArticleInput(
                    name = " ",
                    descriptionShort = "",
                    descriptionLong = "",
                    printFrame = frame(),
                )
                .validate()

        assertEquals(
            mapOf(
                "name" to listOf("Name is required"),
                "descriptionShort" to listOf("DescriptionShort is required"),
                "descriptionLong" to listOf("DescriptionLong is required"),
            ),
            errors,
        )
    }

    @Test
    fun `every text has the length its column has`() {
        val errors =
            draft()
                .copy(
                    name = "a".repeat(256),
                    descriptionShort = "a".repeat(1001),
                    descriptionLong = "a".repeat(5001),
                    tshirtVariants =
                        listOf(
                            variant().copy(colorName = "a".repeat(65), sizeLabel = "a".repeat(65))
                        ),
                )
                .validate()

        assertEquals(
            mapOf(
                "name" to listOf("Name must be at most 255 characters"),
                "descriptionShort" to listOf("DescriptionShort must be at most 1000 characters"),
                "descriptionLong" to listOf("DescriptionLong must be at most 5000 characters"),
                "tshirtVariants[0].colorName" to listOf("ColorName must be at most 64 characters"),
                "tshirtVariants[0].sizeLabel" to listOf("SizeLabel must be at most 64 characters"),
            ),
            errors,
        )
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
                "supplierId" to listOf("SupplierId must be positive"),
                "tshirtVariants[0].id" to listOf("Id must be positive"),
            ),
            draft()
                .copy(
                    categoryId = 0,
                    subcategoryId = -1,
                    supplierId = 0,
                    tshirtVariants = listOf(variant().copy(id = 0)),
                )
                .validate(),
        )
    }

    /**
     * The frame is required for every shirt, not only for an active one: its four columns are `NOT
     * NULL`, because a shirt whose preview cannot place a design is not a described shirt.
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
    fun `an active shirt needs an active variant and a category`() {
        assertEquals(
            listOf("An active article requires at least one active variant"),
            draft()
                .copy(
                    active = true,
                    categoryId = 1,
                    tshirtVariants = listOf(variant().copy(active = false)),
                )
                .validate()["active"],
        )
        assertEquals(
            listOf("An active article requires a category"),
            draft().copy(active = true, tshirtVariants = listOf(variant())).validate()["active"],
        )
        assertEquals(
            emptyMap(),
            draft()
                .copy(active = true, categoryId = 1, tshirtVariants = listOf(variant()))
                .validate(),
        )
    }

    @Test
    fun `a non-empty variant array needs exactly one default`() {
        assertEquals(
            listOf("Exactly one variant must be marked as default"),
            draft()
                .copy(tshirtVariants = listOf(variant().copy(isDefault = false)))
                .validate()["tshirtVariants"],
        )
        assertEquals(
            listOf("Exactly one variant must be marked as default"),
            draft()
                .copy(
                    tshirtVariants =
                        listOf(variant(), variant().copy(sizeLabel = "L", spodSizeId = 92))
                )
                .validate()["tshirtVariants"],
        )
    }

    @Test
    fun `the variant array may not address the same variant twice`() {
        assertEquals(
            listOf("Variant ids must be unique"),
            draft()
                .copy(
                    tshirtVariants =
                        listOf(
                            variant().copy(id = 7),
                            variant()
                                .copy(id = 7, sizeLabel = "L", spodSizeId = 92, isDefault = false),
                        )
                )
                .validate()["tshirtVariants"],
        )
    }

    /** The unique rule of the table, reported as a field error instead of a `23505`. */
    @Test
    fun `one color and size combination may appear only once`() {
        assertEquals(
            listOf("Each color and size combination must appear only once"),
            draft()
                .copy(
                    tshirtVariants =
                        listOf(
                            variant(),
                            variant().copy(isDefault = false, sizeLabel = " M ", spodSizeId = 92),
                        )
                )
                .validate()["tshirtVariants"],
        )
    }

    /**
     * The second unique rule of the table, seen from the printer: two variants that resolve to the
     * same SPOD product are the same garment under two names, and the client hears which rule it
     * broke instead of a `23505` it cannot act on.
     */
    @Test
    fun `one SPOD product combination may appear only once`() {
        assertEquals(
            listOf("Each SPOD product type, appearance and size combination must appear only once"),
            draft()
                .copy(
                    tshirtVariants =
                        listOf(
                            variant(),
                            variant()
                                .copy(isDefault = false, colorName = "Schwarz", sizeLabel = "L"),
                        )
                )
                .validate()["tshirtVariants"],
        )
    }

    @Test
    fun `a variant needs its colour, its size, and its three printer ids`() {
        assertEquals(
            mapOf(
                "tshirtVariants[0].colorName" to listOf("ColorName is required"),
                "tshirtVariants[0].sizeLabel" to listOf("SizeLabel is required"),
                "tshirtVariants[0].colorHex" to listOf("ColorHex is required"),
                "tshirtVariants[0].spodProductTypeId" to listOf("SpodProductTypeId is required"),
                "tshirtVariants[0].spodAppearanceId" to listOf("SpodAppearanceId is required"),
                "tshirtVariants[0].spodSizeId" to listOf("SpodSizeId is required"),
            ),
            draft()
                .copy(
                    tshirtVariants =
                        listOf(
                            TshirtVariantInput(colorName = " ", sizeLabel = "", isDefault = true)
                        )
                )
                .validate(),
        )
        assertEquals(
            mapOf(
                "tshirtVariants[0].spodProductTypeId" to
                    listOf("SpodProductTypeId must be positive"),
                "tshirtVariants[0].spodAppearanceId" to listOf("SpodAppearanceId must be positive"),
                "tshirtVariants[0].spodSizeId" to listOf("SpodSizeId must be positive"),
            ),
            draft()
                .copy(
                    tshirtVariants =
                        listOf(
                            variant()
                                .copy(
                                    spodProductTypeId = 0,
                                    spodAppearanceId = -1,
                                    spodSizeId = 0,
                                )
                        )
                )
                .validate(),
        )
    }

    @Test
    fun `a variant colour is a six-digit hex code`() {
        listOf("000000", "#fff", "#12345g", "#1234567").forEach { submitted ->
            assertEquals(
                listOf("ColorHex must be a six-digit hex color such as #1a2b3c"),
                draft()
                    .copy(tshirtVariants = listOf(variant().copy(colorHex = submitted)))
                    .validate()["tshirtVariants[0].colorHex"],
                "Expected $submitted to be rejected",
            )
        }
        assertEquals(
            emptyMap(),
            draft()
                .copy(tshirtVariants = listOf(variant().copy(colorHex = " #A1b2C3 ")))
                .validate(),
        )
    }

    /**
     * The schema keeps the three printer ids generic on purpose, so the rule that one shirt is one
     * garment lives here.
     */
    @Test
    fun `every variant of one shirt names the same product type`() {
        assertEquals(
            listOf("All variants must share the same SpodProductTypeId"),
            draft()
                .copy(
                    tshirtVariants =
                        listOf(
                            variant(),
                            variant()
                                .copy(
                                    isDefault = false,
                                    sizeLabel = "L",
                                    spodProductTypeId = 813,
                                ),
                        )
                )
                .validate()["tshirtVariants"],
        )
        assertEquals(
            emptyMap(),
            draft()
                .copy(
                    tshirtVariants =
                        listOf(
                            variant(),
                            variant().copy(isDefault = false, sizeLabel = "L", spodSizeId = 92),
                        )
                )
                .validate(),
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

    @Test
    fun `normalization trims what is written and turns blank optional texts into null`() {
        val normalized =
            draft()
                .copy(
                    name = "  Classic tee  ",
                    descriptionShort = "  Short  ",
                    descriptionLong = "  Long  ",
                    sizeChartImageFilename = "   ",
                    tshirtVariants =
                        listOf(
                            variant()
                                .copy(
                                    colorName = "  Black  ",
                                    colorHex = "  #000000  ",
                                    sizeLabel = "  M  ",
                                    exampleImageFilename = "   ",
                                )
                        ),
                )
                .normalized()

        assertEquals("Classic tee", normalized.name)
        assertEquals("Short", normalized.descriptionShort)
        assertEquals("Long", normalized.descriptionLong)
        assertEquals(null, normalized.sizeChartImageFilename)
        assertEquals("Black", normalized.tshirtVariants.single().colorName)
        assertEquals("#000000", normalized.tshirtVariants.single().colorHex)
        assertEquals("M", normalized.tshirtVariants.single().sizeLabel)
        assertEquals(null, normalized.tshirtVariants.single().exampleImageFilename)
    }

    /**
     * The three fields the write contract must not have: the display position is decided by the
     * module, a price id is never accepted, and a variant name is composed rather than submitted.
     */
    @Test
    fun `the write contract exposes neither a position, a price id, nor a variant name`() {
        val fields = TshirtArticleInput.serializer().descriptor.elementNames.toSet()

        assertFalse("position" in fields)
        assertFalse("priceId" in fields)
        assertTrue("printAspectRatio" in fields)
        assertFalse("printFormat" in fields)
        assertTrue("price" in fields)
        assertFalse(
            "name" in TshirtVariantInput.serializer().descriptor.elementNames.toSet(),
            "A shirt variant is named by its colour and its size, never by the client",
        )
    }

    private fun draft(): TshirtArticleInput =
        TshirtArticleInput(
            name = "Classic tee",
            descriptionShort = "A shirt",
            descriptionLong = "A classic shirt",
            printFrame = frame(),
        )

    private fun frame(
        left: Double = 25.0,
        top: Double = 20.0,
        width: Double = 50.0,
        height: Double = 40.0,
    ): PrintFrame = PrintFrame(leftPct = left, topPct = top, widthPct = width, heightPct = height)

    private fun variant(): TshirtVariantInput =
        TshirtVariantInput(
            colorName = "Black",
            colorHex = "#000000",
            sizeLabel = "M",
            spodProductTypeId = 812,
            spodAppearanceId = 5,
            spodSizeId = 91,
            isDefault = true,
            active = true,
        )
}
