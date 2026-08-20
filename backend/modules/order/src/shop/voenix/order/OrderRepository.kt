package shop.voenix.order

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.update
import shop.voenix.article.ArticleVariantReference
import shop.voenix.article.CatalogVariant
import shop.voenix.db.executePostgresWrite
import shop.voenix.production.fulfillment.FulfillmentOrder
import shop.voenix.promotion.PromotionCodeResult

/**
 * The only place that touches `orders` and `order_items`.
 *
 * Four transactions here are the reason the order module is more than a pair of tables:
 *
 * 1. **Placement** writes the order, all of its lines, and the confirmation mail in one
 *    transaction, so a customer can never end up with an order that is missing what they bought —
 *    or without the mail that carries its permanent link (issue #110). It takes no lock at all: the
 *    cart is the identity of the order, and the partial unique index over live orders per cart
 *    decides a race. Whoever loses reads the winner's order and reports
 *    [OrderPlacementResult.AlreadyPlaced]; a preliminary "does this cart already have an order"
 *    query would race and is deliberately absent. Two unique indexes can refuse that insert, not
 *    one: `ux_orders_live_cart` when the cart already has a live order, and
 *    `ux_orders_access_token` when the freshly generated token happens to be one that exists. Both
 *    arrive as SQL state `23505` and neither is told apart by name — see [placeOnce] for why the
 *    same handling is right for both.
 * 2. **[markPaid]** locks the order row with `SELECT … FOR UPDATE` *before* it reads the status it
 *    decides from, so two payment confirmations of the same order queue up instead of both seeing
 *    `PENDING`. Everything the payment causes — the promotion redemption, the status change, the
 *    production request — joins that one transaction, which is what makes "the order was paid" and
 *    "its side effects exist" the same committed fact. The confirmation mail is not one of them: it
 *    was enqueued by the placement above. The lock order is always orders → promotions; nothing in
 *    this module takes them the other way round.
 * 3. **[markCancelled]** takes that very same lock, which is the whole reason it is worth a
 *    transaction of its own: a confirmation and a cancellation of one order are two writers of one
 *    row, and the lock is what makes them queue instead of both deciding from `PENDING`. It causes
 *    nothing but one thing — a cancelled order has no redemption and no production request, but the
 *    promotion capacity its checkout was holding is given back in that same commit (deviation D3).
 *    Its confirmation mail is *not* taken back: the placement already committed it, and the link it
 *    carries is what shows the customer that the order is cancelled.
 * 4. **[releaseReservation]** is that same give-back without the cancellation: the payment of a
 *    still-pending order ended terminally, so the capacity is freed while the order stays as it is
 *    (deviation D4). It takes the order lock too, so it queues behind a confirmation instead of
 *    releasing a reservation a redemption is about to consume.
 *
 * The class is one function over Detekt's limit, and that is the deliberate consequence of the rule
 * this module lives by: `orders` and `order_items` have exactly one door, so every read another
 * module needs is a function here. Splitting the class would only move statements away from the
 * four transactions above, which are the reason it exists.
 */
