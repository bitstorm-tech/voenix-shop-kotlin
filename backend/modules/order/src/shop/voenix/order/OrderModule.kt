package shop.voenix.order

import io.ktor.server.application.Application
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.article.ArticleCatalog
import shop.voenix.auth.GuestTokens
import shop.voenix.email.EmailOutbox
import shop.voenix.email.QueuedEmailSource
import shop.voenix.http.FrontendBaseUrl
import shop.voenix.image.PrivateImageStorage
import shop.voenix.production.ProductionOutbox
import shop.voenix.production.ProductionPdfGenerator
import shop.voenix.production.ProductionSource
import shop.voenix.production.fulfillment.FulfillmentOrderSource
import shop.voenix.promotion.PromotionCodes

/**
 * The runtime handle of the installed order module.
 *
 * It is public because the composition root passes the module's exported capabilities on after the
 * install: [placement] for the two calls the checkout module makes, [orderItems] for the cart's
 * reorder route, [payments] for the three writes the payment module is allowed to make,
 * [productionSource] for everything production makes of a paid order, [fulfillmentOrders] for the
 * order header a supplier sees on the job it has to ship, and [orderConfirmations] for the mail the
 * customer receives. Everything behind them — the operations, the service, the repository, the
 * tables — stays internal.
 *
 * [fulfillmentOrders] is deliberately a second, much narrower production port next to
 * [productionSource]: what a supplier's screen may show is not what the PDF renderer needs, and
 * keeping the two apart is what makes the data minimization structural instead of a filter someone
 * has to remember.
 *
 * The last three are the ports two *earlier* modules declared and left open, which is why they are
 * exported rather than installed: production and email are running long before an order exists, and
 * the composition root hands them their implementation once this module is installed. [placement]
 * and [payments] are the opposite direction: this module declares *and* implements them, and
 * *later* modules — checkout and payment — are the ones that receive them.
 *
 * The parameter list is long because the capabilities *are* the list, one per consumer. There is
 * nothing to group here that would not just be a second handle to unpack.
 */
@Suppress("LongParameterList")
public class OrderModule
internal constructor(
    internal val operations: OrderOperations,
    public val placement: OrderPlacement,
    public val orderItems: OrderItemReader,
    public val payments: OrderPaymentGateway,
    public val productionSource: ProductionSource,
    public val fulfillmentOrders: FulfillmentOrderSource,
    public val orderConfirmations: QueuedEmailSource,
)

/**
 * Assembles the order module.
 *
 * The six capabilities are the whole outside world an order needs: [articles] is what a placement
 * snapshots and what production asks for the current supplier, [promotions] is redeemed when a
 * payment is confirmed, [emailOutbox] is the side effect that joins the *placing* transaction and
 * [productionOutbox] the one that joins the *paying* one, [printImages] turns the stored image
 * names back into readable files, and [payments] answers the `paymentStatus` of an order that is
 * read.
 *
 * [frontendBaseUrl] is not a capability but a setting, and the only one this module has: it is
 * where the permanent order link of the confirmation mail points. See [OrderLinks].
 *
 * [payments] is the one capability whose implementation is installed *after* this module — the
 * payment module implements it, and the composition root hands the order module a late-bound source
 * that it binds afterwards. Nothing here may call it during installation.
 *
 * The two exported ports are plain lambdas over the service. They are pass-throughs by design:
 * `ProductionSource` and `QueuedEmailSource` are the *consumers'* interfaces, and giving the order
 * module a class per port would only add names for the same two calls. `OrderPaymentGateway` is
 * different — it is *this* module's interface — and the service implements it directly.
 */
@Suppress("LongParameterList")
internal fun createOrderModule(
    database: Database,
    frontendBaseUrl: FrontendBaseUrl,
    articles: ArticleCatalog,
    promotions: PromotionCodes,
    productionOutbox: ProductionOutbox,
    emailOutbox: EmailOutbox,
    printImages: PrivateImageStorage,
    payments: OrderPaymentStatusSource,
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
            paymentStatuses = payments,
            links = OrderLinks(frontendBaseUrl),
        )
    return OrderModule(
        operations = service,
        placement = service,
        orderItems =
            OrderItemReader { orderItemId, userId, guestToken ->
                repository.orderItem(orderItemId, userId, guestToken)
            },
        payments = service,
        productionSource = ProductionSource { orderId -> service.productionData(orderId) },
        fulfillmentOrders =
            FulfillmentOrderSource { orderIds -> repository.fulfillmentOrders(orderIds) },
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
 * [frontendBaseUrl], [productionPdfs], and [guestTokens] are what this module needs beyond an order
 * itself: the base URL is what the mailed order link is built from, the admin download generates
 * its documents through the production module, and the customer routes read the guest cookie to
 * know whose orders they are answering. Everything else is what an order *is*, and is documented on
 * [createOrderModule].
 *
 * Install it after image, article, promotion, production, and email, then hand the exported
 * capabilities on: [OrderModule.placement] to the checkout module, [OrderModule.orderItems] to the
 * cart, [OrderModule.productionSource] and [OrderModule.orderConfirmations] to the two ports
 * production and email have been waiting on, and [OrderModule.payments] to the payment module,
 * which is installed after this one — and whose status source is then bound into the [payments]
 * handed in here.
 */
@Suppress("LongParameterList")
public fun Application.installOrderModule(
    database: Database,
    frontendBaseUrl: FrontendBaseUrl,
    articles: ArticleCatalog,
    promotions: PromotionCodes,
    productionOutbox: ProductionOutbox,
    emailOutbox: EmailOutbox,
    printImages: PrivateImageStorage,
    payments: OrderPaymentStatusSource,
    productionPdfs: ProductionPdfGenerator,
    guestTokens: GuestTokens,
): OrderModule =
    createOrderModule(
            database,
            frontendBaseUrl,
            articles,
            promotions,
            productionOutbox,
            emailOutbox,
            printImages,
            payments,
        )
        .also { module -> installOrderModule(module.operations, productionPdfs, guestTokens) }
