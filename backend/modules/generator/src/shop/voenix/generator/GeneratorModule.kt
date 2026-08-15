package shop.voenix.generator

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import shop.voenix.auth.GuestTokens
import shop.voenix.magiccoins.GenerationCoins
import shop.voenix.prompt.PromptCatalog
import shop.voenix.ratelimit.ClientIpRateLimiter

/**
 * The runtime handle of the generator module. It is internal because the module exports no
 * capability: nothing in this backend asks it for anything, the storefront does.
 *
 * The handle owns one piece of lifecycle, the image generator's resources — an HTTP client in a
 * real deployment — and closes them when the application stops.
 */
internal class GeneratorModule(
    private val operations: GeneratorOperations,
    private val guestTokens: GuestTokens,
    private val rateLimiter: ClientIpRateLimiter,
    private val closeable: AutoCloseable,
) {
    fun install(application: Application) {
        application.installGeneratorRoutes(operations, guestTokens, rateLimiter)
        application.monitor.subscribe(ApplicationStopped) { closeable.close() }
    }
}

@Suppress("LongParameterList")
internal fun createGeneratorModule(
    prompts: PromptCatalog,
    coins: GenerationCoins,
    generator: ImageGenerator,
    guestTokens: GuestTokens,
    rateLimiter: ClientIpRateLimiter,
    closeable: AutoCloseable = AutoCloseable {},
): GeneratorModule =
    GeneratorModule(
        GeneratorService(coins, prompts, generator),
        guestTokens,
        rateLimiter,
        closeable,
    )

/**
 * Installs the generator against the image provider [settings] selects: the dummy generator in
 * dummy mode, the fal.ai adapter otherwise.
 *
 * This is the whole composition seam of the module. Which generator runs is decided exactly here,
 * once, and no code behind it knows the difference.
 */
public fun Application.installGeneratorModule(
    settings: GeneratorSettings,
    prompts: PromptCatalog,
    coins: GenerationCoins,
    guestTokens: GuestTokens,
    rateLimiter: ClientIpRateLimiter,
): Unit = generatorModule(settings, prompts, coins, guestTokens, rateLimiter).install(this)

/**
 * Dummy mode hands the uploaded image straight back; every other deployment talks to fal.ai.
 *
 * Only one of the two owns resources, and that is why the branch builds the whole module instead of
 * just the generator: the fal.ai adapter is handed to [createGeneratorModule] as its closeable, so
 * its HTTP client is closed with the application, while the dummy has nothing to close.
 */
private fun generatorModule(
    settings: GeneratorSettings,
    prompts: PromptCatalog,
    coins: GenerationCoins,
    guestTokens: GuestTokens,
    rateLimiter: ClientIpRateLimiter,
): GeneratorModule =
    if (settings.dummyMode) {
        createGeneratorModule(
            prompts = prompts,
            coins = coins,
            generator = dummyImageGenerator(),
            guestTokens = guestTokens,
            rateLimiter = rateLimiter,
        )
    } else {
        FalImageGenerator(settings).let { fal ->
            createGeneratorModule(
                prompts = prompts,
                coins = coins,
                generator = fal,
                guestTokens = guestTokens,
                rateLimiter = rateLimiter,
                closeable = fal,
            )
        }
    }