@Suppress("TooManyFunctions")
internal class OrderRepository(
    private val database: Database,
    /**
     * Where a placement's [OrderAccessToken] comes from. Production never passes anything: the
     * default *is* the generator. It is a parameter so that a test can hand in a generator which
     * collides on purpose — the retry path is otherwise unreachable at a probability of 2^-256.
     */
    private val tokens: () -> OrderAccessToken = OrderAccessToken::generate,
) {
    /** The caller's orders, newest first. Ties break on the id, so the ordering is total. */
    suspend fun history(
        userId: Long?,
        guestToken: String?,
    ): List<OrderView> = read {
        val readable = readablePredicate(userId, guestToken)
        Orders.selectAll()
            .where { readable }
            .orderBy(Orders.createdAt to SortOrder.DESC, Orders.id to SortOrder.DESC)
            .map { row -> row.toOrderView(linesInTransaction(row[Orders.id].value)) }
    }

    /** One readable order, or `null` when it is unknown *or* belongs to somebody else. */
    suspend fun order(
        orderId: Long,
        userId: Long?,
        guestToken: String?,
    ): OrderView? = read {
        val readable = readablePredicate(userId, guestToken)
        Orders.selectAll()
            .where { (Orders.id eq orderId) and readable }
            .singleOrNull()
            ?.let { row -> row.toOrderView(linesInTransaction(orderId)) }
    }

    /**
     * The one order [token] opens, or `null` when no order carries it.
     *
     * This is the only customer-facing read without an ownership predicate, and that is the whole
     * point of the token: it *is* the authorization. `ux_orders_access_token` makes the row unique,
     * so there is nothing to disambiguate — and a token that names no order answers exactly like
     * one that is merely wrong.
     */
    suspend fun orderByToken(token: OrderAccessToken): OrderView? = read {
        Orders.selectAll()
            .where { Orders.accessToken eq token.value }
            .singleOrNull()
            ?.let { row -> row.toOrderView(linesInTransaction(row[Orders.id].value)) }
    }

    /**
     * One order of the caller as a payment is built from it, or the reason it cannot be paid.
     *
     * The ownership predicate is the same one [order] uses, so an id that is unknown and one that
     * belongs to somebody else are the same [PayableOrderResult.NotFound] — and the read happens
     * before anything else, so no state of a foreign order is ever disclosed.
     */
    suspend fun payableOrder(
        orderId: Long,
        userId: Long?,
        guestToken: String?,
    ): PayableOrderResult = read {
        val readable = readablePredicate(userId, guestToken)
        val row =
            Orders.selectAll().where { (Orders.id eq orderId) and readable }.singleOrNull()
                ?: return@read PayableOrderResult.NotFound
        when (OrderStatus.valueOf(row[Orders.status])) {
            OrderStatus.PAID -> PayableOrderResult.AlreadyPaid
            OrderStatus.CANCELLED -> PayableOrderResult.Cancelled
            OrderStatus.PENDING ->
                when (row[Orders.totalCents]) {
                    0 -> PayableOrderResult.Free
                    else -> PayableOrderResult.Payable(row.toPayableOrder())
                }
        }
    }

    /**
     * Writes [input] as one order with its lines, snapshotting what [snapshots] says about the
     * article of every line.
     *
     * The catalog answer is handed in rather than asked for here, so the repository stays free of
     * master-data lookups. What it adds is the part only the database can decide: whether this cart
     * already has a live order.
     *
     * [announce] runs *inside* the placing transaction, like [markPaid]'s does inside the paying
     * one: the confirmation mail is not a consequence of a placed order that happens afterwards, it
     * is part of the same decision. A rollback — a refused unique index, a failing line insert —
     * takes the enqueued mail with it, and the retry below enqueues again in its own transaction,
     * so a committed order has exactly one mail and an uncommitted one has none.
     */
    suspend fun place(
        input: PlaceOrderInput,
        snapshots: Map<ArticleVariantReference, CatalogVariant>,
        announce: suspend (orderId: Long) -> Unit,
    ): OrderPlacementResult =
        placeOnce(input, snapshots, announce)
            ?: placeOnce(input, snapshots, announce)
            // Two conflicts without a live order in a row: reachable only when a cancellation
            // committed inside each window, or — at 2^-256 per attempt — when a generated access
            // token collided twice. See `liveOrderOfCart` for why this is bounded rather than
            // looped.
            ?: error(
                "Cart ${input.cartId} refused two placements in a row without having a live " +
                    "order: either a cancellation committed in both conflict windows, or the " +
                    "access token collided twice"
            )

    /**
     * Turns the order into a paid one and lets everything it causes join the same commit.
     *
     * [redeem] and [announce] are called *inside* this transaction on purpose. The promotion
     * redemption and the production request are not consequences of a paid order that happen
     * afterwards — they are part of the same decision, so a rollback takes both of them with it and
     * no compensation code is needed anywhere. The confirmation mail is deliberately absent from
     * this list: it belongs to the placement (issue #110, Joe decision 3).
     *
     * A refused redemption is the one thing that does *not* roll anything back: the customer has
     * already been charged by then, so the order becomes `PAID` without a redemption and the caller
     * logs the reason (deviation D22).
     */
    suspend fun markPaid(
        orderId: Long,
        redeem: suspend (promotionId: Long, cartId: Long, userId: Long?) -> PromotionCodeResult,
        announce: suspend (orderId: Long) -> Unit,
    ): PaidOrderResult = write {
        val locked =
            Orders.selectAll().where { Orders.id eq orderId }.forUpdate().singleOrNull()
                ?: return@write PaidOrderResult.NotFound
        when (OrderStatus.valueOf(locked[Orders.status])) {
            OrderStatus.PAID -> return@write PaidOrderResult.AlreadyPaid
            OrderStatus.CANCELLED -> return@write PaidOrderResult.Cancelled
            OrderStatus.PENDING -> Unit
        }

        val refusal =
            locked[Orders.promotionId]?.let { promotionId ->
                when (
                    val outcome = redeem(promotionId, locked[Orders.cartId], locked[Orders.userId])
                ) {
                    is PromotionCodeResult.Applicable -> null
                    else -> outcome
                }
            }
        Orders.update({ Orders.id eq orderId }) { statement ->
            statement[status] = OrderStatus.PAID.name
            statement[updatedAt] = CurrentTimestampWithTimeZone
        }
        announce(orderId)

        refusal?.let(PaidOrderResult::PromotionRefused) ?: PaidOrderResult.Paid
    }

    /**
     * Turns the order into a cancelled one, under the same lock [markPaid] takes.
     *
     * The lock is the point. Without it a cancellation and a confirmation arriving together would
     * both read `PENDING` and both write their status, and the order would end up paid *and*
     * cancelled depending on which `UPDATE` landed last. With it, whoever comes second reads what
     * the first one committed and answers [OrderPaymentOutcome.REFUSED].
     *
     * A `PAID` order is never cancelled by a failed payment: the money moved, the production
     * request exists, and taking the status back would leave both behind. The caller reports that
     * refusal instead — it is a case for a human.
     *
     * The result is the exported [OrderPaymentOutcome] rather than an internal type of its own,
     * because a cancellation has exactly these four outcomes and nothing to add to them.
     *
     * [release] joins this transaction, like [markPaid]'s redemption does: an order that stops
     * being live stops holding its promotion's capacity in the very same commit, so a rollback
     * keeps both (deviation D3). It is called only for an order that *has* a promotion — the locked
     * row carries the cart the reservation is keyed on — and only on the `PENDING → CANCELLED`
     * transition, so the two early returns above can never release anything.
     */
    suspend fun markCancelled(
        orderId: Long,
        release: suspend (cartId: Long) -> Unit,
    ): OrderPaymentOutcome = write {
        val locked =
            Orders.selectAll().where { Orders.id eq orderId }.forUpdate().singleOrNull()
                ?: return@write OrderPaymentOutcome.UNKNOWN_ORDER
        when (OrderStatus.valueOf(locked[Orders.status])) {
            OrderStatus.PAID -> return@write OrderPaymentOutcome.REFUSED
            OrderStatus.CANCELLED -> return@write OrderPaymentOutcome.ALREADY_APPLIED
            OrderStatus.PENDING -> Unit
        }

        locked[Orders.promotionId]?.let { release(locked[Orders.cartId]) }
        Orders.update({ Orders.id eq orderId }) { statement ->
            statement[status] = OrderStatus.CANCELLED.name
            statement[updatedAt] = CurrentTimestampWithTimeZone
        }
        OrderPaymentOutcome.APPLIED
    }

    /**
     * Gives the promotion capacity of [orderId]'s cart back without touching the order itself.
     *
     * This is the terminal-payment end (deviation D4), and everything it does *not* do is the
     * point: the order keeps its status, so a customer can still pay it, while the unit their
     * checkout was holding is free for somebody else. The order row is locked first all the same —
     * a release that overtook a running confirmation would take away the very reservation that
     * confirmation's redemption is about to consume, and the lock order stays `orders` then
     * `promotions`.
     *
     * Nothing is released for an unknown order or one without a promotion, and [release] itself is
     * idempotent, which is what makes a redelivered notification a no-op.
     */
    suspend fun releaseReservation(
        orderId: Long,
        release: suspend (cartId: Long) -> Unit,
    ): Unit = write {
        val locked =
            Orders.selectAll().where { Orders.id eq orderId }.forUpdate().singleOrNull()
                ?: return@write
        locked[Orders.promotionId] ?: return@write
        release(locked[Orders.cartId])
    }

    /**
     * The whole stored order, or `null` when no order has that id.
     *
     * No ownership predicate, deliberately: this read serves the production worker and the mail
     * worker, and neither of them is a customer. It is the *only* read of this module without one,
     * which is why it is not reachable from any route.
     */
    suspend fun storedOrder(orderId: Long): StoredOrder? = read {
        Orders.selectAll()
            .where { Orders.id eq orderId }
            .singleOrNull()
            ?.let { row -> row.toStoredOrder(storedLinesInTransaction(orderId)) }
    }

    /**
     * The order headers a fulfillment page shows, for every id of that page in one statement.
     *
     * This is the narrowest read of this module by design: the nine columns it selects are exactly
     * the nine fields of a [FulfillmentOrder], so no e-mail address, no phone number, no amount,
     * and no access token is ever fetched — a supplier surface cannot leak what was never read.
     * Unknown ids are simply absent from the map.
     *
     * Like [storedOrder] it carries no ownership predicate and is reachable from no route: its one
     * caller is the production module, through the port it declared.
     */
    suspend fun fulfillmentOrders(orderIds: Set<Long>): Map<Long, FulfillmentOrder> {
        if (orderIds.isEmpty()) return emptyMap()
        return read {
            Orders.select(
                    Orders.id,
                    Orders.createdAt,
                    Orders.shippingFirstName,
                    Orders.shippingLastName,
                    Orders.shippingStreet,
                    Orders.shippingHouseNumber,
                    Orders.shippingPostalCode,
                    Orders.shippingCity,
                    Orders.shippingCountry,
                )
                .where { Orders.id inList orderIds }
                .associate { row ->
                    val orderId = row[Orders.id].value
                    orderId to
                        FulfillmentOrder(
                            orderId = orderId,
                            orderDate = berlinOrderDate(row[Orders.createdAt]),
                            customerFirstName = row[Orders.shippingFirstName],
                            customerLastName = row[Orders.shippingLastName],
                            shippingStreet = row[Orders.shippingStreet],
                            shippingHouseNumber = row[Orders.shippingHouseNumber],
                            shippingPostalCode = row[Orders.shippingPostalCode],
                            shippingCity = row[Orders.shippingCity],
                            shippingCountry = row[Orders.shippingCountry],
                        )
                }
        }
    }

    /**
     * The reorder snapshot of one ordered line, or `null` when the line is unknown *or* belongs to
     * somebody else — the two are deliberately indistinguishable, so an id cannot be probed.
     *
     * The ownership question is asked as a second statement of the same read-only transaction
     * instead of as a join, because the two tables key their identity differently and the join
     * would only obscure which rule rejected the caller.
     */
    suspend fun orderItem(
        orderItemId: Long,
        userId: Long?,
        guestToken: String?,
    ): OrderItemReader.Item? = read {
        val line =
            OrderItems.selectAll().where { OrderItems.id eq orderItemId }.singleOrNull()
                ?: return@read null
        val readable = readablePredicate(userId, guestToken)
        val readableOrder =
            Orders.select(Orders.id)
                .where { (Orders.id eq line[OrderItems.orderId]) and readable }
                .singleOrNull()
        readableOrder?.let {
            OrderItemReader.Item(
                articleId = line[OrderItems.articleId],
                variantId = line[OrderItems.variantId],
                promptId = line[OrderItems.promptId],
                printImageId = line[OrderItems.printImageId],
            )
        }
    }

    /**
     * One attempt at [place], or `null` when the attempt has to be repeated.
     *
     * `null` says one thing — "a unique index refused this insert and the cart has no live order to
     * report either" — and two different conflicts can produce it:
     *
     * - `ux_orders_live_cart` refused it and [markCancelled] committed in between, so by the time
     *   the winner is read it is no longer live. The cart has no order at all then, so neither
     *   result would be true;
     * - `ux_orders_access_token` refused it, because the generated token already existed. The cart
     *   was never in conflict at all, so the live-order read finds nothing.
     *
     * Both are answered the same way and deliberately without asking *which* index refused: a
     * constraint name is not an application result (see `persistence-error-handling.md`). The
     * second attempt runs the whole insert again — with a **fresh** token, because [tokens] is
     * called per attempt — which is exactly the repair both cases need.
     */
    private suspend fun placeOnce(
        input: PlaceOrderInput,
        snapshots: Map<ArticleVariantReference, CatalogVariant>,
        announce: suspend (orderId: Long) -> Unit,
    ): OrderPlacementResult? =
        when (val insertion = insert(input, snapshots, announce)) {
            // Built from the input rather than read back: the transaction that just committed wrote
            // exactly these values, so a second query could only repeat them.
            is Insertion.Placed ->
                OrderPlacementResult.Placed(input.toPayableOrder(insertion.orderId))
            Insertion.MissingPrintImage -> OrderPlacementResult.UnknownPrintImage
            Insertion.Conflict ->
                liveOrderOfCart(input.cartId)?.let(OrderPlacementResult::AlreadyPlaced)
        }

    /**
     * The transaction that writes the order.
     *
     * Its result is not an [OrderPlacementResult], because the conflict cannot be finished here:
     * the transaction that hit `23505` is dead, and reading the order that won the race needs a
     * fresh one.
     */
    private suspend fun insert(
        input: PlaceOrderInput,
        snapshots: Map<ArticleVariantReference, CatalogVariant>,
        announce: suspend (orderId: Long) -> Unit,
    ): Insertion =
        executePostgresWrite(uniqueViolation = Insertion.Conflict) {
            write {
                if (!printImagesExistInTransaction(input)) {
                    return@write Insertion.MissingPrintImage
                }
                val orderId = insertOrderInTransaction(input, tokens())
                insertLinesInTransaction(orderId, input, snapshots)
                announce(orderId)
                Insertion.Placed(orderId)
            }
        }

    /**
     * The live order of a cart, read after the unique index refused a second placement, or `null`
     * when a cancellation committed in between and the cart has no live order any more.
     *
     * That `null` is the whole reason [place] retries once instead of asserting. The window is
     * narrow — between the failed insert and this read — and the retry is bounded on purpose: a
     * *second* conflict whose winner is gone again needs a second cancellation to commit inside a
     * second such window, and looping over that would trade a rare failure for an unbounded one.
     *
     * The residual `error(…)` in [place] is therefore reachable, not impossible: a triple race —
     * two consecutive conflict windows, each hit by a cancellation — ends there. It is an accepted,
     * vanishingly rare `500` for a customer who can simply place the order again, and it is written
     * as an `error` so that it is loud when it does happen (deviation D27 of the Payment
     * migration).
     */
    private suspend fun liveOrderOfCart(cartId: Long): PayableOrder? = read {
        Orders.selectAll()
            .where { (Orders.cartId eq cartId) and (Orders.status neq OrderStatus.CANCELLED.name) }
            .singleOrNull()
            ?.toPayableOrder()
    }

    private suspend fun <T> read(operation: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database, readOnly = true) {
                maxAttempts = 1
                operation()
            }
        }

    private suspend fun <T> write(operation: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            suspendTransaction(db = database) {
                maxAttempts = 1
                operation()
            }
        }

    /** What the placing transaction itself can end in; a conflict is completed by a second read. */
    private sealed interface Insertion {
        data class Placed(val orderId: Long) : Insertion

        data object Conflict : Insertion

        data object MissingPrintImage : Insertion
    }
}

