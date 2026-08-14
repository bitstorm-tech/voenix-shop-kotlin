package shop.voenix.checkout

import java.util.Locale
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.cart.CheckoutCart
import shop.voenix.cart.CheckoutCarts
import shop.voenix.country.ShippableCountries
import shop.voenix.order.OrderPaymentGateway
import shop.voenix.order.OrderPaymentOutcome
import shop.voenix.order.OrderPlacement
import shop.voenix.order.OrderPlacementResult
import shop.voenix.order.PayableOrder
import shop.voenix.order.PayableOrderResult
import shop.voenix.order.PlaceOrderInput
import shop.voenix.payment.PaymentStarter
import shop.voenix.promotion.PromotionCodeResult
import shop.voenix.promotion.PromotionCodes

/**
 * The orchestration a checkout *is*: five commits in five modules, in the one order that leaves no
 * gap a designed mechanism does not already cover.
 *
 * There is no transaction here, and there cannot be one — every step commits inside the module that
 * owns it. What holds the sequence together instead is that each step is safe to repeat and each
 * gap has an owner:
 * - the cart is closed **last**, so a checkout that dies halfway leaves an `ACTIVE` cart the
 *   customer can simply submit again;
 * - a second submission does not place a second order: the order module answers with the one that
 *   won (`AlreadyPlaced`, deviation D15), and the payment module answers with that order's existing
 *   checkout URL without calling the provider;
 * - a provider that refuses cancels the order inside the payment module, and that cancellation
 *   releases the promotion reservation. The checkout only learns that no payment was started
 *   (deviation D7) and deliberately does not claim to know which of the two happened;
 * - a placement that refuses outright produces no order that could ever release the reservation, so
 *   this module gives it back itself before it answers.
 *
 * The guest token is read, never minted, and never logged (deviations D8 and D9): it is a bearer
 * credential, so the only identifier that reaches a log line here is the order id.
 */
