package shop.voenix.generator

import io.ktor.server.application.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import shop.voenix.auth.AuthSettings
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.installAuthModule
import shop.voenix.http.installHttpRuntime
import shop.voenix.magiccoins.GenerationCoins
import shop.voenix.magiccoins.MagicCoinsOwner
import shop.voenix.operation.OperationResult
import shop.voenix.prompt.PromptCatalog

/**
 * The stand-ins every generator test shares. All three capabilities the module consumes prove their
 * own rules in their own modules; what the generator has to prove is the order it calls them in and
 * what it does with their answers — so all three record into one shared call log.
 */
internal object GeneratorTestSupport {
    const val PROMPT_ID: Long = 42L
    val OWNER: MagicCoinsOwner = MagicCoinsOwner.User(7)

    fun image(
        contentType: String = "image/png",
        bytes: ByteArray = byteArrayOf(1, 2, 3),
    ): RawImage = RawImage(bytes, contentType)

    fun received(
        contentType: String = "image/png",
        bytes: ByteArray = byteArrayOf(1, 2, 3),
        promptId: Long = PROMPT_ID,
    ): GenerationUpload.Received = GenerationUpload.Received(image(contentType, bytes), promptId)

    /**
     * The coin capability, recording both of its calls.
     *
     * Both methods dispatch to `Dispatchers.IO` and yield, exactly like the real service does
     * through its repository. That is not decoration: the dispatch is the step a cancelled job
     * breaks, so a fake that answered without suspending would let the cancellation test pass even
     * if the spend were not protected by `NonCancellable` at all.
     */
    class FakeCoins(
        private val calls: MutableList<String>,
        var affordable: OperationResult<Boolean> = OperationResult.Success(true),
        var spends: Boolean = true,
    ) : GenerationCoins {
        override suspend fun hasEnoughForGeneration(
            owner: MagicCoinsOwner
        ): OperationResult<Boolean> =
            withContext(Dispatchers.IO) {
                yield()
                calls += HAS_ENOUGH
                affordable
            }

        override suspend fun trySpendForGeneration(owner: MagicCoinsOwner): Boolean =
            withContext(Dispatchers.IO) {
                yield()
                calls += SPEND
                spends
            }
    }

    /** The prompt catalog: an answer, or the database failure it is allowed to throw. */
    class FakePrompts(
        private val calls: MutableList<String>,
        var text: String? = "as a watercolor",
        var failure: Throwable? = null,
    ) : PromptCatalog {
        override suspend fun composedText(promptId: Long): String? {
            calls += COMPOSED_TEXT
            failure?.let { throw it }
            return text
        }

        override suspend fun findSalesGrossPriceCents(promptIds: Set<Long>): Map<Long, Int> =
            error("The generator never asks for prices")
    }

    /** The image provider: the generated image, or `null` for an upstream failure. */
    class FakeGenerator(
        private val calls: MutableList<String>,
        var result: RawImage? = image("image/jpeg", byteArrayOf(9, 9)),
        var onGenerate: (suspend () -> Unit)? = null,
    ) : ImageGenerator {
        val prompts: MutableList<String> = mutableListOf()

        override suspend fun generate(
            image: RawImage,
            prompt: String,
        ): RawImage? {
            calls += GENERATE
            prompts += prompt
            onGenerate?.invoke()
            return result
        }
    }

    /** Records whether the routes reached the operation at all, and with what. */
    class StubOperations(var outcome: GenerationOutcome = GenerationOutcome.UnexpectedFailure) :
        GeneratorOperations {
        val uploads: MutableList<GenerationUpload> = mutableListOf()

        override suspend fun generate(
            owner: MagicCoinsOwner,
            upload: GenerationUpload,
        ): GenerationOutcome {
            uploads += upload
            return outcome
        }
    }

    const val HAS_ENOUGH: String = "hasEnoughForGeneration"
    const val COMPOSED_TEXT: String = "composedText"
    const val GENERATE: String = "generate"
    const val SPEND: String = "trySpendForGeneration"

    const val SESSION_SECRET: String = "generator-route-test-session-secret"
}

/** The shared runtime a route test needs: content negotiation, sessions, CSRF, and the routes. */
internal fun Application.installGeneratorTestApplication(operations: GeneratorOperations) {
    val authSettings = AuthSettings(GeneratorTestSupport.SESSION_SECRET)
    installHttpRuntime()
    installAuthModule(authSettings)
    installGeneratorModule(operations, GuestTokens(authSettings))
}