/**
 * The placed order: who it belongs to, where it goes, and what it cost.
 *
 * Every address and amount column is a snapshot taken at placement, so a later change to the
 * account, the catalog, or the promotion cannot rewrite what was ordered. The amounts are stored
 * rather than derived because the database is what keeps them consistent (`total = subtotal +
 * shipping - discount`).
 */
internal object Orders : LongIdTable("orders") {
    val cartId = long("cart_id")
    val guestSessionToken = text("guest_session_token").nullable()
    val userId = long("user_id").nullable()
    val promotionId = long("promotion_id").nullable()

    /**
     * The order's own bearer credential; see [OrderAccessToken]. It is stored as text and read back
     * through that type, so nothing outside the repository ever handles it as a plain string.
     */
    val accessToken = text("access_token")
    val status = text("status")
    val shippingFirstName = varchar("shipping_first_name", 100)
    val shippingLastName = varchar("shipping_last_name", 100)
    val shippingStreet = varchar("shipping_street", 200)
    val shippingHouseNumber = varchar("shipping_house_number", 20)
    val shippingPostalCode = varchar("shipping_postal_code", 10)
    val shippingCity = varchar("shipping_city", 100)
    val shippingCountry = varchar("shipping_country", 2)
    val billingFirstName = varchar("billing_first_name", 100)
    val billingLastName = varchar("billing_last_name", 100)
    val billingStreet = varchar("billing_street", 200)
    val billingHouseNumber = varchar("billing_house_number", 20)
    val billingPostalCode = varchar("billing_postal_code", 10)
    val billingCity = varchar("billing_city", 100)
    val billingCountry = varchar("billing_country", 2)
    val email = varchar("email", 255)
    val phone = text("phone").nullable()
    val subtotalCents = integer("subtotal_cents")
    val shippingCostCents = integer("shipping_cost_cents")
    val discountCents = integer("discount_cents")
    val totalCents = integer("total_cents")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
}