@Suppress("LongParameterList")
internal class CheckoutService(
    private val carts: CheckoutCarts,
    private val promotions: PromotionCodes,
    private val orders: OrderPlacement,
    private val orderPayments: OrderPaymentGateway,
    private val payments: PaymentStarter,
    private val shippableCountries: ShippableCountries,
) : CheckoutOperations {
    override suspend fun checkout(
        guestToken: String?,
        userId: Long?,
        request: CheckoutRequest,
    ): CheckoutResult {
        // Neither a session nor a cookie means there is no cart this request could mean, which is
        // the same answer as an empty one (D8). A signed-in customer without a cookie has one: the
        // cart is theirs by user id since issue #77.
        if (guestToken == null && userId == null) return CheckoutResult.EmptyCart
        val cart = carts.activeCart(guestToken, userId) ?: return CheckoutResult.EmptyCart
        if (cart.lines.isEmpty()) return CheckoutResult.EmptyCart

        // Before anything is written: a cart whose amounts do not fit the order columns is refused,
        // so no reservation is taken for a checkout that could never be stored (D13).
        if (cart.subtotalCents + cart.shippingCents > Int.MAX_VALUE) {
            return CheckoutResult.TotalTooLarge
        }

        // …and a destination the shop does not ship to, for the same reason: the country admin is
        // the authority, so this is the last read-only guard before the first commit (issue #81).
        // Only the shipping address is checked; an invoice may go anywhere.
        if (!shippableCountries.isShippable(request.shippingAddress?.country.orEmpty())) {
            return CheckoutResult.ShippingCountryUnavailable
        }

        // A cart without a coupon reserves nothing; one with a coupon holds its capacity from here
        // until the order is paid, cancelled, or its payment ends terminally.
        val reserved =
            cart.promotionId?.let { promotionId ->
                when (val result = promotions.reserve(promotionId, cart.cartId, userId)) {
                    is PromotionCodeResult.Applicable -> result
                    else -> return CheckoutResult.PromotionRejected(result)
                }
            }

        val input = placeOrderInput(cart, guestToken, userId, request, reserved)
        return when (val placement = orders.place(input)) {
            is OrderPlacementResult.Placed -> settle(placement.order, cart.cartId)
            // The winning order, not the request just made: both submissions get the same answer.
            is OrderPlacementResult.AlreadyPlaced -> settle(placement.order, cart.cartId)
            is OrderPlacementResult.Invalid -> {
                // The request passed its own field rules, so whatever the placement refuses here is
                // an inconsistency this module produced — never something a client can fix.
                logger.error(
                    "Checkout assembled an order the placement refused: {}",
                    placement.errors,
                )
                refuse(CheckoutResult.Invalid, cart.cartId, reserved)
            }
            OrderPlacementResult.UnknownArticleReference ->
                refuse(CheckoutResult.ItemUnavailable, cart.cartId, reserved)
            OrderPlacementResult.UnknownPrintImage ->
                refuse(CheckoutResult.ImageUnavailable, cart.cartId, reserved)
        }
    }

    /**
     * The three answers that mean no order exists and none ever will for this cart as it stands:
     * whatever it reserved a moment ago is given back before the customer is told.
     *
     * Without this the hold would outlive every attempt to use it. A deleted article variant
     * refuses each retry the same way, and a reservation has no expiry (deviation D2) — so a
     * customer who gives up would leave the coupon's capacity blocked for everyone, forever.
     *
     * The release runs [NonCancellable] because a client that hangs up is exactly the customer who
     * never comes back: the compensation must finish even when the request that caused it does not.
     */
    private suspend fun refuse(
        result: CheckoutResult,
        cartId: Long,
        reserved: PromotionCodeResult.Applicable?,
    ): CheckoutResult {
        if (reserved != null) {
            withContext(NonCancellable) { promotions.releaseAbandoned(cartId) }
        }
        return result
    }

    override suspend fun startPayment(
        orderId: Long,
        guestToken: String?,
        userId: Long?,
    ): CheckoutResult =
        when (val payable = orders.payable(orderId, userId, guestToken)) {
            is PayableOrderResult.Payable -> retryPayment(payable.order)
            // Unknown and foreign are one answer, and neither reaches the provider.
            PayableOrderResult.NotFound -> CheckoutResult.OrderNotFound
            PayableOrderResult.AlreadyPaid -> CheckoutResult.OrderNotPayable.AlreadyPaid
            // Cancelled and free are one dead end: neither can ever be paid, and the customer can
            // do the same thing with both answers — nothing.
            PayableOrderResult.Cancelled,
            PayableOrderResult.Free -> CheckoutResult.OrderNotPayable.NotPayable
        }

    /**
     * The retry itself: nothing but the stored order and a payment for it. No cart is read and none
     * is closed — whatever cart this order came from was closed when it was placed.
     */
    private suspend fun retryPayment(order: PayableOrder): CheckoutResult =
        payments.start(order)?.let { checkoutUrl ->
            CheckoutResult.Started(CheckoutResponse(order.orderId, checkoutUrl))
        } ?: CheckoutResult.PaymentNotStarted

    /**
     * What happens to an order that now exists: a free one is confirmed here and now, a payable one
     * is handed to the payment module. The cart is closed by whichever of the two succeeded.
     */
    private suspend fun settle(
        order: PayableOrder,
        cartId: Long,
    ): CheckoutResult {
        // Not "placed": this is also the path a second submission takes, and that one placed
        // nothing — it was answered with the order that won.
        logger.info("Checkout proceeds with order {}", order.orderId)
        return when (order.totalCents) {
            0 -> confirmFreeOrder(order, cartId)
            else -> startOrderPayment(order, cartId)
        }
    }

    /**
     * A total of zero has nothing to pay: the order is confirmed straight away — which redeems the
     * promotion, queues production and the confirmation mail — and only then is the cart closed
     * (deviation D6). A confirmation that does not apply leaves the cart `ACTIVE`, so re-submitting
     * heals through `AlreadyPlaced` instead of stranding a checked-out cart behind an unconfirmed
     * order.
     */
    private suspend fun confirmFreeOrder(
        order: PayableOrder,
        cartId: Long,
    ): CheckoutResult =
        when (orderPayments.confirm(order.orderId)) {
            OrderPaymentOutcome.APPLIED,
            // A second submission of the same free cart confirms the same order again.
            OrderPaymentOutcome.ALREADY_APPLIED -> {
                closeCart(cartId)
                CheckoutResult.Started(CheckoutResponse(order.orderId, checkoutUrl = null))
            }
            OrderPaymentOutcome.REFUSED,
            OrderPaymentOutcome.UNKNOWN_ORDER -> {
                logger.error("Free order {} could not be confirmed by its checkout", order.orderId)
                CheckoutResult.UnexpectedFailure
            }
        }

    /**
     * The paid path: no checkout URL means no payment exists, and then the cart stays `ACTIVE` on
     * purpose — the customer's next attempt must find their cart, not an empty one (D7).
     */
    private suspend fun startOrderPayment(
        order: PayableOrder,
        cartId: Long,
    ): CheckoutResult {
        val checkoutUrl = payments.start(order) ?: return CheckoutResult.PaymentNotStarted
        closeCart(cartId)
        return CheckoutResult.Started(CheckoutResponse(order.orderId, checkoutUrl))
    }

    /**
     * Closes the cart this checkout bought from, and says so when it was not this call that did it.
     *
     * `markCheckedOut` answering `false` is idempotent by design, but not *here*: this checkout
     * read the cart as `ACTIVE` a moment ago and has settled an order for it since, so something
     * else ended that cart while the checkout was running. Since issue #110 removed the login
     * claim, a cart is only ever closed by a checkout, so the remaining cause is a concurrent
     * checkout of the very same cart — two tabs, or a retried request. The order is placed and the
     * payment exists either way, which is why this is a `warn` rather than an `error`: nothing is
     * broken for the customer, and the entry names the cart, never the guest token.
     */
    private suspend fun closeCart(cartId: Long) {
        if (!carts.markCheckedOut(cartId)) {
            logger.warn("Cart {} was no longer active when its checkout closed it", cartId)
        }
    }

    private companion object {
        val logger: Logger = LoggerFactory.getLogger(CheckoutService::class.java)
    }
}

