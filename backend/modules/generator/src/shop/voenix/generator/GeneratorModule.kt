package shop.voenix.generator

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import shop.voenix.article.ArticleCatalog
import shop.voenix.auth.GuestTokens
import shop.voenix.magiccoins.GenerationCoins
import shop.voenix.prompt.PromptCatalog
import shop.voenix.ratelimit.ClientIpRateLimiter

/**
 * The runtime handle of the generator module. It is internal because the module exports no
 * capability: nothing in this backend asks it for anything, the storefront does.
 *
 * The handle carries the image generator's resources — an HTTP client in a real deployment — as its
 * [closeable]; [installGeneratorModule] is what ties them to the application's shutdown.
 */
internal class GeneratorModule(
    val operations: GeneratorOperations,
    val closeable: AutoCloseable,
)

internal fun createGeneratorModule(
    articles: ArticleCatalog,
    prompts: PromptCatalog,
    coins: GenerationCoins,
    generator: ImageGenerator,
    closeable: AutoCloseable = AutoCloseable {},
): GeneratorModule =
    GeneratorModule(GeneratorService(coins, articles, prompts, generator), closeable)

/**
 * Installs the generator against the image provider [settings] selects: the dummy generator in
 * dummy mode, the fal.ai adapter otherwise.
 *
 * This is the whole composition seam of the module. Which generator runs is decided exactly here,
 * once, and no code behind it knows the difference. Each parameter is one capability another module
 * exports, so the list grows with the module's real dependencies — the same trade the other
 * installers make.
 */
@Suppress("LongParameterList")
public fun Application.installGeneratorModule(
    settings: GeneratorSettings,
    articles: ArticleCatalog,
    prompts: PromptCatalog,
    coins: GenerationCoins,
    guestTokens: GuestTokens,
    rateLimiter: ClientIpRateLimiter,
) {
    val module = generatorModule(settings, articles, prompts, coins)
    installGeneratorRoutes(module.operations, guestTokens, rateLimiter)
    monitor.subscribe(ApplicationStopped) { module.closeable.close() }
}

/**
 * Dummy mode hands the uploaded image straight back; every other deployment talks to fal.ai.
 *
 * Only one of the two owns resources, and that is why the branch builds the whole module instead of
 * just the generator: the fal.ai adapter is handed to [createGeneratorModule] as its closeable, so
 * its HTTP client is closed with the application, while the dummy has nothing to close.
 */
private fun generatorModule(
    settings: GeneratorSettings,
    articles: ArticleCatalog,
    prompts: PromptCatalog,
    coins: GenerationCoins,
): GeneratorModule =
    if (settings.dummyMode) {
        createGeneratorModule(
            articles = articles,
            prompts = prompts,
            coins = coins,
            generator = dummyImageGenerator(),
        )
    } else {
        FalImageGenerator(settings).let { fal ->
            createGeneratorModule(
                articles = articles,
                prompts = prompts,
                coins = coins,
                generator = fal,
                closeable = fal,
            )
        }
    }
