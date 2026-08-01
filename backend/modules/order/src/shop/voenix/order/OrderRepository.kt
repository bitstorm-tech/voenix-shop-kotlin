package shop.voenix.order

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
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
import shop.voenix.promotion.PromotionCodeResult

/**
 * The only place that touches `orders` and `order_items`.
 *
 * Two transactions here are the reason the order module is more than a pair of tables:
 *
 * 1. **Placement** writes the order and all of its lines in one transaction, so a customer can
 *    never end up with an order that is missing what they bought. It takes no lock at all: the cart
 *    is the identity of the order, and the partial unique index over live orders per cart decides a
 *    race. Whoever loses reads the winner's order and reports [OrderWriteResult.AlreadyPlaced]; a
 *    preliminary "does this cart already have an order" query would race and is deliberately
 *    absent.
 * 2. **[markPaid]** locks the order row with `SELECT … FOR UPDATE` *before* it reads the status it
 *    decides from, so two payment confirmations of the same order queue up instead of both seeing
 *    `PENDING`. Everything the payment causes — the promotion redemption, the status change, the
 *    production request, the confirmation mail — joins that one transaction, which is what makes
 *    "the order was paid" and "its side effects exist" the same committed fact. The lock order is
 *    always orders → promotions; nothing in this module takes them the other way round.
 * 3. **[markCancelled]** takes that very same lock, which is the whole reason it is worth a
 *    transaction of its own: a confirmation and a cancellation of one order are two writers of one
 *    row, and the lock is what makes them queue instead of both deciding from `PENDING`. It causes
 *    nothing — a cancelled order has no redemption, no production request, and no mail.
 */
internal class OrderRepository(private val database: Database) {
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
     * Writes [input] as one order with its lines, snapshotting what [snapshots] says about the
     * article of every line.
     *
     * The catalog answer is handed in rather than asked for here, so the repository stays free of
     * master-data lookups. What it adds is the part only the database can decide: whether this cart
     * already has a live order.
     */
    suspend fun place(
        input: PlaceOrderInput,
        snapshots: Map<ArticleVariantReference, CatalogVariant>,
    ): OrderWriteResult =
        placeOnce(input, snapshots)
            ?: placeOnce(input, snapshots)
            ?: error(
                "Cart ${input.cartId} refused two placements in a row without having a live order"
            )

