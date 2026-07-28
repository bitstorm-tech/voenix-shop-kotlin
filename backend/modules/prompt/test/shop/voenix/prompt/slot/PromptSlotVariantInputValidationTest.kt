package shop.voenix.prompt.slot

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The field matrix of both variant inputs, including the one place where they differ: the create
 * decides the slot, the update cannot express it.
 */
internal class PromptSlotVariantInputValidationTest {
    @Test
    fun `a complete create input is valid`() {
        assertEquals(
            emptyMap(),
            PromptSlotVariantInput(
                    slotId = 3,
                    name = "Watercolor",
                    prompt = "in watercolor",
                    description = "A soft look",
                    llm = "gpt-image-1",
                )
                .validate(),
        )
    }

    @Test
    fun `a complete update input is valid and needs no slot`() {
        assertEquals(
            emptyMap(),
            PromptSlotVariantUpdate(name = "Watercolor", prompt = "in watercolor").validate(),
        )
    }

    @Test
    fun `the slot id is required and must be positive on create only`() {
        assertEquals(
            listOf("Slot id is required"),
            PromptSlotVariantInput(name = "Watercolor", prompt = "in watercolor")
                .validate()
                .getValue("slotId"),
        )
        assertEquals(
            listOf("Slot id must be positive"),
            PromptSlotVariantInput(slotId = 0, name = "Watercolor", prompt = "in watercolor")
                .validate()
                .getValue("slotId"),
        )
        assertEquals(
            listOf("Slot id must be positive"),
            PromptSlotVariantInput(slotId = -1, name = "Watercolor", prompt = "in watercolor")
                .validate()
                .getValue("slotId"),
        )

        // The update has no slot field at all, so no input can name one.
        assertEquals(
            emptyMap(),
            PromptSlotVariantUpdate(name = "Watercolor", prompt = "in watercolor").validate(),
        )
    }

    @Test
    fun `a missing or blank name and prompt are rejected by both inputs`() {
        assertEquals(
            mapOf(
                "name" to listOf("Name is required"),
                "prompt" to listOf("Prompt is required"),
            ),
            PromptSlotVariantUpdate(name = "   ", prompt = "").validate(),
        )
        assertEquals(
            setOf("slotId", "name", "prompt"),
            PromptSlotVariantInput().validate().keys,
        )
    }

    @Test
    fun `the description and the llm are optional`() {
        assertEquals(
            emptyMap(),
            PromptSlotVariantUpdate(
                    name = "Watercolor",
                    prompt = "in watercolor",
                    description = "  ",
                    llm = "  ",
                )
                .validate(),
        )
    }

    @Test
    fun `length limits are measured after trimming`() {
        val valid =
            PromptSlotVariantUpdate(
                name = " ${"a".repeat(255)} ",
                prompt = " ${"b".repeat(10_000)} ",
                description = " ${"c".repeat(1000)} ",
                llm = " ${"d".repeat(255)} ",
            )
        assertEquals(emptyMap(), valid.validate())

        assertEquals(
            mapOf(
                "name" to listOf("Name must be at most 255 characters"),
                "prompt" to listOf("Prompt must be at most 10000 characters"),
                "description" to listOf("Description must be at most 1000 characters"),
                "llm" to listOf("LLM must be at most 255 characters"),
            ),
            PromptSlotVariantUpdate(
                    name = "a".repeat(256),
                    prompt = "b".repeat(10_001),
                    description = "c".repeat(1001),
                    llm = "d".repeat(256),
                )
                .validate(),
        )
    }

    @Test
    fun `the create input reports its own and the shared rules at once`() {
        assertEquals(
            setOf("slotId", "name", "prompt", "llm"),
            PromptSlotVariantInput(slotId = 0, llm = "d".repeat(256)).validate().keys,
        )
    }

    @Test
    fun `normalizing trims and turns a blank description or llm into null`() {
        assertEquals(
            PromptSlotVariantUpdate(
                name = "Watercolor",
                prompt = "in watercolor",
                description = "A soft look",
                llm = "gpt-image-1",
            ),
            PromptSlotVariantUpdate(
                    name = "  Watercolor  ",
                    prompt = "  in watercolor  ",
                    description = "  A soft look  ",
                    llm = "  gpt-image-1  ",
                )
                .normalized(),
        )

        val blanked =
            PromptSlotVariantUpdate(
                    name = "Watercolor",
                    prompt = "in watercolor",
                    description = "   ",
                    llm = null,
                )
                .normalized()
        assertEquals(null, blanked.description)
        assertEquals(null, blanked.llm)
    }

    @Test
    fun `the create input hands its shared fields to the update input unchanged`() {
        assertEquals(
            PromptSlotVariantUpdate(
                name = "  Watercolor  ",
                prompt = "in watercolor",
                description = "A soft look",
                llm = "gpt-image-1",
            ),
            PromptSlotVariantInput(
                    slotId = 3,
                    name = "  Watercolor  ",
                    prompt = "in watercolor",
                    description = "A soft look",
                    llm = "gpt-image-1",
                )
                .values(),
        )
    }
}