/**
 * One ordered line, in the order the customer put it together ([position]).
 *
 * [articleId] and [variantId] are plain numbers on purpose: they carry no catalog foreign key, so a
 * deleted article cannot take an order line with it. Everything production and the confirmation
 * mail need is snapshotted next to them — the names, the prices, the supplier article number, and
 * the five layout measurements in millimetres.
 */
internal object OrderItems : LongIdTable("order_items") {
    val orderId = long("order_id")
    val position = integer("position")
    val articleId = long("article_id")
    val variantId = long("variant_id")
    val articleName = varchar("article_name", 255)
    val variantName = varchar("variant_name", 255)
    val supplierArticleNumber = varchar("supplier_article_number", 255).nullable()
    val printTemplateWidthMm = integer("print_template_width_mm").nullable()
    val printTemplateHeightMm = integer("print_template_height_mm").nullable()
    val documentFormatWidthMm = integer("document_format_width_mm").nullable()
    val documentFormatHeightMm = integer("document_format_height_mm").nullable()
    val documentFormatMarginBottomMm = integer("document_format_margin_bottom_mm").nullable()
    val quantity = integer("quantity")
    val priceCents = integer("price_cents")
    val promptPriceCents = integer("prompt_price_cents")
    val promptId = long("prompt_id").nullable()
    val printImageId = long("print_image_id").nullable()
    val createdAt = timestampWithTimeZone("created_at")
}

