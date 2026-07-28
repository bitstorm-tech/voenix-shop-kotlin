package shop.voenix.article.category

import kotlin.test.Test
import kotlin.test.assertEquals

internal class ArticleCategoryInputValidationTest {
    @Test
    fun `a complete input is valid`() {
        assertEquals(
            emptyMap(),
            ArticleCategoryInput(name = "Mugs", description = "All mugs", active = false)
                .validate(),
        )
    }

    @Test
    fun `the description is optional`() {
        assertEquals(emptyMap(), ArticleCategoryInput(name = "Mugs").validate())
        assertEquals(emptyMap(), ArticleCategoryInput(name = "Mugs", description = "  ").validate())
    }

    @Test
    fun `a missing or blank name is rejected`() {
        assertEquals(
            mapOf("name" to listOf("Name is required")),
            ArticleCategoryInput().validate(),
        )
        assertEquals(
            mapOf("name" to listOf("Name is required")),
            ArticleCategoryInput(name = "   ").validate(),
        )
    }

    @Test
    fun `length limits are measured after trimming`() {
        assertEquals(emptyMap(), ArticleCategoryInput(name = " ${"a".repeat(200)} ").validate())
        assertEquals(
            mapOf("name" to listOf("Name must be at most 200 characters")),
            ArticleCategoryInput(name = "a".repeat(201)).validate(),
        )

        assertEquals(
            emptyMap(),
            ArticleCategoryInput(name = "Mugs", description = " ${"a".repeat(1000)} ").validate(),
        )
        assertEquals(
            mapOf("description" to listOf("Description must be at most 1000 characters")),
            ArticleCategoryInput(name = "Mugs", description = "a".repeat(1001)).validate(),
        )
    }

    @Test
    fun `every broken rule is reported at once`() {
        assertEquals(
            setOf("name", "description"),
            ArticleCategoryInput(name = "", description = "a".repeat(1001)).validate().keys,
        )
    }

    @Test
    fun `normalizing trims the name and turns a blank description into null`() {
        assertEquals(
            ArticleCategoryInput(name = "Mugs", description = "All mugs", active = true),
            ArticleCategoryInput(name = "  Mugs  ", description = "  All mugs  ").normalized(),
        )
        assertEquals(
            null,
            ArticleCategoryInput(name = "Mugs", description = "   ").normalized().description,
        )
        assertEquals(
            null,
            ArticleCategoryInput(name = "Mugs", description = null).normalized().description,
        )
    }
}
