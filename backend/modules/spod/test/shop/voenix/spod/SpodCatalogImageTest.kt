package shop.voenix.spod

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the partner's undocumented image answers mean: which URL a mockup is really behind, which of
 * a colour's mockups is the garment alone, and which one a shop shows a colour with.
 */
internal class SpodCatalogImageTest {
    @Test
    fun `the lookupId placeholder is replaced by the product id of the same answer`() {
        val image =
            image(
                productId = 598279462,
                url =
                    "$IMAGE_SERVER/products/lookupId/views/1,width=1000,appearanceId=2,mediaType=png/1",
            )

        assertEquals(
            "$IMAGE_SERVER/products/598279462/views/1,width=1000,appearanceId=2,mediaType=png/1",
            image.downloadUrl(),
        )
    }

    @Test
    fun `a URL without the placeholder, or an answer without a product id, is used as it is`() {
        val resolved = "$IMAGE_SERVER/products/598279462/views/1,appearanceId=2,mediaType=png/1"
        val placeholder = "$IMAGE_SERVER/products/lookupId/views/1,appearanceId=2,mediaType=png/1"

        assertEquals(resolved, image(productId = 598279462, url = resolved).downloadUrl())
        assertEquals(placeholder, image(productId = 0, url = placeholder).downloadUrl())
        assertEquals("", image(productId = 598279462, url = "").downloadUrl())
    }

    @Test
    fun `a mockup with a modelId shows a model`() {
        val model = "$IMAGE_SERVER/products/1/views/1,appearanceId=2,modelId=12701,crop=detail/1"
        val garment = "$IMAGE_SERVER/products/1/views/1,appearanceId=2/1"

        assertTrue(image(url = model).showsModel)
        assertFalse(image(url = garment).showsModel)
    }

    /**
     * The exact word wins over a word that merely contains it, and the garment alone over a model.
     */
    @Test
    fun `the front image is the garment alone with the closest perspective`() {
        val article =
            SpodCatalogArticle(
                id = "a-1",
                images =
                    listOf(
                        image(id = "back", appearanceId = 5, perspective = "back"),
                        image(
                            id = "front-model",
                            appearanceId = 5,
                            perspective = "front",
                            model = true,
                        ),
                        image(id = "front-top", appearanceId = 5, perspective = "front_top"),
                        image(id = "front", appearanceId = 5, perspective = "FRONT"),
                        image(id = "other-colour", appearanceId = 6, perspective = "front"),
                    ),
            )

        assertEquals("front", article.frontImage(5)?.id)
        assertEquals("other-colour", article.frontImage(6)?.id)
        assertNull(article.frontImage(7))
    }

    @Test
    fun `without a front view any picture of the colour will do, the garment alone first`() {
        val article =
            SpodCatalogArticle(
                id = "a-1",
                images =
                    listOf(
                        image(
                            id = "side-model",
                            appearanceId = 5,
                            perspective = "side",
                            model = true,
                        ),
                        image(id = "side", appearanceId = 5, perspective = "side"),
                        image(id = "blank", appearanceId = 5, perspective = "front", url = ""),
                    ),
            )

        assertEquals("side", article.frontImage(5)?.id)
    }

    /**
     * A mockup as the partner lists it; [url] overrides the placeholder URL built from the rest.
     */
    private fun image(
        id: String = "i-1",
        productId: Long = 1,
        appearanceId: Long = 2,
        perspective: String? = "front",
        model: Boolean = false,
        url: String? = null,
    ): SpodCatalogImage {
        val modelParameters = if (model) ",modelId=12701,crop=detail" else ""
        val parameters = "appearanceId=$appearanceId$modelParameters,mediaType=png"
        return SpodCatalogImage(
            id = id,
            productId = productId,
            appearanceId = appearanceId,
            perspective = perspective,
            imageUrl = url ?: "$IMAGE_SERVER/products/lookupId/views/1,$parameters/$id",
        )
    }
}

private const val IMAGE_SERVER = "https://image.spreadshirtmedia.net/image-server/v1"
