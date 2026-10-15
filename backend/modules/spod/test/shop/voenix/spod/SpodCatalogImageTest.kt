package shop.voenix.spod

import kotlin.test.Test
import kotlin.test.assertEquals

/** What the partner's undocumented image answers mean: which URL an image is really behind. */
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

    private fun image(productId: Long, url: String): SpodCatalogImage =
        SpodCatalogImage(id = "i-1", productId = productId, imageUrl = url)
}

private const val IMAGE_SERVER = "https://image.spreadshirtmedia.net/image-server/v1"
