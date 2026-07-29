package shop.voenix.prompt.slot

import kotlin.test.Test
import kotlin.test.assertEquals

internal class PromptSlotInputValidationTest {
    @Test
    fun `a complete input is valid`() {
        assertEquals(emptyMap(), PromptSlotInput(name = "Background").validate())
    }

    @Test
    fun `a missing or blank name is rejected`() {
        assertEquals(mapOf("name" to listOf("Name is required")), PromptSlotInput().validate())
        assertEquals(
            mapOf("name" to listOf("Name is required")),
            PromptSlotInput(name = "   ").validate(),
        )
    }

    @Test
    fun `the length limit is measured after trimming`() {
        assertEquals(emptyMap(), PromptSlotInput(name = " ${"a".repeat(255)} ").validate())
        assertEquals(
            mapOf("name" to listOf("Name must be at most 255 characters")),
            PromptSlotInput(name = "a".repeat(256)).validate(),
        )
    }

    @Test
    fun `normalizing trims the name`() {
        assertEquals(
            PromptSlotInput(name = "Background"),
            PromptSlotInput(name = "  Background  ").normalized(),
        )
    }
}
