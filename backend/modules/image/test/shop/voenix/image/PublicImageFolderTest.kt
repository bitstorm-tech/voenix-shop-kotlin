package shop.voenix.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class PublicImageFolderTest {
    @Test
    fun `safe nested folders are normalized once`() {
        assertEquals(
            "prompt/example-images",
            PublicImageFolder.of("prompt/example-images").path.toString(),
        )
    }

    @Test
    fun `unsafe folders are rejected`() {
        listOf(
                "",
                " ",
                "/absolute",
                "\\absolute",
                ".",
                "..",
                "a/../b",
                "a/./b",
                "a//b",
                "a/",
                "a\\b",
            )
            .forEach { value ->
                assertFailsWith<IllegalArgumentException>(value) { PublicImageFolder.of(value) }
            }
    }
}