/**
 * The print images an order line points at, declared here with its identity and its stored name.
 *
 * The table belongs to the image and cart slice; the order module asks it two questions only.
 *
 * *Does the image a placement names still exist?* — because the alternative is worse. The
 * `print_image_id` foreign key would refuse the insert anyway, but `order_items` has three foreign
 * keys, so SQL state `23503` could not say *which* reference failed, and a repository must never
 * guess that from a constraint name. Asking first turns the answer into
 * [OrderPlacementResult.UnknownPrintImage]; the foreign key stays the concurrency-safe authority
 * behind it, and an image deleted in the gap surfaces as an unexpected failure rather than a wrong
 * result.
 *
 * The line's other nullable reference, `prompt_id`, deliberately gets no such pre-flight query: a
 * deleted prompt has already had its reference nulled in the customer's own cart line by that
 * table's `ON DELETE SET NULL`, so a placement practically never carries a prompt id that is gone.
 * What is left is a race of milliseconds — a prompt deleted between the checkout's read and the
 * placement's insert — and it is answered the way an unforeseen collision should be: the foreign
 * key refuses the insert, the `23503` is rethrown, and the placement fails visibly instead of
 * quietly storing a line with the wrong prompt story.
 *
 * *Under which name was it stored?* — that name is the only thing production needs to get the file
 * itself, and it is handed to `shop.voenix.image.PrivateImageStorage.originalPaths` unchanged. The
 * order module never combines it with a directory: where private originals live is the image
 * module's secret, and stays one.
 */
