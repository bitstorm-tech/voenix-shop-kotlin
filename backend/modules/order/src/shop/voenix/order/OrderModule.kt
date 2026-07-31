package shop.voenix.order

import io.ktor.server.application.Application
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.ArticleCatalog
import shop.voenix.auth.GuestTokens
import shop.voenix.email.EmailOutbox
import shop.voenix.email.QueuedEmailSource
import shop.voenix.image.PrivateImageStorage
import shop.voenix.production.ProductionOutbox
import shop.voenix.production.ProductionPdfGenerator
import shop.voenix.production.ProductionSource
import shop.voenix.promotion.PromotionCodes

/**
 * The runtime handle of the installed order module.
 *
 * It is public because the composition root passes the module's exported capabilities on after the
 * install: [guestData] for the claim the account module runs after a login, [orderItems] for the
 * cart's reorder route, [productionSource] for everything production makes of a paid order, and
 * [orderConfirmations] for the mail the customer receives. Everything behind them — the operations,
 * the service, the repository, the tables — stays internal.
 *
 * The last two are the ports two *earlier* modules declared and left open, which is why they are
 * exported rather than installed: production and email are running long before an order exists, and
 * the composition root hands them their implementation once this module is installed.
 */
public class OrderModule
internal constructor(
    internal val operations: OrderOperations,
    public val guestData: OrderGuestData,
    public val orderItems: OrderItemReader,
    public val productionSource: ProductionSource,
    public val orderConfirmations: QueuedEmailSource,
)

/**
 * Assembles the order module.
 *
 * The five capabilities are the whole outside world an order needs: [articles] is what a placement
 * snapshots and what production asks for the current supplier, [promotions] is redeemed when a
 * payment is confirmed, [productionOutbox] and [emailOutbox] are the two side effects that join the
 * paying transaction, and [printImages] turns the stored image names back into readable files.
 *
 * The two exported ports are plain lambdas over the service. They are pass-throughs by design:
 * `ProductionSource` and `QueuedEmailSource` are the *consumers'* interfaces, and giving the order
 * module a class per port would only add names for the same two calls.
 */
@Suppress("LongParameterList")
internal fun createOrderModule(
    database: Database,
    articles: ArticleCatalog,
    promotions: PromotionCodes,
    productionOutbox: ProductionOutbox,
    emailOutbox: EmailOutbox,
    printImages: PrivateImageStorage,
): OrderModule {
    val repository = OrderRepository(database)
    val service =
        OrderService(
            repository = repository,
            articles = articles,
            promotions = promotions,
            productionOutbox = productionOutbox,
            emailOutbox = emailOutbox,
            printImages = printImages,
        )
    return OrderModule(
        operations = service,
        guestData = OrderGuestData(repository),
        orderItems =
            OrderItemReader { orderItemId, userId, guestToken ->
                repository.orderItem(orderItemId, userId, guestToken)
            },
        productionSource = ProductionSource { orderId -> service.productionData(orderId) },
        orderConfirmations =
            QueuedEmailSource { reference -> service.orderConfirmation(reference) },
    )
}

/** The route test seam: installs the order routes on caller-provided implementations. */
internal fun Application.installOrderModule(
    orders: OrderOperations,
    productionPdfs: ProductionPdfGenerator,
    guestTokens: GuestTokens,
): Unit = OrderRoutes.install(this, orders, productionPdfs, guestTokens)

/**
 * Installs the four order routes and returns the handle with the module's exported capabilities.
 *
 * [productionPdfs] and [guestTokens] are what the routes need and the operations do not: the admin
 * download generates its documents through the production module, and the customer routes read the
 * guest cookie to know whose orders they are answering. Everything else is what an order *is*, and
 * is documented on [createOrderModule].
 *
 * Install it after image, article, promotion, production, and email, then hand the four exported
 * capabilities on: [OrderModule.guestData] to the account module, [OrderModule.orderItems] to the
 * cart, [OrderModule.productionSource] and [OrderModule.orderConfirmations] to the two ports
 * production and email have been waiting on.
 */
@Suppress("LongParameterList")
public fun Application.installOrderModule(
    database: Database,
    articles: ArticleCatalog,
    promotions: PromotionCodes,
    productionOutbox: ProductionOutbox,
    emailOutbox: EmailOutbox,
    printImages: PrivateImageStorage,
    productionPdfs: ProductionPdfGenerator,
    guestTokens: GuestTokens,
): OrderModule =
    createOrderModule(database, articles, promotions, productionOutbox, emailOutbox, printImages)
        .also { module -> installOrderModule(module.operations, productionPdfs, guestTokens) }
