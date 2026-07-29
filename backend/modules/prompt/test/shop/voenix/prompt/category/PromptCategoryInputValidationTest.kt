package shop.voenix.prompt.category

import kotlin.test.Test
import kotlin.test.assertEquals

internal class PromptCategoryInputValidationTest {
    @Test
    fun `a complete input is valid`() {
        assertEquals(emptyMap(), PromptCategoryInput(name = "Portraits").validate())
    }

    @Test
    fun `a missing or blank name is rejected`() {
        assertEquals(mapOf("name" to listOf("Name is required")), PromptCategoryInput().validate())
        assertEquals(
            mapOf("name" to listOf("Name is required")),
            PromptCategoryInput(name = "   ").validate(),
        )
    }

    @Test
    fun `the length limit is measured after trimming`() {
        assertEquals(emptyMap(), PromptCategoryInput(name = " ${"a".repeat(200)} ").validate())
        assertEquals(
            mapOf("name" to listOf("Name must be at most 200 characters")),
            PromptCategoryInput(name = "a".repeat(201)).validate(),
        )
    }

    @Test
    fun `a category is active unless the body says otherwise`() {
        assertEquals(true, PromptCategoryInput(name = "Portraits").active)
        assertEquals(false, PromptCategoryInput(name = "Portraits", active = false).active)
    }

    @Test
    fun `normalizing trims the name and keeps the activation`() {
        assertEquals(
            PromptCategoryInput(name = "Portraits", active = false),
            PromptCategoryInput(name = "  Portraits  ", active = false).normalized(),
        )
    }
}
