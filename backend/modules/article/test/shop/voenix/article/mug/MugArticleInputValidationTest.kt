package shop.voenix.article.mug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.descriptors.elementNames
import shop.voenix.article.PrintAspectRatio
import shop.voenix.pricing.PriceInput

/**
 * The field-rule matrix of a mug, ported from the legacy `ArticleRequestValidatorTests`.
 *
 * Three rules are new and each has its own reason: an active mug also needs a category (the legacy
 * storefront silently hid such articles), the ids that reference another row have to be positive
 * like everywhere else in this backend, and the variant array may not address the same variant
 * twice, because it is a diff.
 *
 * The rule "an active mug needs a price" is deliberately *not* here. Whether a price exists can be
 * a fact about the stored article, so the write path owns it; `MugArticleAdminIntegrationTest`
 * proves it.
 */
internal class MugArticleInputValidationTest {
    @Test
    fun `a draft without details and variants is accepted`() {
        assertEquals(emptyMap(), draft().validate())
    }

    @Test
    fun `name and both descriptions are required`() {
        val errors =
            MugArticleInput(name = " ", descriptionShort = "", descriptionLong = "").validate()

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
                    supplierArticleName = "a".repeat(256),
                    supplierArticleNumber = "a".repeat(256),
                    mugDetails = details().copy(fillingQuantity = "a".repeat(256)),
                    mugVariants = listOf(variant().copy(name = "a".repeat(256))),
                )
                .validate()

