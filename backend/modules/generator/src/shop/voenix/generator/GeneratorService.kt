package shop.voenix.generator

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.magiccoins.GenerationCoins
import shop.voenix.magiccoins.MagicCoinsOwner
import shop.voenix.operation.OperationResult
import shop.voenix.prompt.PromptCatalog

/**
 * The order in which one generation happens: check the upload, check that the visitor can afford
 * it, load the prompt, generate, and only then spend the coin.
 *
 * That order is the legacy behavior and it is deliberate in both directions. The coin check runs
 * before the prompt is loaded, so a visitor without balance is told so instead of being sent
 * looking for a prompt they may not use anyway. The spend runs after the generation, so nobody pays
 * for an image the provider never delivered.
 *
 * The service never asks whether generation is real: [ImageGenerator] answers that question, and
 * dummy mode is chosen once at the composition seam.
 */
internal class GeneratorService(
    private val coins: GenerationCoins,
    private val prompts: PromptCatalog,
    private val generator: ImageGenerator,
) : GeneratorOperations {
    override suspend fun generate(
        owner: MagicCoinsOwner,
        upload: GenerationUpload,
    ): GenerationOutcome =
        when {
            upload !is GenerationUpload.Received -> upload.rejection()
            upload.image.contentType.trim().lowercase() !in ALLOWED_IMAGE_CONTENT_TYPES ->
                GenerationOutcome.Invalid(IMAGE_PART_NAME, UNSUPPORTED_CONTENT_TYPE_MESSAGE)
            else -> generateForOwner(owner, upload)
        }

    /**
     * An infrastructure failure of the balance lookup becomes an unexpected failure, never an
     * insufficient balance: answering a broken database with "not enough Magic Coins" would tell a
     * customer to pay for a defect that is not theirs.
     */
    private suspend fun generateForOwner(
        owner: MagicCoinsOwner,
        received: GenerationUpload.Received,
    ): GenerationOutcome =
        when (val affordable = coins.hasEnoughForGeneration(owner)) {
            is OperationResult.Success ->
                if (affordable.value) {
                    generateForPrompt(owner, received)
                } else {
                    GenerationOutcome.InsufficientCoins
                }
            else -> GenerationOutcome.UnexpectedFailure
        }

    private suspend fun generateForPrompt(
        owner: MagicCoinsOwner,
        received: GenerationUpload.Received,
    ): GenerationOutcome {
        val lookup = runCatching { prompts.composedText(received.promptId) }
        val prompt = lookup.getOrElse {
            return promptFailure(it, received.promptId)
        }
        return when (prompt) {
            null -> GenerationOutcome.PromptUnavailable
            else -> generateAndCharge(owner, received.image, prompt)
        }
    }

    /**
     * The spend runs inside `NonCancellable`. Once the visitor's request is cancelled — a closed
     * browser tab is enough — every suspending step of an ordinary spend aborts before it does
     * anything, and the one case where the image was already produced and paid for at the provider
     * would be exactly the case in which nothing is charged. A failed spend only warns: the image
     * exists, and refusing to hand it over would punish the customer for a defect on our side.
     */
    private suspend fun generateAndCharge(
        owner: MagicCoinsOwner,
        image: RawImage,
        prompt: String,
    ): GenerationOutcome {
        val generated =
            generator.generate(image, prompt) ?: return GenerationOutcome.UpstreamFailure
        withContext(NonCancellable) {
            if (!coins.trySpendForGeneration(owner)) {
                logger.warn("Magic Coin spend failed after a successful generation")
            }
        }
        return GenerationOutcome.Generated(generated)
    }

    /**
     * What a prompt lookup that did not answer means here: a 500, never the 404 of an unknown
     * prompt and never a 402. The catalog lets an unexpected database failure surface as an
     * exception on purpose, so this module decides what such a failure means for its own contract.
     *
     * A [CancellationException] is not a failure of the lookup at all — it is the request ending —
     * and is rethrown so it keeps controlling the coroutine's lifecycle.
     */
    private fun promptFailure(
        failure: Throwable,
        promptId: Long,
    ): GenerationOutcome {
        if (failure is CancellationException) throw failure
        logger.error("Prompt $promptId could not be loaded for a generation", failure)
        return GenerationOutcome.UnexpectedFailure
    }

    private fun GenerationUpload.rejection(): GenerationOutcome.Invalid =
        when (this) {
            GenerationUpload.MissingImage ->
                GenerationOutcome.Invalid(IMAGE_PART_NAME, MISSING_IMAGE_MESSAGE)
            GenerationUpload.TooLarge ->
                GenerationOutcome.Invalid(IMAGE_PART_NAME, TOO_LARGE_MESSAGE)
            GenerationUpload.MissingPromptId ->
                GenerationOutcome.Invalid(PROMPT_ID_PART_NAME, MISSING_PROMPT_ID_MESSAGE)
            is GenerationUpload.Received -> error("A received upload is not a rejection")
        }

    private companion object {
        const val MISSING_IMAGE_MESSAGE = "An image file is required"
        const val TOO_LARGE_MESSAGE =
            "Image files may carry at most 10 MiB each and 20 MiB per request"
        const val UNSUPPORTED_CONTENT_TYPE_MESSAGE = "Image must be a JPEG, PNG, or WebP file"
        const val MISSING_PROMPT_ID_MESSAGE = "A numeric prompt id is required"

        val logger: Logger = LoggerFactory.getLogger(GeneratorService::class.java)
    }
}

/**
 * The one operation of this module, free of Ktor: generate an image for [MagicCoinsOwner] from what
 * a request carried.
 *
 * The seam exists so the routes can be tested against a stub that records whether it was reached at
 * all — which is how "a request without a CSRF token never generates anything" becomes a provable
 * statement instead of a claim about status codes.
 */
internal fun interface GeneratorOperations {
    suspend fun generate(
        owner: MagicCoinsOwner,
        upload: GenerationUpload,
    ): GenerationOutcome
}

/**
 * How one generation ended.
 *
 * The module carries its own outcome type instead of the shared `OperationResult` because three of
 * its answers have no equivalent there: a payment answer (402), an upstream answer (502), and a
 * missing prompt that is not the missing resource of the request path. Building them out of
 * `NotFound` and `Conflict` would give two callers different meanings for the same variant, which
 * is the mistake `CartPromotionResult` avoided.
 */
internal sealed interface GenerationOutcome {
    data class Generated(val image: RawImage) : GenerationOutcome

    /** [message] is a fixed text under the [field] the client has to fix; it never echoes input. */
    data class Invalid(val field: String, val message: String) : GenerationOutcome

    data object InsufficientCoins : GenerationOutcome

    data object PromptUnavailable : GenerationOutcome

    /** The image provider did not deliver an image. */
    data object UpstreamFailure : GenerationOutcome

    data object UnexpectedFailure : GenerationOutcome
}
