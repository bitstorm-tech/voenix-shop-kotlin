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