internal object PrintImages : LongIdTable("print_images") {
    val filename = varchar("filename", 64)
}

private fun insertOrderInTransaction(
    input: PlaceOrderInput,
    token: OrderAccessToken,
): Long {
    val billing = input.effectiveBillingAddress
    return Orders.insertAndGetId { statement ->
            statement[cartId] = input.cartId
            statement[guestSessionToken] = input.guestToken
            statement[userId] = input.userId
            statement[promotionId] = input.promotionId
            statement[accessToken] = token.value
            statement[status] = OrderStatus.PENDING.name
            statement[shippingFirstName] = input.shippingAddress.firstName
            statement[shippingLastName] = input.shippingAddress.lastName
            statement[shippingStreet] = input.shippingAddress.street
            statement[shippingHouseNumber] = input.shippingAddress.houseNumber
            statement[shippingPostalCode] = input.shippingAddress.postalCode
            statement[shippingCity] = input.shippingAddress.city
            statement[shippingCountry] = input.shippingAddress.country
            statement[billingFirstName] = billing.firstName
            statement[billingLastName] = billing.lastName
            statement[billingStreet] = billing.street
            statement[billingHouseNumber] = billing.houseNumber
            statement[billingPostalCode] = billing.postalCode
            statement[billingCity] = billing.city
            statement[billingCountry] = billing.country
            statement[email] = input.email
            statement[phone] = input.phone
            statement[subtotalCents] = input.subtotalCents
            statement[shippingCostCents] = input.shippingCostCents
            statement[discountCents] = input.discountCents
            statement[totalCents] = input.totalCents
            statement[createdAt] = CurrentTimestampWithTimeZone
            statement[updatedAt] = CurrentTimestampWithTimeZone
        }
        .value
}

/**
 * Writes the lines in the order the customer put them together. `position` is that order made
 * durable — the ids would reproduce it only by accident, and `UNIQUE (order_id, position)` keeps it
 * unambiguous.
 */
