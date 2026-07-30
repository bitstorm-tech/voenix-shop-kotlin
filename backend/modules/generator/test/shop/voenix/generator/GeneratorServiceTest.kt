package shop.voenix.generator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import shop.voenix.generator.GeneratorTestSupport.COMPOSED_TEXT
import shop.voenix.generator.GeneratorTestSupport.FakeCoins
import shop.voenix.generator.GeneratorTestSupport.FakeGenerator
import shop.voenix.generator.GeneratorTestSupport.FakePrompts
import shop.voenix.generator.GeneratorTestSupport.GENERATE
import shop.voenix.generator.GeneratorTestSupport.HAS_ENOUGH
import shop.voenix.generator.GeneratorTestSupport.OWNER
import shop.voenix.generator.GeneratorTestSupport.SPEND
import shop.voenix.generator.GeneratorTestSupport.received
import shop.voenix.operation.OperationResult

/**
 * What one generation does, in which order, and what it does not do when something goes wrong.
 *
 * Every test reads the same recorded call log, because the order is the behavior here: the coin
 * check before the prompt, the spend only after an image exists.
 */
internal class GeneratorServiceTest {
    private val calls = mutableListOf<String>()
    private val coins = FakeCoins(calls)
    private val prompts = FakePrompts(calls)
    private val generator = FakeGenerator(calls)
    private val service = GeneratorService(coins, prompts, generator)

    @Test
    fun `a generation checks coins, loads the prompt, generates, and spends - in that order`() =
        runBlocking {
            val outcome = service.generate(OWNER, received())

            assertEquals(listOf(HAS_ENOUGH, COMPOSED_TEXT, GENERATE, SPEND), calls)
            val generated = assertIs<GenerationOutcome.Generated>(outcome)
            assertEquals("image/jpeg", generated.image.contentType)
            assertEquals(listOf<Byte>(9, 9), generated.image.bytes.toList())
            assertEquals(listOf("as a watercolor"), generator.prompts)
        }

    @Test
    fun `a failed spend does not take the generated image away`() = runBlocking {
        coins.spends = false

        val outcome = service.generate(OWNER, received())

        assertIs<GenerationOutcome.Generated>(outcome)
        assertTrue(calls.contains(SPEND))
    }

    @Test
    fun `a rejected upload never touches coins`() = runBlocking {
        val outcomes =
            listOf(
                GenerationUpload.MissingImage to IMAGE_PART_NAME,
                GenerationUpload.TooLarge to IMAGE_PART_NAME,
                GenerationUpload.MissingPromptId to PROMPT_ID_PART_NAME,
            )

        outcomes.forEach { (upload, field) ->
            val outcome = service.generate(OWNER, upload)

            val invalid = assertIs<GenerationOutcome.Invalid>(outcome)
            assertEquals(field, invalid.field)
            assertTrue(invalid.message.isNotBlank())
        }
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `only jpeg, png, and webp are generated from, whatever the casing`() = runBlocking {
        listOf("image/jpeg", "image/JPEG", "image/PNG", "image/webp").forEach { contentType ->
            assertIs<GenerationOutcome.Generated>(
                service.generate(OWNER, received(contentType = contentType)),
                "$contentType is an allowed upload type",
            )
        }

        calls.clear()
        val outcome = service.generate(OWNER, received(contentType = "text/html"))

        val invalid = assertIs<GenerationOutcome.Invalid>(outcome)
        assertEquals(IMAGE_PART_NAME, invalid.field)
        assertEquals("Image must be a JPEG, PNG, or WebP file", invalid.message)
        assertEquals(emptyList(), calls, "An unusable upload never costs a coin check")
    }

    @Test
    fun `the rejection of an upload never echoes what the client sent`() = runBlocking {
        val outcome = service.generate(OWNER, received(contentType = "application/x-evil<script>"))

        val invalid = assertIs<GenerationOutcome.Invalid>(outcome)
        assertTrue(!invalid.message.contains("evil"), "A fixed text, not the client's input")
    }

    @Test
    fun `an empty balance is answered without loading a prompt or generating`() = runBlocking {
        coins.affordable = OperationResult.Success(false)

        val outcome = service.generate(OWNER, received())

        assertEquals(GenerationOutcome.InsufficientCoins, outcome)
        assertEquals(listOf(HAS_ENOUGH), calls)
    }

    @Test
    fun `a broken balance lookup is a failure of ours, never an empty balance`() = runBlocking {
        coins.affordable = OperationResult.UnexpectedFailure

        val outcome = service.generate(OWNER, received())

        assertEquals(GenerationOutcome.UnexpectedFailure, outcome)
        assertEquals(listOf(HAS_ENOUGH), calls)
    }

    @Test
    fun `an unusable prompt is not generated from and not charged for`() = runBlocking {
        prompts.text = null

        val outcome = service.generate(OWNER, received())

        assertEquals(GenerationOutcome.PromptUnavailable, outcome)
        assertEquals(listOf(HAS_ENOUGH, COMPOSED_TEXT), calls)
    }

    @Test
    fun `a prompt lookup that fails becomes a failure of ours, not a missing prompt`() =
        runBlocking {
            prompts.failure = IllegalStateException("connection reset")

            val outcome = service.generate(OWNER, received())

            assertEquals(GenerationOutcome.UnexpectedFailure, outcome)
            assertEquals(listOf(HAS_ENOUGH, COMPOSED_TEXT), calls)
        }

    @Test
    fun `a cancelled prompt lookup stays a cancellation`() = runBlocking {
        prompts.failure = CancellationException("the visitor left")

        assertFailsWith<CancellationException> { service.generate(OWNER, received()) }
        assertTrue(!calls.contains(SPEND))
    }

    @Test
    fun `a provider that delivers no image is not charged for`() = runBlocking {
        generator.result = null

        val outcome = service.generate(OWNER, received())

        assertEquals(GenerationOutcome.UpstreamFailure, outcome)
        assertEquals(listOf(HAS_ENOUGH, COMPOSED_TEXT, GENERATE), calls)
    }

    /**
     * The visitor closes the tab while the provider is working. The image has been produced and
     * paid for at the provider by then, so the coin has to be spent even though every suspending
     * step of the request is now cancelled — which is what `NonCancellable` around the spend is
     * for, and what the suspending coins fake makes provable.
     */
    @Test
    fun `the coin is spent even when the request is cancelled during generation`() = runBlocking {
        lateinit var job: Job
        generator.onGenerate = { job.cancel() }

        job = launch(Dispatchers.Default) { service.generate(OWNER, received()) }
        job.join()

        assertEquals(listOf(HAS_ENOUGH, COMPOSED_TEXT, GENERATE, SPEND), calls)
    }
}
