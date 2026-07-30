package shop.voenix.cart

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.ArticleCatalog
import shop.voenix.auth.GuestTokens
import shop.voenix.image.PrivateImageStorage
import shop.voenix.promotion.PromotionCodes
import shop.voenix.prompt.PromptCatalog
import shop.voenix.validation.toRequestValidationResult

/**
 * The runtime handle of the installed cart module.
 *
 * Unlike Article's or Prompt's it is public, because the composition root needs the two
 * capabilities the cart *exports* after it is installed: [guestImages] for the image module's guest
 * delivery route, and [guestData] for the claim the account module runs after a login. Everything
 * behind them — the operations, the service, the repository, the tables — stays internal.
 */
public class CartModule
internal constructor(
    internal val operations: CartOperations,
    public val guestImages: CartGuestImages,
    public val guestData: CartGuestData,
    private val guestTokens: GuestTokens,
) {
    internal fun install(application: Application): Unit =
        CartRoutes.install(application, operations, guestTokens)
}

@Suppress("LongParameterList")
internal fun createCartModule(
    database: Database,
    articles: ArticleCatalog,
    prompts: PromptCatalog,
    promotions: PromotionCodes,
    printImageStorage: PrivateImageStorage,
    guestTokens: GuestTokens,
): CartModule {
    val repository = CartRepository(database)
    return CartModule(
        operations =
            CartService(
                repository = repository,
                articles = articles,
                prompts = prompts,
                promotions = promotions,
                printImages = printImageStorage,
            ),
        guestImages = CartGuestImages(repository),
        guestData = CartGuestData(repository),
        guestTokens = guestTokens,
    )
}

/** The route test seam: installs the cart routes on a caller-provided implementation. */
internal fun Application.installCartModule(
    carts: CartOperations,
    guestTokens: GuestTokens,
): Unit = CartRoutes.install(this, carts, guestTokens)

/**
 * Installs the seven cart routes and returns the handle with the module's exported capabilities.
 *
 * The four capability parameters are the whole reason the cart is the first module to bind them:
 * [articles] prices a line and renders it, [prompts] prices the prompt a line was generated with,
 * [promotions] validates the coupon code a cart carries, and [printImageStorage] holds the uploaded
 * originals. [guestTokens] is the guest identity behind every anonymous cart.
 *
 * Install it after image, article, prompt, and promotion, and install the image module's guest
 * delivery route afterwards with [CartModule.guestImages].
 */
@Suppress("LongParameterList")
public fun Application.installCartModule(
    database: Database,
    articles: ArticleCatalog,
    prompts: PromptCatalog,
    promotions: PromotionCodes,
    printImageStorage: PrivateImageStorage,
    guestTokens: GuestTokens,
): CartModule =
    createCartModule(database, articles, prompts, promotions, printImageStorage, guestTokens)
        .also { module -> module.install(this) }

public fun RequestValidationConfig.validateCartRequests() {
    validate<AddCartItemInput> { input -> input.toRequestValidationResult() }
    validate<CartQuantityInput> { input -> input.toRequestValidationResult() }
    validate<PromotionCodeInput> { input -> input.toRequestValidationResult() }
}