private fun insertLinesInTransaction(
    orderId: Long,
    input: PlaceOrderInput,
    snapshots: Map<ArticleVariantReference, CatalogVariant>,
) {
    input.lines.forEachIndexed { index, line ->
        val snapshot =
            checkNotNull(snapshots[ArticleVariantReference(line.articleId, line.variantId)]) {
                "The service must resolve every ordered article before an order is placed"
            }
        OrderItems.insert { statement ->
            statement[OrderItems.orderId] = orderId
            statement[position] = index + 1
            statement[articleId] = line.articleId
            statement[variantId] = line.variantId
            statement[articleName] = snapshot.articleName
            statement[variantName] = snapshot.variantName
            statement[supplierArticleNumber] = snapshot.supplierArticleNumber
            statement[printTemplateWidthMm] = snapshot.printTemplateWidthMm
            statement[printTemplateHeightMm] = snapshot.printTemplateHeightMm
            statement[documentFormatWidthMm] = snapshot.documentFormatWidthMm
            statement[documentFormatHeightMm] = snapshot.documentFormatHeightMm
            statement[documentFormatMarginBottomMm] = snapshot.documentFormatMarginBottomMm
            statement[quantity] = line.quantity
            statement[priceCents] = line.priceCents
            statement[promptPriceCents] = line.promptPriceCents
            statement[promptId] = line.promptId
            statement[printImageId] = line.printImageId
            statement[createdAt] = CurrentTimestampWithTimeZone
        }
    }
}

private fun printImagesExistInTransaction(input: PlaceOrderInput): Boolean {
    val imageIds = input.lines.mapNotNull(PlaceOrderInput.Line::printImageId).toSet()
    if (imageIds.isEmpty()) return true
    val known =
        PrintImages.select(PrintImages.id)
            .where { PrintImages.id inList imageIds }
            .mapTo(mutableSetOf()) { row -> row[PrintImages.id].value }
    return known.size == imageIds.size
}

/**
 * "This order is the caller's": the signed-in customer it belongs to, or the guest token it was
 * placed with while it has no user at all.
 *
 * An order belongs to the account it was placed with, and `user_id` is written once, at placement.
 * The `user_id IS NULL` half of the guest branch is therefore not redundant: a signed-in checkout
 * stores *both* the user and the guest cookie of that browser, and the cookie is not rotated at
 * logout — without the clause, that cookie would keep opening an account's order afterwards. It
 * also covers the day an account is deleted, because `fk_orders_user` is `ON DELETE SET NULL`. A
 * guest cookie opens only orders that never belonged to an account, and a request that carries no
 * identity at all matches nothing rather than everything.
 */
private fun readablePredicate(
    userId: Long?,
    guestToken: String?,
): Op<Boolean> {
    val byUser = userId?.let { Orders.userId eq it }
    val byGuest = guestToken?.let { Orders.userId.isNull() and (Orders.guestSessionToken eq it) }
    return when {
        byUser != null && byGuest != null -> byUser or byGuest
        byUser != null -> byUser
        byGuest != null -> byGuest
        else -> Op.FALSE
    }
}

private fun linesInTransaction(orderId: Long): List<OrderLineView> =
    OrderItems.selectAll()
        .where { OrderItems.orderId eq orderId }
        .orderBy(OrderItems.position to SortOrder.ASC)
        .map { row ->
            OrderLineView(
                orderItemId = row[OrderItems.id].value,
                articleId = row[OrderItems.articleId],
                variantId = row[OrderItems.variantId],
                articleName = row[OrderItems.articleName],
                variantName = row[OrderItems.variantName],
                quantity = row[OrderItems.quantity],
                price = row[OrderItems.priceCents],
                promptPrice = row[OrderItems.promptPriceCents],
                imageId = row[OrderItems.printImageId],
            )
        }

/**
 * The stored lines in their durable [OrderItems.position] order, each with the file name of its
 * print image.
 *
 * The names are read as a second statement of the same transaction rather than as a join, for the
 * same reason the ownership check is: the two tables belong to different slices, and one batched
 * lookup keyed by id says what a `LEFT JOIN` would only obscure. A line whose image row has
 * vanished keeps a `null` name and stays a line.
 */