    /**
     * Turns the order into a paid one and lets everything it causes join the same commit.
     *
     * [redeem] and [announce] are called *inside* this transaction on purpose. The promotion
     * redemption, the production request, and the confirmation mail are not consequences of a paid
     * order that happen afterwards — they are part of the same decision, so a rollback takes all of
     * them with it and no compensation code is needed anywhere.
     *
     * A refused redemption is the one thing that does *not* roll anything back: the customer has
     * already been charged by then, so the order becomes `PAID` without a redemption and the caller
     * logs the reason (deviation D22).
     */
    suspend fun markPaid(
        orderId: Long,
        redeem: suspend (promotionId: Long, userId: Long?) -> PromotionCodeResult,
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
                when (val outcome = redeem(promotionId, locked[Orders.userId])) {
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
     * request and the confirmation mail exist, and taking the status back would leave all three
     * behind. The caller reports that refusal instead — it is a case for a human.
     *
     * The result is the exported [OrderPaymentOutcome] rather than an internal type of its own,
     * because a cancellation has exactly these four outcomes and nothing to add to them.
     */
    suspend fun markCancelled(orderId: Long): OrderPaymentOutcome = write {
        val locked =
            Orders.selectAll().where { Orders.id eq orderId }.forUpdate().singleOrNull()
                ?: return@write OrderPaymentOutcome.UNKNOWN_ORDER
        when (OrderStatus.valueOf(locked[Orders.status])) {
            OrderStatus.PAID -> return@write OrderPaymentOutcome.REFUSED
            OrderStatus.CANCELLED -> return@write OrderPaymentOutcome.ALREADY_APPLIED
            OrderStatus.PENDING -> Unit
        }

        Orders.update({ Orders.id eq orderId }) { statement ->
            statement[status] = OrderStatus.CANCELLED.name
            statement[updatedAt] = CurrentTimestampWithTimeZone
        }
        OrderPaymentOutcome.APPLIED
    }

    /**
     * Moves the orders of [guestToken] and of the confirmed address [email] to [userId].
     *
     * The `user_id IS NULL` predicate is what makes the claim idempotent and safe at once: a second
     * login changes nothing, and no claim can ever take an order away from another account. The two
     * handles are independent — a visitor without a cookie can still have ordered under their
     * address — and the e-mail is lowercased on both sides, exactly like the partial index that
     * answers it.
     */
    suspend fun claimGuestData(
        userId: Long,
        guestToken: String?,
        email: String?,
    ) {
        write {
            guestToken?.let { token ->
                assignUserInTransaction(userId, Orders.guestSessionToken eq token)
            }
            email?.let { address ->
                assignUserInTransaction(userId, Orders.email.lowerCase() eq address.lowercase())
            }
        }
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
     * `null` is exactly one situation, and [markCancelled] is what made it reachable: the index
     * refused the insert, and by the time the winner is read it is no longer live. The cart has no
     * order at all then, so neither result would be true — hence a second attempt, which now finds
     * the index free.
     */
    private suspend fun placeOnce(
        input: PlaceOrderInput,
        snapshots: Map<ArticleVariantReference, CatalogVariant>,
    ): OrderWriteResult? =
        when (val insertion = insert(input, snapshots)) {
            is Insertion.Placed ->
                OrderWriteResult.Stored(
                    checkNotNull(findOrder(insertion.orderId)) {
                        "The order vanished right after the transaction that wrote it committed"
                    }
                )
            Insertion.MissingPrintImage -> OrderWriteResult.UnknownPrintImage
            Insertion.Conflict ->
                liveOrderOfCart(input.cartId)?.let(OrderWriteResult::AlreadyPlaced)
        }

    /**
     * The transaction that writes the order.
     *
     * Its result is not an [OrderWriteResult], because the conflict cannot be finished here: the
     * transaction that hit `23505` is dead, and reading the order that won the race needs a fresh
     * one.
     */
    private suspend fun insert(
        input: PlaceOrderInput,
        snapshots: Map<ArticleVariantReference, CatalogVariant>,
    ): Insertion =
        executePostgresWrite(uniqueViolation = Insertion.Conflict) {
            write {
                if (!printImagesExistInTransaction(input)) {
                    return@write Insertion.MissingPrintImage
                }
                val orderId = insertOrderInTransaction(input)
                insertLinesInTransaction(orderId, input, snapshots)
                Insertion.Placed(orderId)
            }
        }

    /**
     * The live order of a cart, read after the unique index refused a second placement, or `null`
     * when a cancellation committed in between and the cart has no live order any more.
     *
     * That `null` is the whole reason [place] retries once instead of asserting. The window is
     * narrow — between the failed insert and this read — and it is bounded on purpose: a *second*
     * conflict without a live order would mean the index and this read disagree about what "live"
     * is, and that is a bug to see, not to loop over.
     */
    private suspend fun liveOrderOfCart(cartId: Long): OrderView? = read {
        Orders.selectAll()
            .where { (Orders.cartId eq cartId) and (Orders.status neq OrderStatus.CANCELLED.name) }
            .singleOrNull()
            ?.let { row -> row.toOrderView(linesInTransaction(row[Orders.id].value)) }
    }

    private suspend fun findOrder(orderId: Long): OrderView? = read {
        Orders.selectAll()
            .where { Orders.id eq orderId }
            .singleOrNull()
            ?.let { row -> row.toOrderView(linesInTransaction(orderId)) }
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

private fun insertOrderInTransaction(input: PlaceOrderInput): Long {
    val billing = input.effectiveBillingAddress
    return Orders.insertAndGetId { statement ->
            statement[cartId] = input.cartId
            statement[guestSessionToken] = input.guestToken
            statement[userId] = input.userId
            statement[promotionId] = input.promotionId
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

private fun assignUserInTransaction(
    userId: Long,
    owned: Op<Boolean>,
) {
    Orders.update({ owned and Orders.userId.isNull() }) { statement ->
        statement[Orders.userId] = userId
        statement[updatedAt] = CurrentTimestampWithTimeZone
    }
}

/**
 * "This order is the caller's": the signed-in customer it belongs to, or the guest token it was
 * placed with while it has no user yet.
 *
 * The second half of that rule is the whole hardening. A guest token stops opening an order the
 * moment the order is claimed, so a shared or stolen cookie cannot reach an account's history — and
 * a request that carries no identity at all matches nothing rather than everything.
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
