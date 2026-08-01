package shop.voenix.order

import java.nio.file.Path
import java.sql.SQLException
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.article.ArticleCatalog
import shop.voenix.article.ArticleVariantReference
import shop.voenix.email.EmailOutbox
import shop.voenix.email.EmailRecipient
import shop.voenix.email.QueuedEmail
import shop.voenix.email.QueuedEmailReference
import shop.voenix.image.PrivateImageStorage
import shop.voenix.operation.OperationResult
import shop.voenix.production.ProductionData
import shop.voenix.production.ProductionItem
import shop.voenix.production.ProductionOutbox
import shop.voenix.promotion.PromotionCodes

/**
 * What an order *means*, between the routes above it and the tables below it.
 *
 * The service owns the three decisions the repository deliberately does not have:
 *
 * - **snapshotting**: a placement asks [ArticleCatalog] once what every ordered line is — names,
 *   supplier article number, and the five print measurements — and those values go onto the order.
 *   A later catalog change never moves them, and a reference the catalog does not know at all
 *   rejects the placement instead of storing an empty name (deviation D18);
 * - **who may see an order**: reads carry the caller's identity into the repository predicate, and
 *   a miss is [OperationResult.NotFound] whether the order does not exist or belongs to somebody
 *   else;
 * - **what a paid order sets in motion**: the redemption, the production request, and the
 *   confirmation mail are handed to the repository as work for its transaction, so they exist
 *   exactly if the payment does.
 *
 * It is also the module's [OrderPaymentGateway]: [confirm] and [cancel] are the two writes the
 * payment module is given, and they are where the internal results are translated into the four
 * exported ones. Everything richer than those four stays here.
 *
 * It also answers the two workers that come back for the order *after* it was paid — production
 * ([productionData]) and the confirmation mail ([orderConfirmation]). Both read the stored order
 * again on every attempt, and both are deliberately without an ownership predicate: a worker is not
 * a customer.
 *
 * The two error policies differ on purpose. The read operations serve HTTP, so an unexpected
 * database failure is logged once and becomes `UnexpectedFailure`. Placement and payment
 * confirmation serve future modules instead, and let that failure surface as an exception together
 * with the rollback that caused it — there is nothing to compensate, because the transaction *is*
 * the compensation. A `CancellationException` is always rethrown.
 *
 * Nothing here ever logs the guest session token. It is the bearer credential of an anonymous
 * customer, and the legacy checkout wrote it into the log of every order it created (deviation
 * D17).
 */
