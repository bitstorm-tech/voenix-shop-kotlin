package shop.voenix.production.pdf

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import shop.voenix.production.ProductionPdfError
import shop.voenix.production.ProductionPdfResult
import shop.voenix.production.ProductionSource

internal class ProductionPdfGeneratorTest {
    private val tempDir = newTempDirectory()

    @AfterTest
    fun cleanUp() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `an unknown order is a typed not-found result`(): Unit = runBlocking {
        val generator = generatorFor { null }

        assertIs<ProductionPdfResult.OrderNotFound>(generator.generate(41))
    }

    @Test
    fun `a non-positive order id never reaches the source`(): Unit = runBlocking {
        val generator = generatorFor { fail("the source must not be queried") }

        assertIs<ProductionPdfResult.OrderNotFound>(generator.generate(0))
        assertIs<ProductionPdfResult.OrderNotFound>(generator.generate(-7))
    }

    @Test
    fun `a multi-supplier order yields one separate PDF per supplier`(): Unit = runBlocking {
        val image = writePng(tempDir, "item.png")
        val order =
            productionOrder(
                orderId = 55,
                items =
                    listOf(
                        productionItem(supplierId = 10, articleName = "Alpha", imagePath = image),
                        productionItem(
                            supplierId = 20,
                            articleName = "Beta",
                            quantity = 2,
                            imagePath = image,
                        ),
                        productionItem(supplierId = 10, articleName = "Gamma", imagePath = image),
                    ),
            )
        val generator = generatorFor { order }

        val result = assertIs<ProductionPdfResult.Generated>(generator.generate(55))

        assertEquals(listOf(10L, 20L), result.documents.map { it.supplierId })
        val (first, second) = result.documents
        assertEquals("ORD-55.pdf", first.fileName)
        assertEquals("ORD-55.pdf", second.fileName)

        loadPdf(first.bytes).use { document ->
            assertEquals(3, document.numberOfPages)
            assertContains(document.itemInfoText(2), "Alpha")
            assertEquals("ORD-55 (1/2)", document.orderLabelText(2))
            assertContains(document.itemInfoText(3), "Gamma")
            assertEquals("ORD-55 (2/2)", document.orderLabelText(3))
            assertFalse(document.itemInfoText(2).contains("Beta"))
            assertFalse(document.itemInfoText(3).contains("Beta"))
        }
        loadPdf(second.bytes).use { document ->
            assertEquals(3, document.numberOfPages)
            assertContains(document.itemInfoText(2), "Beta")
            assertEquals("ORD-55 (1/2)", document.orderLabelText(2))
            assertEquals("ORD-55 (2/2)", document.orderLabelText(3))
            assertFalse(document.itemInfoText(2).contains("Alpha"))
            assertFalse(document.itemInfoText(3).contains("Gamma"))
        }
    }

    @Test
    fun `an item without an image is a typed retryable failure, never a blank page`(): Unit =
        runBlocking {
            val order = productionOrder(items = listOf(productionItem(imagePath = null)))
            val generator = generatorFor { order }

            val result = assertIs<ProductionPdfResult.GenerationFailed>(generator.generate(42))

            assertEquals(ProductionPdfError.MISSING_IMAGE, result.error)
        }

    @Test
    fun `a dangling image path is a typed retryable failure`(): Unit = runBlocking {
        val order =
            productionOrder(
                items = listOf(productionItem(imagePath = tempDir.resolve("does-not-exist.png")))
            )
        val generator = generatorFor { order }

        val result = assertIs<ProductionPdfResult.GenerationFailed>(generator.generate(42))

        assertEquals(ProductionPdfError.MISSING_IMAGE, result.error)
    }

    @Test
    fun `an undecodable image file is a typed retryable failure`(): Unit = runBlocking {
        val corrupt = tempDir.resolve("corrupt.png")
        corrupt.toFile().writeText("this is not an image")
        val order = productionOrder(items = listOf(productionItem(imagePath = corrupt)))
        val generator = generatorFor { order }

        val result = assertIs<ProductionPdfResult.GenerationFailed>(generator.generate(42))

        assertEquals(ProductionPdfError.UNREADABLE_IMAGE, result.error)
    }

    @Test
    fun `invalid measurements and quantities are typed retryable failures`(): Unit = runBlocking {
        val image = writePng(tempDir, "item.png")
        val invalidItems =
            listOf(
                productionItem(imagePath = image, documentFormatWidthMm = -1.0),
                productionItem(imagePath = image, documentFormatHeightMm = 0.0),
                productionItem(imagePath = image, printTemplateWidthMm = -5.0),
                productionItem(imagePath = image, documentFormatMarginBottomMm = -2.0),
                // Narrower than the two side columns: no room for any print area.
                productionItem(imagePath = image, documentFormatWidthMm = 10.0),
                productionItem(imagePath = image, quantity = 0),
            )
        for (item in invalidItems) {
            val generator = generatorFor { productionOrder(items = listOf(item)) }

            val result = assertIs<ProductionPdfResult.GenerationFailed>(generator.generate(42))

            assertEquals(ProductionPdfError.INVALID_SOURCE, result.error, "item $item")
        }
    }

    @Test
    fun `an item without a supplier is a typed retryable failure`(): Unit = runBlocking {
        val image = writePng(tempDir, "item.png")
        val order =
            productionOrder(
                items =
                    listOf(
                        productionItem(supplierId = 10, imagePath = image),
                        productionItem(supplierId = null, imagePath = image),
                    )
            )
        val generator = generatorFor { order }

        val result = assertIs<ProductionPdfResult.GenerationFailed>(generator.generate(42))

        assertEquals(ProductionPdfError.INVALID_SOURCE, result.error)
    }

    @Test
    fun `one failing supplier fails the whole generation`(): Unit = runBlocking {
        val image = writePng(tempDir, "item.png")
        val order =
            productionOrder(
                items =
                    listOf(
                        productionItem(supplierId = 10, imagePath = image),
                        productionItem(supplierId = 20, imagePath = null),
                    )
            )
        val generator = generatorFor { order }

        val result = assertIs<ProductionPdfResult.GenerationFailed>(generator.generate(42))

        assertEquals(ProductionPdfError.MISSING_IMAGE, result.error)
    }

    @Test
    fun `unicode survives from the source into the rendered document`(): Unit = runBlocking {
        val image = writePng(tempDir, "item.png")
        val order =
            productionOrder(
                orderId = 66,
                firstName = "Žofia",
                lastName = "Đorđević",
                street = "Große Straße",
                houseNumber = "3a",
                postalCode = "010 01",
                city = "Žilina",
                country = "Slovensko",
                items =
                    listOf(
                        productionItem(
                            articleName = "Šálka „Čarovná“",
                            supplierArticleNumber = "SUP-ÄÖÜ",
                            variantName = "Синяя",
                            imagePath = image,
                        )
                    ),
            )
        val generator = generatorFor { order }

        val result = assertIs<ProductionPdfResult.Generated>(generator.generate(66))

        loadPdf(result.documents.single().bytes).use { document ->
            val addressText = document.horizontalText(1)
            assertContains(addressText, "Žofia Đorđević")
            assertContains(addressText, "Große Straße 3a")
            assertContains(addressText, "010 01 Žilina")
            assertEquals("Šálka „Čarovná“ | SUP-ÄÖÜ | Синяя", document.itemInfoText(2))
        }
    }

    private fun generatorFor(source: ProductionSource): ProductionPdfService =
        ProductionPdfService(source, ProductionPdfRenderer())
}