/**
 * The two things a customer can do here, expressed once so that the routes stay a mapping from HTTP
 * to these calls and back.
 *
 * Both take the caller's identity as parameters rather than reading a cookie themselves: who is
 * checking out is an HTTP question, and answering it in the route is what lets a test drive a whole
 * checkout without a browser. [guestToken] is nullable in both because the token is *read* and
 * never minted (deviation D8) — a visitor without a cookie has no cart and no order, and each call
 * says so in its own way.
 */
internal interface CheckoutOperations {
    /**
     * Turns the caller's active cart into an order and, unless that order is free, into a payment.
     *
     * The five steps commit independently and in this order: the cart snapshot, the promotion
     * reservation, the placement, the settlement (a free order's confirmation or the payment), and
     * finally closing the cart. Nothing is marked checked out until there is something to show for
     * it.
     */
    suspend fun checkout(
        guestToken: String?,
        userId: Long?,
        request: CheckoutRequest,
    ): CheckoutResult

    /**
     * Starts the payment of the already placed order [orderId] again — the retry journey.
     *
     * The order is read back from the database rather than rebuilt from a request, so a retry
     * charges for the order that exists. An order that is not the caller's is answered exactly like
     * an unknown one, and no provider is ever called on its behalf.
     */
    suspend fun startPayment(
        orderId: Long,
        guestToken: String?,
        userId: Long?,
    ): CheckoutResult
}

/**
 * Everything a checkout — or a retried payment — can end in.
 *
 * It is a result of its own rather than the shared `OperationResult` because a checkout composes
 * four modules, and the reason it stopped is the only thing that tells a customer what to do next:
 * an empty cart is a different sentence from an exhausted coupon, and both are different from a
 * payment provider that would not create a payment.
 *
 * Two of the refusals are deliberately *not* here. An unexpected database failure is not mapped at
 * all — it surfaces as an exception and the HTTP runtime answers it — and a request that breaks its
 * own field *shape* never reaches an operation, because the Request Validation plugin rejects it
 * first. [Invalid] is therefore not the customer's mistake but this module's: the placement refused
 * an input the checkout itself assembled.
 *
 * [ShippingCountryUnavailable] is the one exception to that split, and for a reason: whether the
 * shop ships to a country is not a property of the request but of a table an admin maintains, so it
 * cannot be a rule of `CheckoutRequest`. It is answered here and rendered as the field error the
 * plugin would have produced.
 */
internal sealed interface CheckoutResult {
    /** The order exists and, unless it is free, so does the payment the customer is sent to. */
    data class Started(val response: CheckoutResponse) : CheckoutResult

    /** No cart, no guest token, or a cart without a single line — all the same sentence. */
    data object EmptyCart : CheckoutResult

    /**
     * The shop does not ship to the country of the shipping address (issue #81).
     *
     * It is the one refusal here that is the *customer's* to fix, and the only one whose answer is
     * therefore a field error rather than a code: the form is still on screen, and the field it
     * belongs to is `shippingAddress.country`. The billing address is deliberately not checked — an
     * invoice goes wherever the customer says.
     */
    data object ShippingCountryUnavailable : CheckoutResult

    /** The coupon the cart carries could not be reserved; [reason] is the promotion's own. */
    data class PromotionRejected(val reason: PromotionCodeResult) : CheckoutResult

    /** A line names an article variant the catalog no longer has. */
    data object ItemUnavailable : CheckoutResult

    /** A line names a print image that is gone. */
    data object ImageUnavailable : CheckoutResult

