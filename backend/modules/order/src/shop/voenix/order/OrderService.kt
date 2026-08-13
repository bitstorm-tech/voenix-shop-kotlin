package shop.voenix.order

import java.nio.file.Path
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
import shop.voenix.operation.databaseOperation
import shop.voenix.production.ProductionData
import shop.voenix.production.ProductionItem
import shop.voenix.production.ProductionOutbox
import shop.voenix.production.fulfillment.ShippingNotificationOrder
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
 * - **what an order sets in motion**: the confirmation mail is handed to the *placement*
 *   transaction, and the redemption and the production request to the *payment* one, so each side
 *   effect exists exactly if the write that causes it does. The mail hangs on the placement because
 *   its link is the customer's durable handle to the order — they must have it whatever the payment
 *   then does (issue #110, Joe decision 3).
 *
 * It is also the module's two exported write capabilities. [OrderPaymentGateway] is what the
 * payment module is given — [confirm], [cancel], and [paymentEnded] — and it is where the internal
 * results are translated into the four exported ones; everything richer than those four stays here.
 * [OrderPlacement] is what the checkout module is given: [place] and the retry read [payable], both
 * of which answer with the exported [PayableOrder] snapshot rather than the internal [OrderView].
 *
 * The traffic in the other direction is [OrderPaymentStatusSource]: the two reads above ask it for
 * the `paymentStatus` of what they answer — the history in one batch call, the single order with a
 * refresh — and neither knows that a payment provider exists.
 *
 * It also answers the two workers that come back for the order later — production
 * ([productionData], after the payment) and the confirmation mail ([orderConfirmation], after the
 * placement). Both read the stored order again on every attempt, and both are deliberately without
 * an ownership predicate: a worker is not a customer.
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
@Suppress("LongParameterList")
internal class OrderService(
    private val repository: OrderRepository,
    private val articles: ArticleCatalog,
    private val promotions: PromotionCodes,
    private val productionOutbox: ProductionOutbox,
    private val emailOutbox: EmailOutbox,
    private val printImages: PrivateImageStorage,
    private val paymentStatuses: OrderPaymentStatusSource,
    private val links: OrderLinks,
) : OrderOperations, OrderPlacement, OrderPaymentGateway {
    /**
     * The history, with every order's stored payment status filled in by a *single* batch read.
     *
     * A history of twenty orders costs one status query and no provider call at all — which is the
     * whole reason [OrderPaymentStatusSource.stored] exists next to
     * [OrderPaymentStatusSource.refreshed].
     */
    override suspend fun history(
        userId: Long?,
        guestToken: String?,
    ): OperationResult<List<OrderView>> =
        logger.databaseOperation(
            "Database error while reading the order history",
            OperationResult.UnexpectedFailure,
        ) {
            val orders = repository.history(userId, guestToken)
            if (orders.isEmpty()) {
                OperationResult.Success(orders)
            } else {
                val statuses =
                    paymentStatuses.stored(orders.mapTo(mutableSetOf(), OrderView::orderId))
                OperationResult.Success(
                    orders.map { order -> order.copy(paymentStatus = statuses[order.orderId]) }
                )
            }
        }

    /**
     * One order, with a payment status the payment module may refresh from the provider first.
     *
     * The single read is the only place that refresh happens, and it is what makes a missed webhook
     * repairable by the customer looking at their order: a payment that is still running is asked
     * about, and a `PAID` the shop never heard of confirms the order on the spot.
     *
     * The ownership-filtered read comes first, always: no order the caller does not own is ever
     * refreshed, so nobody can drive provider calls for somebody else's payment by guessing ids.
     */
    override suspend fun order(
        orderId: Long,
        userId: Long?,
        guestToken: String?,
    ): OperationResult<OrderView> =
        logger.databaseOperation(
            "Database error while reading order $orderId",
            OperationResult.UnexpectedFailure,
        ) {
            when (val order = repository.order(orderId, userId, guestToken)) {
                null -> OperationResult.NotFound
                else -> OperationResult.Success(repaired(order, userId, guestToken))
            }
        }

    /**
     * The order behind an access token, for the permanent link the confirmation mail carries.
     *
     * Two things are deliberately different from [order]. There is no identity to filter by — the
     * token is the credential, and the unique index makes it name at most one order. And the
     * payment status comes from [OrderPaymentStatusSource.stored], **never** from `refreshed`: this
     * read is reachable without any session at all, and an anonymous request must not be able to
     * drive outbound provider calls. A missed webhook is repaired by the customer's own order page,
     * which is where the refresh lives.
     *
     * A token that is not even shaped like one is answered before the database is touched, and with
     * the same [OperationResult.NotFound] a wrong token gets. Nothing here logs the token.
     */
    override suspend fun orderByToken(token: String): OperationResult<OrderView> =
        logger.databaseOperation(
            "Database error while reading an order by its access token",
            OperationResult.UnexpectedFailure,
        ) {
            val accessToken = OrderAccessToken(token)
            val order = accessToken?.let { repository.orderByToken(it) }
            when (order) {
                null -> OperationResult.NotFound
                else ->
                    OperationResult.Success(
                        order.copy(
                            paymentStatus =
                                paymentStatuses.stored(setOf(order.orderId))[order.orderId]
                        )
                    )
            }
        }

    /**
     * [order] with its payment status filled in — and with the order itself re-read when that very
     * refresh is what paid it.
     *
     * Without the second read the repairing answer contradicts itself: the row was read a moment
     * before `refreshed` confirmed the order, so it still says `PENDING` while `paymentStatus`
     * already says `PAID`. The re-read costs one query in exactly the case that just wrote to the
     * order, and it uses the same ownership filter, because a second read is a second read.
     */
    private suspend fun repaired(
        order: OrderView,
        userId: Long?,
        guestToken: String?,
    ): OrderView {
        val paymentStatus = paymentStatuses.refreshed(order.orderId)
        val repaired =
            if (paymentStatus == OrderPaymentStatus.PAID && order.status == OrderStatus.PENDING) {
                repository.order(order.orderId, userId, guestToken) ?: order
            } else {
                order
            }
        return repaired.copy(paymentStatus = paymentStatus)
    }

    /**
     * Places one order: field rules first, then the catalog snapshot, then the write.
     *
     * The catalog is resolved in a single batched call for all lines, and every reference has to
     * come back. A line whose article or variant the catalog does not know cannot be produced, and
     * an order that cannot be produced must not be taken.
     *
     * The confirmation mail is enqueued *inside* the placing transaction, so an order and the mail
     * that hands the customer its permanent link are one committed fact — a placement that rolls
     * back leaves no mail, and a committed order can never be without one. It does not wait for the
     * payment (issue #110, Joe decision 3): the link is what a customer needs to *see* their order,
     * including one whose payment failed. The accepted consequence is that an order cancelled right
     * after placement still mails a confirmation; the link then shows the real, cancelled status.
     */
    override suspend fun place(input: PlaceOrderInput): OrderPlacementResult {
        val errors = input.validate()
        if (errors.isNotEmpty()) return OrderPlacementResult.Invalid(errors)

        val references =
            input.lines.mapTo(mutableSetOf()) { line ->
                ArticleVariantReference(articleId = line.articleId, variantId = line.variantId)
            }
        val snapshots = articles.find(references)
        return when {
            snapshots.keys.containsAll(references) ->
                repository.place(input, snapshots) { placedOrderId ->
                    emailOutbox.enqueue(QueuedEmailReference.OrderConfirmation(placedOrderId))
                }
            else -> OrderPlacementResult.UnknownArticleReference
        }
    }

    /**
     * The order a payment is to be started for again, with the same ownership rule the customer's
     * own reads apply.
     *
     * It is a pure read of the stored snapshot, and deliberately not built from anything the caller
     * hands in: a retry describes the order that exists, never the request that asked about it.
     */
    override suspend fun payable(
        orderId: Long,
        userId: Long?,
        guestToken: String?,
    ): PayableOrderResult = repository.payableOrder(orderId, userId, guestToken)

    /**
     * Confirms the payment of an order.
     *
     * Everything that makes this safe lives in the repository's single transaction; what the
     * service adds is the promotion capability, the production request it hands into that
     * transaction, and a warning for every outcome that changed nothing a caller can see. The
     * confirmation mail is *not* among them any more — it was enqueued when the order was placed.
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
                redeem = { promotionId, cartId, userId ->
                    promotions.redeem(promotionId, orderId, cartId, userId)
                },
                announce = { paidOrderId -> productionOutbox.request(paidOrderId) },
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
     *
     * The second thing the service adds is the promotion capability the cancellation hands its
     * release to. An order that stops being live stops holding its promotion's capacity, in the
     * same commit (deviation D3).
     */
    override suspend fun cancel(orderId: Long): OrderPaymentOutcome {
        val outcome = repository.markCancelled(orderId) { cartId -> promotions.release(cartId) }
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
     * The payment of an order ended terminally: the order is left alone, its promotion reservation
     * is not.
     *
     * There is nothing to log and nothing to report. An unknown order, an order without a
     * promotion, and a reservation that is already gone are all the same no-op, which is exactly
     * what a redelivered notification must be (deviation D4).
     */
    override suspend fun paymentEnded(orderId: Long) {
        repository.releaseReservation(orderId) { cartId -> promotions.release(cartId) }
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
     * Every value is read again per attempt — the recipient and the access token included — so an
     * address corrected between two attempts reaches the customer, while the amounts and the items
     * stay the ones that were ordered. `null` means the order is gone; the mail worker leaves the
     * job open for a later scan rather than dropping it.
     *
     * The link is built here from the stored token through [OrderLinks], which is why every retry
     * mails the same, working link and no attempt can send a mail without one.
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
            orderUrl = links.orderUrl(order.accessToken),
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
     * The customer half of a shipping notification: whom to write to, how to greet them, and the
     * permanent link to their order.
     *
     * It is the third worker read of this service and follows the same rules as the other two: no
     * ownership predicate (a worker is not a customer), and every value read again per attempt, so
     * a corrected address reaches the next send. `null` means the order is gone and the email
     * worker retries later.
     *
     * The access token itself never leaves this module — the link is built here through
     * [OrderLinks], and production receives the finished, self-redacting `EmailActionUrl`.
     */
    suspend fun shippingNotificationOrder(orderId: Long): ShippingNotificationOrder? {
        val order = repository.storedOrder(orderId) ?: return null
        return ShippingNotificationOrder(
            recipientEmail = order.email,
            customerFirstName = order.shippingAddress.firstName,
            orderUrl = links.orderUrl(order.accessToken),
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
