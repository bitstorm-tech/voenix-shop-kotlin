package shop.voenix.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import shop.voenix.pricing.PriceInput

internal class PromptInputValidationTest {
    @Test
    fun `a complete input is valid`() {
        assertEquals(emptyMap(), completeInput().validate())
    }

    @Test
    fun `the title is required and limited to 255 characters after trimming`() {
        assertEquals(
            mapOf("title" to listOf("Title is required")),
            completeInput(title = "   ").validate(),
        )
        assertEquals(emptyMap(), completeInput(title = " ${"a".repeat(255)} ").validate())
        assertEquals(
            mapOf("title" to listOf("Title must be at most 255 characters")),
            completeInput(title = "a".repeat(256)).validate(),
        )
    }

    /** The stored text keeps its whitespace, but whitespace alone is not a prompt. */
    @Test
    fun `the prompt text is required but never trimmed`() {
        assertEquals(
            mapOf("promptText" to listOf("PromptText is required")),
            completeInput(promptText = " \n ").validate(),
        )
        assertEquals(emptyMap(), completeInput(promptText = "\n  Paint it.  \n").validate())
        assertEquals(
            "\n  Paint it.  \n",
            completeInput(promptText = "\n  Paint it.  \n").normalized().promptText,
        )
    }

    @Test
    fun `the category is required and both references must be positive`() {
        assertEquals(
            mapOf("categoryId" to listOf("CategoryId is required")),
            completeInput(categoryId = null).validate(),
        )
        assertEquals(
            mapOf("categoryId" to listOf("CategoryId must be positive")),
            completeInput(categoryId = 0).validate(),
        )
        assertEquals(
            mapOf("subcategoryId" to listOf("SubcategoryId must be positive")),
            completeInput(subcategoryId = -1).validate(),
        )
        // A prompt without a subcategory is a complete prompt.
        assertEquals(emptyMap(), completeInput(subcategoryId = null).validate())
    }

    @Test
    fun `slot variant ids are required, may be empty, and must be positive`() {
        assertEquals(
            mapOf("slotVariantIds" to listOf("SlotVariantIds is required")),
            completeInput(slotVariantIds = null).validate(),
        )
        assertEquals(emptyMap(), completeInput(slotVariantIds = emptyList()).validate())
        assertEquals(
            mapOf("slotVariantIds" to listOf("SlotVariantIds must be positive")),
            completeInput(slotVariantIds = listOf(1, 0)).validate(),
        )
    }

    /** Repeating an id asks for the same thing twice; it is not a mistake to reject. */
    @Test
    fun `duplicate slot variant ids are accepted and deduplicated`() {
        assertEquals(emptyMap(), completeInput(slotVariantIds = listOf(12, 9, 12)).validate())
        assertEquals(
            listOf(12L, 9L),
            completeInput(slotVariantIds = listOf(12, 9, 12)).normalized().slotVariantIds,
        )
    }

    @Test
    fun `the price is required on every write`() {
        assertEquals(
            mapOf("price" to listOf("Price is required")),
            completeInput(price = null).validate(),
        )
    }

    @Test
    fun `the optional texts are length limited`() {
        assertEquals(
            mapOf("llm" to listOf("Llm must be at most 255 characters")),
            completeInput(llm = "a".repeat(256)).validate(),
        )
        assertEquals(
            mapOf(
                "exampleImageFilename" to
                    listOf("ExampleImageFilename must be at most 255 characters")
            ),
            completeInput(exampleImageFilename = "a".repeat(256)).validate(),
        )
    }

    @Test
    fun `every broken field is reported at once`() {
        assertEquals(
            mapOf(
                "title" to listOf("Title is required"),
                "promptText" to listOf("PromptText is required"),
                "categoryId" to listOf("CategoryId is required"),
                "slotVariantIds" to listOf("SlotVariantIds is required"),
                "price" to listOf("Price is required"),
            ),
            PromptInput().validate(),
        )
    }

    @Test
    fun `normalizing trims the title and the optional texts and leaves the prompt text alone`() {
        assertEquals(
            completeInput(
                title = "Watercolor portrait",
                promptText = "  Turn the photo into art.  ",
                llm = "gpt-image-1",
                exampleImageFilename = null,
            ),
            completeInput(
                    title = "  Watercolor portrait  ",
                    promptText = "  Turn the photo into art.  ",
                    llm = "  gpt-image-1  ",
                    exampleImageFilename = "   ",
                )
                .normalized(),
        )
    }

    /**
     * The two fields the contract must not have. `position` is decided by the module, and a
     * `priceId` would let a body attach a price row another prompt owns — a rule that holds by
     * construction only as long as no prompt input can express one.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `the input has neither a position nor a price id`() {
        val fields = PromptInput.serializer().descriptor.elementNames.toSet()
        assertFalse("position" in fields, "The prompt input must not accept a position")
        assertFalse("priceId" in fields, "The prompt input must not accept a price id")

        // A body that sends them anyway is simply ignored, never stored.
        assertEquals(
            PromptInput(
                title = "T",
                promptText = "P",
                categoryId = 1,
                slotVariantIds = emptyList(),
            ),
            lenientJson.decodeFromString<PromptInput>(
                """{"title":"T","promptText":"P","categoryId":1,"slotVariantIds":[],""" +
                    """"position":9,"priceId":77}"""
            ),
        )
    }

    private fun completeInput(
        title: String? = "Watercolor portrait",
        promptText: String? = "Turn the photo into art.",
        categoryId: Long? = 3,
        subcategoryId: Long? = 7,
        slotVariantIds: List<Long>? = listOf(9),
        exampleImageFilename: String? = null,
        llm: String? = "gpt-image-1",
        price: PriceInput? = PriceInput(purchaseVatId = 1, salesVatId = 1),
    ): PromptInput =
        PromptInput(
            title = title,
            promptText = promptText,
            categoryId = categoryId,
            subcategoryId = subcategoryId,
            slotVariantIds = slotVariantIds,
            exampleImageFilename = exampleImageFilename,
            llm = llm,
            price = price,
        )

    private companion object {
        val lenientJson = Json { ignoreUnknownKeys = true }
    }
}
