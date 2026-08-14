package shop.voenix.order

import shop.voenix.validation.ValidationErrors

/**
 * The two things a checkout needs from the order module: place the order it has just priced, and
 * read back an order whose payment has to be started again.
 *
 * Like [OrderPaymentGateway], it is declared *and* implemented here and handed to the checkout
 * module at composition time. What an order is, what it snapshots, and who may see it are this
 * module's decisions; the caller only supplies the numbers it has already decided
 * ([PlaceOrderInput]) and receives the snapshot a payment is built from ([PayableOrder]).
 * Everything behind that — the tables, the repository, the internal `OrderView` the customer routes
 * serialize — stays inside.
 *
 * Neither call maps an unexpected database failure to a result. It surfaces as an exception
 * together with the rollback that caused it, so the checkout answers it with its own error policy.
 */
public interface OrderPlacement {
    /**
     * Places one order from [input]: the field rules first, then the catalog snapshot, then the
     * write.
     *
     * A cart that already has a live order is answered with that order rather than a second one —
     * see [OrderPlacementResult.AlreadyPlaced], which is what makes a double-submitted checkout
     * harmless.
     */
    public suspend fun place(input: PlaceOrderInput): OrderPlacementResult

    /**
     * The order [orderId] as a payment would be built from it, for the caller identified by
     * [userId] and [guestToken] — the read behind the retry-payment journey.
     *
     * The ownership rule is the same one the customer's own order reads apply: an order belongs to
     * the signed-in customer whose id it carries, or to the guest token it was placed with *while
     * it has no user yet*. Anything else is [PayableOrderResult.NotFound], whether the id is
     * unknown or simply somebody else's.
     */
    public suspend fun payable(
        orderId: Long,
        userId: Long?,
        guestToken: String?,
    ): PayableOrderResult
}

/**
 * What placing an order can end in.
 *
 * Placement has no HTTP surface of its own — the checkout module is its caller — so it answers with
 * its own result instead of the shared `OperationResult`. That is deliberate: the two outcomes that
 * matter to a checkout are not HTTP shapes. [AlreadyPlaced] is a *success* the caller must be able
 * to tell from [Placed], because it means "this cart already has that order, use it" and not "try
 * again"; and an unexpected database failure is not mapped to a result at all but surfaces as an
 * exception, exactly like every other capability of this codebase (`ArticleCatalog`,
 * `PromotionCodes`), so the calling module answers it with its own error policy.
 *
 * Both successes carry a [PayableOrder] rather than the placed *request*, and for [AlreadyPlaced]
 * that is the whole point: the answer describes the order the database already holds, so a second,
 * edited submission is silently answered with what the first one stored (deviation D15). Nothing in
 * the service prevents that second placement — the partial unique index `ux_orders_live_cart` does,
 * and the repository turns the resulting `23505` into the order that won the race. A preliminary
 * "does this cart have an order" query would race and is deliberately absent.
 *
 * [UnknownArticleReference] and [UnknownPrintImage] are the two references placement refuses to
 * snapshot blindly. The legacy checkout wrote an empty article name for a deleted article and
 * produced an order nobody could ever produce; here the placement is rejected instead.
 */
public sealed interface OrderPlacementResult {
    /** The order was written by *this* call. */
    public data class Placed(public val order: PayableOrder) : OrderPlacementResult

    /** This cart already had a live order; [order] is that one, never the request just made. */
    public data class AlreadyPlaced(public val order: PayableOrder) : OrderPlacementResult

    /** The input broke its own field rules; nothing was written. */
    public data class Invalid(public val errors: ValidationErrors) : OrderPlacementResult

    /** At least one `(articleId, variantId)` pair names nothing the catalog knows. */
    public data object UnknownArticleReference : OrderPlacementResult

    /** At least one line names a print image that does not exist. */
    public data object UnknownPrintImage : OrderPlacementResult
}

/**
 * What asking for an order that is supposed to be paid can end in.
 *
 * The four refusals are the reasons an order cannot start a *second* payment journey, and each of
 * them is a different sentence to the customer: an order that is not theirs (or does not exist) is
 * [NotFound], one that has been paid is [AlreadyPaid], one that was cancelled is [Cancelled], and a
 * free order is [Free] — it was confirmed without a payment and never had one to retry.
 *
 * [NotFound] deliberately covers unknown *and* foreign ids, exactly as the customer's own order
 * reads do: the two are indistinguishable, so nobody can probe for the existence of somebody else's
 * order — and no provider call is ever made on their behalf.
 */
public sealed interface PayableOrderResult {
    /** The order exists, belongs to the caller, is still pending, and costs money. */
    public data class Payable(public val order: PayableOrder) : PayableOrderResult

    /** Unknown id, or an order that belongs to somebody else — deliberately the same answer. */
    public data object NotFound : PayableOrderResult

    /** The order is `PAID`; there is nothing left to pay. */
    public data object AlreadyPaid : PayableOrderResult

    /** The order is `CANCELLED`; it will never be paid. */
    public data object Cancelled : PayableOrderResult

    /** The order's total is zero: it is confirmed without a payment and has none to retry. */
    public data object Free : PayableOrderResult
}

/**
 * A placed order as everything that still has to be *paid for* reads it.
 *
 * This is the order module's exchange snapshot, and it is deliberately not [OrderView]: a payment
 * provider needs the amount, the customer, and the two postal addresses, and nothing else an order
 * has. The lines, the status, the timestamps, and the payment status stay inside, which is what
 * keeps [OrderView] internal and serializable for the customer's own routes only.
 *
 * It is answered by both halves of [OrderPlacement] — a fresh placement and the retry read — so the
 * two journeys that start a payment hand their consumer the very same shape, built from the stored
 * columns either way. That is what makes a retried payment describe the order that exists rather
 * than the request that asked for it.
 *
 * [phone] is optional and stays exactly as the customer typed it; turning it into something a
 * provider accepts is that adapter's job, not this module's.
 */
public data class PayableOrder(
    public val orderId: Long,
    public val totalCents: Int,
    public val email: String,
    public val phone: String?,
    public val shippingAddress: Address,
    public val billingAddress: Address,
) {
    /**
     * One postal address as the order stored it.
     *
     * Street and house number stay two fields, because that is how the shop holds them; a provider
     * that wants one line joins them in its own adapter. [billingAddress] is always present here —
     * "same address" was already resolved into stored columns when the order was placed.
     */
    public data class Address(
        public val firstName: String,
        public val lastName: String,
        public val street: String,
        public val houseNumber: String,
        public val postalCode: String,
        public val city: String,
        public val country: String,
    )
}

/**
 * The order [orderId] that was just written from this input, as its payment reads it.
 *
 * A placement that committed wrote exactly these values, so this is the same snapshot a read of the
 * row would answer with — including the "same address" fallback, which
 * [PlaceOrderInput.effectiveBillingAddress] has already resolved into the columns that were stored.
 */
internal fun PlaceOrderInput.toPayableOrder(orderId: Long): PayableOrder =
    PayableOrder(
        orderId = orderId,
        totalCents = totalCents,
        email = email,
        phone = phone,
        shippingAddress = shippingAddress.toPayableAddress(),
        billingAddress = effectiveBillingAddress.toPayableAddress(),
    )

private fun PlaceOrderInput.Address.toPayableAddress(): PayableOrder.Address =
    PayableOrder.Address(
        firstName = firstName,
        lastName = lastName,
        street = street,
        houseNumber = houseNumber,
        postalCode = postalCode,
        city = city,
        country = country,
    )
