package shop.voenix.article.category

import kotlin.test.Test
import kotlin.test.assertEquals

internal class ArticleSubcategoryInputValidationTest {
    @Test
    fun `a complete input is valid`() {
        assertEquals(
            emptyMap(),
            ArticleSubcategoryInput(
                    categoryId = 1,
                    name = "Classic",
                    description = "Classic mugs",
                    exampleImageFilename = "anything",
                    active = false,
                )
                .validate(),
        )
    }

    @Test
    fun `only the category and the name are required`() {
        assertEquals(
            emptyMap(),
            ArticleSubcategoryInput(categoryId = 1, name = "Classic").validate(),
        )
        assertEquals(
            emptyMap(),
            ArticleSubcategoryInput(categoryId = 1, name = "Classic", description = "  ")
                .validate(),
        )
    }

    @Test
    fun `a missing or unusable category id is rejected`() {
        assertEquals(
            mapOf("categoryId" to listOf("CategoryId is required")),
            ArticleSubcategoryInput(name = "Classic").validate(),
        )
        assertEquals(
            mapOf("categoryId" to listOf("CategoryId must be positive")),
            ArticleSubcategoryInput(categoryId = 0, name = "Classic").validate(),
        )
    }

    @Test
    fun `a missing or blank name is rejected`() {
        assertEquals(
            mapOf("name" to listOf("Name is required")),
            ArticleSubcategoryInput(categoryId = 1).validate(),
        )
        assertEquals(
            mapOf("name" to listOf("Name is required")),
            ArticleSubcategoryInput(categoryId = 1, name = "   ").validate(),
        )
    }

    @Test
    fun `length limits are measured after trimming`() {
        assertEquals(
            emptyMap(),
            ArticleSubcategoryInput(categoryId = 1, name = " ${"a".repeat(200)} ").validate(),
        )
        assertEquals(
            mapOf("name" to listOf("Name must be at most 200 characters")),
            ArticleSubcategoryInput(categoryId = 1, name = "a".repeat(201)).validate(),
        )

        assertEquals(
            emptyMap(),
            ArticleSubcategoryInput(
                    categoryId = 1,
                    name = "Classic",
                    description = " ${"a".repeat(1000)} ",
                )
                .validate(),
        )
        assertEquals(
            mapOf("description" to listOf("Description must be at most 1000 characters")),
            ArticleSubcategoryInput(
                    categoryId = 1,
                    name = "Classic",
                    description = "a".repeat(1001),
                )
                .validate(),
        )
    }

    /**
     * Whether the named file exists is not a field rule, so the shape of the name is checked
     * together with it while saving, not here.
     */
    @Test
    fun `the example image file name is not a field rule`() {
        assertEquals(
            emptyMap(),
            ArticleSubcategoryInput(
                    categoryId = 1,
                    name = "Classic",
                    exampleImageFilename = "not-a-stored-name.png",
                )
                .validate(),
        )
    }

    @Test
    fun `every broken rule is reported at once`() {
        assertEquals(
            setOf("categoryId", "name", "description"),
            ArticleSubcategoryInput(name = "", description = "a".repeat(1001)).validate().keys,
        )
    }

    @Test
    fun `normalization trims texts and turns blank ones into null`() {
        assertEquals(
            ArticleSubcategoryInput(
                categoryId = 7,
                name = "Classic",
                description = null,
                exampleImageFilename = null,
            ),
            ArticleSubcategoryInput(
                    categoryId = 7,
                    name = "  Classic  ",
                    description = "   ",
                    exampleImageFilename = "  ",
                )
                .normalized(),
        )
        assertEquals(
            "image.webp",
            ArticleSubcategoryInput(
                    categoryId = 7,
                    name = "Classic",
                    exampleImageFilename = " image.webp ",
                )
                .normalized()
                .exampleImageFilename,
        )
    }
}