        assertEquals(
            mapOf(
                "name" to listOf("Name must be at most 255 characters"),
                "descriptionShort" to listOf("DescriptionShort must be at most 1000 characters"),
                "descriptionLong" to listOf("DescriptionLong must be at most 5000 characters"),
                "supplierArticleName" to
                    listOf("SupplierArticleName must be at most 255 characters"),
                "supplierArticleNumber" to
                    listOf("SupplierArticleNumber must be at most 255 characters"),
                "mugDetails.fillingQuantity" to
                    listOf("FillingQuantity must be at most 255 characters"),
                "mugVariants[0].name" to listOf("Name must be at most 255 characters"),
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
                "mugVariants[0].id" to listOf("Id must be positive"),
            ),
            draft()
                .copy(
                    categoryId = 0,
                    subcategoryId = -1,
                    supplierId = 0,
                    mugVariants = listOf(variant().copy(id = 0)),
                )
                .validate(),
        )
    }

    @Test
    fun `an active mug needs details, an active variant, and a category`() {
        assertEquals(
            listOf("An active article requires complete mug details"),
            draft()
                .copy(active = true, categoryId = 1, mugVariants = listOf(variant()))
                .validate()["active"],
        )
        assertEquals(
            listOf("An active article requires at least one active variant"),
            draft()
                .copy(
                    active = true,
                    categoryId = 1,
                    mugDetails = details(),
                    mugVariants = listOf(variant().copy(active = false)),
                )
                .validate()["active"],
        )
        assertEquals(
            listOf("An active article requires a category"),
            draft()
                .copy(active = true, mugDetails = details(), mugVariants = listOf(variant()))
                .validate()["active"],
        )
    }

    @Test
    fun `an active mug with details, an active variant, and a category is accepted`() {
        assertEquals(
            emptyMap(),
            draft()
                .copy(
                    active = true,
                    categoryId = 1,
                    mugDetails = details(),
                    mugVariants = listOf(variant()),
                )
                .validate(),
        )
    }

    @Test
    fun `a non-empty variant array needs exactly one default`() {
        assertEquals(
            listOf("Exactly one variant must be marked as default"),
            draft()
                .copy(mugVariants = listOf(variant().copy(isDefault = false)))
                .validate()["mugVariants"],
        )
        assertEquals(
            listOf("Exactly one variant must be marked as default"),
            draft().copy(mugVariants = listOf(variant(), variant())).validate()["mugVariants"],
        )
    }

    @Test
    fun `the variant array may not address the same variant twice`() {
        assertEquals(
            listOf("Variant ids must be unique"),
            draft()
                .copy(
                    mugVariants =
                        listOf(variant().copy(id = 7), variant().copy(id = 7, isDefault = false))
                )
                .validate()["mugVariants"],
        )
    }

    @Test
    fun `a variant needs a name and both color codes`() {
        assertEquals(
            mapOf(
                "mugVariants[0].name" to listOf("Name is required"),
                "mugVariants[0].insideColorCode" to listOf("InsideColorCode is required"),
                "mugVariants[0].outsideColorCode" to listOf("OutsideColorCode is required"),
            ),
            draft()
                .copy(
                    mugVariants =
                        listOf(
                            MugVariantInput(
                                name = "",
                                insideColorCode = " ",
                                outsideColorCode = "",
                                isDefault = true,
                            )
                        )
                )
                .validate(),
        )
    }

    @Test
    fun `every measurement must be greater than zero`() {
        assertEquals(
            mapOf(
                "mugDetails.heightMm" to listOf("HeightMm must be greater than zero"),
                "mugDetails.documentFormatWidthMm" to
                    listOf("DocumentFormatWidthMm must be greater than zero"),
            ),
            draft()
                .copy(mugDetails = details().copy(heightMm = 0, documentFormatWidthMm = -1))
                .validate(),
        )
        assertEquals(
            setOf(
                "mugDetails.heightMm",
                "mugDetails.diameterMm",
                "mugDetails.printTemplateWidthMm",
                "mugDetails.printTemplateHeightMm",
            ),
            draft().copy(mugDetails = MugDetails()).validate().keys,
        )
    }

    /**
     * The ratio is submitted as text, so that a value this shop does not print is a field error
     * like every other one instead of a body that fails to parse.
     */
    @Test
    fun `the print aspect ratio must be one this shop prints`() {
        assertEquals(
            mapOf("printAspectRatio" to listOf("PrintAspectRatio must be one of 16:9, 1:1")),
            draft().copy(printAspectRatio = "4:3").validate(),
        )
        // The constant name is not the contract either.
        assertEquals(
            listOf("PrintAspectRatio must be one of 16:9, 1:1"),
            draft().copy(printAspectRatio = "SQUARE").validate()["printAspectRatio"],
        )

        assertEquals(emptyMap(), draft().copy(printAspectRatio = " 1:1 ").validate())
        assertEquals(PrintAspectRatio.SQUARE, draft().copy(printAspectRatio = " 1:1 ").printFormat)
        // An absent field is what a mug has always been printed in.
        assertEquals(emptyMap(), draft().validate())
        assertEquals(PrintAspectRatio.WIDE_16_9, draft().printFormat)
    }

    @Test
    fun `normalization trims what is written and turns blank optional texts into null`() {
        val normalized =
            draft()
                .copy(
                    name = "  Classic mug  ",
                    descriptionShort = "  Short  ",
                    descriptionLong = "  Long  ",
                    supplierArticleName = "   ",
                    supplierArticleNumber = "  A-1  ",
                    mugDetails = details().copy(fillingQuantity = "   "),
                    mugVariants =
                        listOf(
                            variant()
                                .copy(
                                    name = "  White  ",
                                    insideColorCode = "  #ffffff  ",
                                    outsideColorCode = "  #000000  ",
                                    exampleImageFilename = "   ",
                                )
                        ),
                )
                .normalized()

        assertEquals("Classic mug", normalized.name)
        assertEquals("Short", normalized.descriptionShort)
        assertEquals("Long", normalized.descriptionLong)
        assertEquals(null, normalized.supplierArticleName)
        assertEquals("A-1", normalized.supplierArticleNumber)
        assertEquals(null, normalized.mugDetails?.fillingQuantity)
        assertEquals("White", normalized.mugVariants.single().name)
        assertEquals("#ffffff", normalized.mugVariants.single().insideColorCode)
        assertEquals("#000000", normalized.mugVariants.single().outsideColorCode)
        assertEquals(null, normalized.mugVariants.single().exampleImageFilename)
    }

    /**
     * The two fields the write contract must not have: the display position is decided by the
     * module, and a price id is never accepted, which is what makes a price belong to exactly one
     * article by construction.
     */
    @Test
    fun `the write contract exposes neither a position nor a price id`() {
        val fields = MugArticleInput.serializer().descriptor.elementNames.toSet()

        assertFalse("position" in fields)
        assertFalse("priceId" in fields)
        // The resolved ratio is a reading of the submitted text, not a second field of the body.
        assertTrue("printAspectRatio" in fields)
        assertFalse("printFormat" in fields)
        assertTrue("price" in fields)
        assertFalse("id" in PriceInput.serializer().descriptor.elementNames.toSet())
    }

    private fun draft(): MugArticleInput =
        MugArticleInput(
            name = "Classic Mug",
            descriptionShort = "A mug",
            descriptionLong = "A classic mug",
        )

    private fun details(): MugDetails =
        MugDetails(
            heightMm = 95,
            diameterMm = 82,
            printTemplateWidthMm = 200,
            printTemplateHeightMm = 90,
            dishwasherSafe = true,
        )

    private fun variant(): MugVariantInput =
        MugVariantInput(
            name = "White",
            insideColorCode = "#ffffff",
            outsideColorCode = "#ffffff",
            isDefault = true,
            active = true,
        )
}
