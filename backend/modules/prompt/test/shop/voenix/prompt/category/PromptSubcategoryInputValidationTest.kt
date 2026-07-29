package shop.voenix.prompt.category

import kotlin.test.Test
import kotlin.test.assertEquals

internal class PromptSubcategoryInputValidationTest {
    @Test
    fun `a complete input is valid`() {
        assertEquals(
            emptyMap(),
            PromptSubcategoryInput(categoryId = 1, name = "Kids", description = "For children")
                .validate(),
        )
    }

    @Test
    fun `the category id is required and must be positive`() {
        assertEquals(
            mapOf("categoryId" to listOf("CategoryId is required")),
            PromptSubcategoryInput(name = "Kids").validate(),
        )
        assertEquals(
            mapOf("categoryId" to listOf("CategoryId must be positive")),
            PromptSubcategoryInput(categoryId = 0, name = "Kids").validate(),
        )
    }

    @Test
    fun `a missing or blank name is rejected`() {
        assertEquals(
            mapOf("name" to listOf("Name is required")),
            PromptSubcategoryInput(categoryId = 1).validate(),
        )
        assertEquals(
            mapOf("name" to listOf("Name is required")),
            PromptSubcategoryInput(categoryId = 1, name = "  ").validate(),
        )
    }

    @Test
    fun `the length limits are measured after trimming`() {
        assertEquals(
            emptyMap(),
            PromptSubcategoryInput(
                    categoryId = 1,
                    name = " ${"a".repeat(200)} ",
                    description = " ${"b".repeat(1000)} ",
                )
                .validate(),
        )
        assertEquals(
            mapOf(
                "name" to listOf("Name must be at most 200 characters"),
                "description" to listOf("Description must be at most 1000 characters"),
            ),
            PromptSubcategoryInput(
                    categoryId = 1,
                    name = "a".repeat(201),
                    description = "b".repeat(1001),
                )
                .validate(),
        )
    }

    @Test
    fun `every broken field is reported at once`() {
        assertEquals(
            mapOf(
                "categoryId" to listOf("CategoryId is required"),
                "name" to listOf("Name is required"),
            ),
            PromptSubcategoryInput().validate(),
        )
    }

    @Test
    fun `normalizing trims the values and turns a blank description into no description`() {
        assertEquals(
            PromptSubcategoryInput(categoryId = 1, name = "Kids", description = null),
            PromptSubcategoryInput(categoryId = 1, name = "  Kids  ", description = "   ")
                .normalized(),
        )
        assertEquals(
            PromptSubcategoryInput(categoryId = 1, name = "Kids", description = "For children"),
            PromptSubcategoryInput(
                    categoryId = 1,
                    name = "Kids",
                    description = "  For children  ",
                )
                .normalized(),
        )
    }
}