internal class OrderService(
    private val repository: OrderRepository,
    private val articles: ArticleCatalog,
    private val promotions: PromotionCodes,
    private val productionOutbox: ProductionOutbox,
    private val emailOutbox: EmailOutbox,
    private val printImages: PrivateImageStorage,
) : OrderOperations, OrderPaymentGateway {
    override suspend fun history(
        userId: Long?,
        guestToken: String?,
    ): OperationResult<List<OrderView>> =
        databaseOperation("Database error while reading the order history") {
            OperationResult.Success(repository.history(userId, guestToken))
        }

    override suspend fun order(
        orderId: Long,
        userId: Long?,
        guestToken: String?,
    ): OperationResult<OrderView> =
        databaseOperation("Database error while reading order $orderId") {
            when (val order = repository.order(orderId, userId, guestToken)) {
                null -> OperationResult.NotFound
                else -> OperationResult.Success(order)
            }
        }

    /**
     * Places one order: field rules first, then the catalog snapshot, then the write.
     *
     * The catalog is resolved in a single batched call for all lines, and every reference has to
     * come back. A line whose article or variant the catalog does not know cannot be produced, and
     * an order that cannot be produced must not be taken.
     */
    suspend fun place(input: PlaceOrderInput): OrderWriteResult {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OrderWriteResult.Invalid(errors)

        val references =
            input.lines.mapTo(mutableSetOf()) { line ->
                ArticleVariantReference(articleId = line.articleId, variantId = line.variantId)
            }
        val snapshots = articles.find(references)
        return when {
            snapshots.keys.containsAll(references) -> repository.place(input, snapshots)
            else -> OrderWriteResult.UnknownArticleReference
        }
    }

    /**
     * Confirms the payment of an order.
     *
     * Everything that makes this safe lives in the repository's single transaction; what the
     * service adds is the promotion capability, the two outbox writes it hands into that
     * transaction, and a warning for every outcome that changed nothing a caller can see.
     *
     * Three outcomes are worth a log line, because each of them means a payment was confirmed for
     * something the order module did not do:
     * - a refused redemption leaves a paid order without a redemption, and the warning names the
     *   reason the promotion module gave, so an exhausted limit can be told from a deleted
     *   promotion;
     * - an unknown order id means the payment belongs to nothing here;
     * - a cancelled order stays cancelled, and the confirmed payment has to be dealt with by hand.
     *
     * The lines name the order id and the outcome, never the customer's identity or the guest
     * token.
     */
    suspend fun markPaid(orderId: Long): PaidOrderResult {
        val result =
            repository.markPaid(
                orderId = orderId,
                redeem = { promotionId, userId -> promotions.redeem(promotionId, orderId, userId) },
                announce = { paidOrderId ->
                    productionOutbox.request(paidOrderId)
                    emailOutbox.enqueue(QueuedEmailReference.OrderConfirmation(paidOrderId))
                },
            )
        when (result) {
            is PaidOrderResult.PromotionRefused ->
                logger.warn(
                    "Order {} was paid without redeeming its promotion: {}",
                    orderId,
                    result.reason,
                )
            PaidOrderResult.NotFound ->
                logger.warn("Payment confirmation for order {} found no such order", orderId)
            PaidOrderResult.Cancelled ->
                logger.warn(
                    "Payment confirmation for order {} changed nothing: the order is CANCELLED",
                    orderId,
                )
            PaidOrderResult.Paid,
            PaidOrderResult.AlreadyPaid -> Unit
        }
        return result
    }

    /**
     * The payment module's confirmation, in the four words it needs.
     *
     * The mapping is the boundary decision of this module (deviation D13): five internal results
     * become four exported ones, and `PromotionRefused` is one of the [OrderPaymentOutcome.APPLIED]
     * ones. A paid order whose coupon could not be redeemed is a *promotion* problem — the warning
     * above already names it — and telling a payment about it would only invite it to treat a paid
     * order as a failed payment.
     */
    override suspend fun confirm(orderId: Long): OrderPaymentOutcome =
        when (markPaid(orderId)) {
            PaidOrderResult.Paid,
            is PaidOrderResult.PromotionRefused -> OrderPaymentOutcome.APPLIED
            PaidOrderResult.AlreadyPaid -> OrderPaymentOutcome.ALREADY_APPLIED
            PaidOrderResult.NotFound -> OrderPaymentOutcome.UNKNOWN_ORDER
            PaidOrderResult.Cancelled -> OrderPaymentOutcome.REFUSED
        }

    /**
     * Cancels an order whose payment will not happen.
     *
     * Everything that decides this lives in the repository's locked transaction; what the service
     * adds is the trace for the two outcomes that changed nothing: a payment failure for an order
     * that does not exist here, and one for an order that is already `PAID` — where the customer
     * has been charged and the order stays exactly as it is.
     */
    override suspend fun cancel(orderId: Long): OrderPaymentOutcome {
        val outcome = repository.markCancelled(orderId)
        when (outcome) {
            OrderPaymentOutcome.UNKNOWN_ORDER ->
                logger.warn("Payment cancellation for order {} found no such order", orderId)
            OrderPaymentOutcome.REFUSED ->
                logger.warn(
                    "Payment cancellation for order {} changed nothing: the order is PAID",
                    orderId,
                )
            OrderPaymentOutcome.APPLIED,
            OrderPaymentOutcome.ALREADY_APPLIED -> Unit
        }
        return outcome
    }

    /**
     * What production has to make: the shipping address of the order and one item per stored line,
     * in the order the customer put them together.
     *
     * Three of those values are not read from the order, and each for its own reason:
     *
     * - the **supplier** is resolved live through the catalog, because an article that has no
     *   supplier assigned yet must stay repairable: production reports the item as retryably
     *   invalid, an admin assigns the supplier, and the very same request succeeds on the next scan
     *   (decision 7, deviation D24). A reference the catalog no longer knows at all answers the
     *   same way — `supplierId = null` — instead of losing the line;
     * - the **image path** comes from the image module by file name. A name it cannot resolve
     *   leaves `imagePath = null`, which production treats as a retryable generation failure rather
     *   than rendering a blank page;
     * - the five **measurements** are the ones snapshotted at placement, never today's, so a
     *   catalog edit cannot silently re-lay-out an order that is already in production.
     *
     * `null` means the order does not exist. Everything else — a database failure, an unusable
     * image storage — surfaces as an exception, which every production stage records as the
     * retryable `SOURCE_UNAVAILABLE`. That is the whole error policy here: a worker must never
     * mistake "we could not look" for "there is nothing".
     */
    suspend fun productionData(orderId: Long): ProductionData? {
        val order = repository.storedOrder(orderId) ?: return null
        val suppliers = articles.find(order.lines.mapTo(mutableSetOf()) { line -> line.reference })
        val paths = printImagePaths(order)
        return ProductionData(
            orderId = order.orderId,
            orderDate = order.orderDate,
            shippingFirstName = order.shippingAddress.firstName,
            shippingLastName = order.shippingAddress.lastName,
            shippingStreet = order.shippingAddress.street,
            shippingHouseNumber = order.shippingAddress.houseNumber,
            shippingPostalCode = order.shippingAddress.postalCode,
            shippingCity = order.shippingAddress.city,
            shippingCountry = order.shippingAddress.country,
            items =
                order.lines.map { line ->
                    ProductionItem(
                        supplierId = suppliers[line.reference]?.supplierId,
                        articleName = line.articleName,
                        supplierArticleNumber = line.supplierArticleNumber,
                        variantName = line.variantName,
                        quantity = line.quantity,
                        imagePath = line.printImageFilename?.let(paths::get),
                        printTemplateWidthMm = line.printTemplateWidthMm?.toDouble(),
                        printTemplateHeightMm = line.printTemplateHeightMm?.toDouble(),
                        documentFormatWidthMm = line.documentFormatWidthMm?.toDouble(),
                        documentFormatHeightMm = line.documentFormatHeightMm?.toDouble(),
                        documentFormatMarginBottomMm =
                            line.documentFormatMarginBottomMm?.toDouble(),
                    )
                },
        )
    }

    /**
     * The confirmation mail of one order, built from what the order stored.
     *
     * Every value is read again per attempt — the recipient included — so an address corrected
     * between two attempts reaches the customer, while the amounts and the items stay the ones that
     * were paid for. `null` means the order is gone; the mail worker leaves the job open for a
     * later scan rather than dropping it.
     *
     * The line price the customer sees is the article price plus the prompt price, exactly as the
     * cart charged it, so the items add up to the stored subtotal. Subtotal and discount are stored
     * columns rather than the "total minus shipping" the legacy mail computed (deviation D12) — a
     * fully discounted order is a normal mail here, not an invariant violation that retries
     * forever.
     */
    suspend fun orderConfirmation(reference: QueuedEmailReference): QueuedEmail? {
        require(reference is QueuedEmailReference.OrderConfirmation) {
            "The order module resolves only order confirmations"
        }
        val order = repository.storedOrder(reference.orderId) ?: return null
        return QueuedEmail.OrderConfirmation(
            recipient = EmailRecipient(order.email),
            orderId = order.orderId,
            orderDate = order.orderDate,
            customerFirstName = order.shippingAddress.firstName,
            shippingAddress = order.shippingAddress.toEmailAddress(),
            billingAddress = order.billingAddress.toEmailAddress(),
            items =
                order.lines.map { line ->
                    QueuedEmail.OrderConfirmation.Item(
                        articleName = line.articleName,
                        variantName = line.variantName,
                        quantity = line.quantity,
                        unitPriceInCents = (line.priceCents + line.promptPriceCents).toLong(),
                    )
                },
            subtotalInCents = order.subtotalCents.toLong(),
            shippingCostInCents = order.shippingCostCents.toLong(),
            discountInCents = order.discountCents.toLong(),
            totalInCents = order.totalCents.toLong(),
        )
    }

    /**
     * The readable original behind every print-image name of [order], keyed by that name.
     *
     * A name the image module cannot answer for is simply absent, and the line renders as "image
     * missing". A storage that cannot answer *at all* is a different thing and must not look like
     * one: it throws, and the production stage retries.
     */
    private suspend fun printImagePaths(order: StoredOrder): Map<String, Path> {
        val names = order.lines.mapNotNullTo(mutableSetOf(), StoredOrder.Line::printImageFilename)
        return when (val result = printImages.originalPaths(names)) {
            is OperationResult.Success -> result.value
            else -> error("Print image storage did not answer for order ${order.orderId}: $result")
        }
    }

    private suspend fun <T> databaseOperation(
        message: String,
        operation: suspend () -> OperationResult<T>,
    ): OperationResult<T> =
        try {
            operation()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: SQLException) {
            logger.error(message, exception)
            OperationResult.UnexpectedFailure
        }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(OrderService::class.java)
    }
}

/** What the catalog is asked about a stored line: the pair the line was placed with. */
private val StoredOrder.Line.reference: ArticleVariantReference
    get() = ArticleVariantReference(articleId = articleId, variantId = variantId)

private fun StoredOrder.Address.toEmailAddress(): QueuedEmail.OrderConfirmation.Address =
    QueuedEmail.OrderConfirmation.Address(
        firstName = firstName,
        lastName = lastName,
        street = street,
        houseNumber = houseNumber,
        city = city,
        postalCode = postalCode,
        country = country,
    )