private fun storedLinesInTransaction(orderId: Long): List<StoredOrder.Line> {
    val rows =
        OrderItems.selectAll()
            .where { OrderItems.orderId eq orderId }
            .orderBy(OrderItems.position to SortOrder.ASC)
            .toList()
    val filenames =
        printImageNamesInTransaction(
            rows.mapNotNullTo(mutableSetOf()) { row -> row[OrderItems.printImageId] }
        )
    return rows.map { row ->
        StoredOrder.Line(
            articleId = row[OrderItems.articleId],
            variantId = row[OrderItems.variantId],
            articleName = row[OrderItems.articleName],
            variantName = row[OrderItems.variantName],
            supplierArticleNumber = row[OrderItems.supplierArticleNumber],
            quantity = row[OrderItems.quantity],
            priceCents = row[OrderItems.priceCents],
            promptPriceCents = row[OrderItems.promptPriceCents],
            printImageFilename = row[OrderItems.printImageId]?.let(filenames::get),
            printTemplateWidthMm = row[OrderItems.printTemplateWidthMm],
            printTemplateHeightMm = row[OrderItems.printTemplateHeightMm],
            documentFormatWidthMm = row[OrderItems.documentFormatWidthMm],
            documentFormatHeightMm = row[OrderItems.documentFormatHeightMm],
            documentFormatMarginBottomMm = row[OrderItems.documentFormatMarginBottomMm],
        )
    }
}

private fun printImageNamesInTransaction(imageIds: Set<Long>): Map<Long, String> =
    when {
        imageIds.isEmpty() -> emptyMap()
        else ->
            PrintImages.select(PrintImages.id, PrintImages.filename)
                .where { PrintImages.id inList imageIds }
                .associate { row -> row[PrintImages.id].value to row[PrintImages.filename] }
    }

private fun ResultRow.toStoredOrder(lines: List<StoredOrder.Line>): StoredOrder =
    StoredOrder(
        orderId = this[Orders.id].value,
        // A stored token was written by `generate`, so a row that cannot be read back as one means
        // somebody wrote the column by hand — loud here rather than a mail with a dead link.
        accessToken =
            checkNotNull(OrderAccessToken(this[Orders.accessToken])) {
                "Order ${this[Orders.id].value} carries a malformed access token"
            },
        createdAt = this[Orders.createdAt],
        email = this[Orders.email],
        shippingAddress =
            StoredOrder.Address(
                firstName = this[Orders.shippingFirstName],
                lastName = this[Orders.shippingLastName],
                street = this[Orders.shippingStreet],
                houseNumber = this[Orders.shippingHouseNumber],
                postalCode = this[Orders.shippingPostalCode],
                city = this[Orders.shippingCity],
                country = this[Orders.shippingCountry],
            ),
        billingAddress =
            StoredOrder.Address(
                firstName = this[Orders.billingFirstName],
                lastName = this[Orders.billingLastName],
                street = this[Orders.billingStreet],
                houseNumber = this[Orders.billingHouseNumber],
                postalCode = this[Orders.billingPostalCode],
                city = this[Orders.billingCity],
                country = this[Orders.billingCountry],
            ),
        subtotalCents = this[Orders.subtotalCents],
        shippingCostCents = this[Orders.shippingCostCents],
        discountCents = this[Orders.discountCents],
        totalCents = this[Orders.totalCents],
        lines = lines,
    )

private fun ResultRow.toPayableOrder(): PayableOrder =
    PayableOrder(
        orderId = this[Orders.id].value,
        totalCents = this[Orders.totalCents],
        email = this[Orders.email],
        phone = this[Orders.phone],
        shippingAddress =
            PayableOrder.Address(
                firstName = this[Orders.shippingFirstName],
                lastName = this[Orders.shippingLastName],
                street = this[Orders.shippingStreet],
                houseNumber = this[Orders.shippingHouseNumber],
                postalCode = this[Orders.shippingPostalCode],
                city = this[Orders.shippingCity],
                country = this[Orders.shippingCountry],
            ),
        billingAddress =
            PayableOrder.Address(
                firstName = this[Orders.billingFirstName],
                lastName = this[Orders.billingLastName],
                street = this[Orders.billingStreet],
                houseNumber = this[Orders.billingHouseNumber],
                postalCode = this[Orders.billingPostalCode],
                city = this[Orders.billingCity],
                country = this[Orders.billingCountry],
            ),
    )

private fun ResultRow.toOrderView(items: List<OrderLineView>): OrderView =
    OrderView(
        orderId = this[Orders.id].value,
        createdAt = this[Orders.createdAt].toInstant(),
        status = OrderStatus.valueOf(this[Orders.status]),
        subtotal = this[Orders.subtotalCents],
        shippingCost = this[Orders.shippingCostCents],
        discountAmount = this[Orders.discountCents],
        total = this[Orders.totalCents],
        items = items,
    )
