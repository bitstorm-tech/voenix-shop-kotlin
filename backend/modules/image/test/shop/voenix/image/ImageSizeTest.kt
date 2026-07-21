package shop.voenix.image

import com.sksamuel.scrimage.ImmutableImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class ImageSizeTest {
    @Test
    fun `width and box parsing accepts only positive bounded decimal dimensions`() {
        assertEquals(ImageSize(1, null), ImageSize.parse("1"))
        assertEquals(ImageSize(4096, null), ImageSize.parse("4096"))
        assertEquals(ImageSize(120, 80), ImageSize.parse("120x80"))
        assertEquals(ImageSize(1, 4096), ImageSize.parse("1x4096"))

        listOf(
                "",
                "0",
                "4097",
                "-1",
                "+1",
                " 1",
                "1 ",
                "x1",
                "1x",
                "1X2",
                "1x0",
                "1x4097",
                "1x2x3",
                "999999999999999999999",
            )
            .forEach { assertNull(ImageSize.parse(it), it) }
    }

    @Test
    fun `resize fits within bounds preserves aspect ratio and permits upscaling`() {
        val landscape = ImmutableImage.create(300, 200)
        val portrait = ImmutableImage.create(200, 300)

        assertDimensions(ImageSize(120, null).resize(landscape), 120, 80)
        assertDimensions(ImageSize(120, 120).resize(landscape), 120, 80)
        assertDimensions(ImageSize(120, 120).resize(portrait), 80, 120)
        assertDimensions(ImageSize(600, 400).resize(landscape), 600, 400)
        assertDimensions(ImageSize(300, 200).resize(landscape), 300, 200)
        assertDimensions(ImageSize(400, 100).resize(landscape), 150, 100)
    }

    private fun assertDimensions(
        image: ImmutableImage,
        width: Int,
        height: Int,
    ) {
        assertEquals(width, image.width)
        assertEquals(height, image.height)
    }
}