    /** The cart's amounts do not fit the cents the order columns hold (deviation D13). */
    data object TotalTooLarge : CheckoutResult

    /**
     * No payment was started. The checkout cannot tell whether the provider refused — in which case
     * the payment module has already cancelled the order — or whether the order's live payment slot
     * was contended away, in which case the order is untouched (deviation D7). It therefore claims
     * neither, and above all does not mark the cart checked out.
     */
    data object PaymentNotStarted : CheckoutResult

    /** The order does not exist, or belongs to somebody else — deliberately indistinguishable. */
    data object OrderNotFound : CheckoutResult

    /**
     * The order exists but no second payment journey can start for it — in one of the two ways the
     * customer can act on differently.
     *
     * The order module tells the four states apart (`PayableOrderResult`), and it keeps that
     * distinction because its own callers need it. Here only two of them survive: "you have already
     * paid this" is good news, everything else is the same dead end, and inventing a third sentence
     * for a customer who can do nothing with it would only be noise.
     */
    sealed interface OrderNotPayable : CheckoutResult {
        /** It is already `PAID`: there is nothing left to pay. */
        data object AlreadyPaid : OrderNotPayable

        /**
         * It will never be paid: it is `CANCELLED`, or its total is zero and it was confirmed
         * without a payment there could be a retry for. Both are one sentence to the customer.
         */
        data object NotPayable : OrderNotPayable
    }

    /** The placement refused the input this module built for it — a bug here, never a client's. */
    data object Invalid : CheckoutResult

    /** A step answered something that cannot be acted on; it has been logged. */
    data object UnexpectedFailure : CheckoutResult
}

/**
 * The placement input, assembled from three sources that each own their part: the stored cart owns
 * the lines and the amounts, the reserved [promotion] owns the discount — which the cart itself
 * calculates, so the customer sees the arithmetic they were shown — and the request owns the two
 * addresses.
 *
 * The amounts are narrowed to `Int` here and nowhere earlier, because the guard that makes the
 * narrowing safe runs in the service: a cart beyond `Int` cents never reaches this function, and a
 * discount can only ever reduce it.
 */
private fun placeOrderInput(
    cart: CheckoutCart,
    guestToken: String?,
    userId: Long?,
    request: CheckoutRequest,
    promotion: PromotionCodeResult.Applicable?,
): PlaceOrderInput =
    PlaceOrderInput(
        cartId = cart.cartId,
        userId = userId,
        guestToken = guestToken,
        promotionId = promotion?.id,
        shippingAddress = request.shippingAddress?.postalAddress.toOrderAddress(),
        billingAddress = request.billingAddress?.let { address -> address.toOrderAddress() },
        email = request.shippingAddress?.normalizedEmail.orEmpty(),
        phone = request.shippingAddress?.normalizedPhone,
        subtotalCents = cart.subtotalCents.toInt(),
        shippingCostCents = cart.shippingCents.toInt(),
        discountCents =
            promotion?.let { reserved -> cart.discountCents(reserved.discount).toInt() } ?: 0,
        lines = cart.lines.map(CheckoutCart.Line::toOrderLine),
    )

/**
 * The address as the order stores it, trimmed.
 *
 * A `null` here cannot happen behind the Request Validation plugin, and mapping it to blank fields
 * rather than throwing is deliberate: should it ever happen, the placement reports it as `Invalid`
 * — one code path for "the checkout built something the order refuses" — instead of a
 * `NullPointerException` nobody can read.
 *
 * The country code is upper-cased on top of the trim, because that is how [ShippableCountries]
 * reads it: a checkout that passed the shippability check with `"de"` would otherwise freeze `"de"`
 * into the order while the check compared `"DE"`. The snapshot has to say the same country the
 * decision was made about.
 */
private fun CheckoutRequest.AddressInput?.toOrderAddress(): PlaceOrderInput.Address =
    PlaceOrderInput.Address(
        firstName = this?.firstName.orEmpty().trim(),
        lastName = this?.lastName.orEmpty().trim(),
        street = this?.street.orEmpty().trim(),
        houseNumber = this?.houseNumber.orEmpty().trim(),
        postalCode = this?.postalCode.orEmpty().trim(),
        city = this?.city.orEmpty().trim(),
        country = this?.country.orEmpty().trim().uppercase(Locale.ROOT),
    )

private fun CheckoutCart.Line.toOrderLine(): PlaceOrderInput.Line =
    PlaceOrderInput.Line(
        articleId = articleId,
        variantId = variantId,
        quantity = quantity,
        priceCents = priceCents,
        promptPriceCents = promptPriceCents,
        promptId = promptId,
        printImageId = printImageId,
    )
