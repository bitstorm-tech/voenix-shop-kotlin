package shop.voenix.checkout

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.math.BigDecimal
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import shop.voenix.cart.CheckoutCart
import shop.voenix.cart.CheckoutCarts
import shop.voenix.order.OrderPaymentGateway
import shop.voenix.order.OrderPaymentOutcome
import shop.voenix.order.OrderPlacement
import shop.voenix.order.OrderPlacementResult
import shop.voenix.order.PayableOrder
import shop.voenix.order.PayableOrderResult
import shop.voenix.order.PlaceOrderInput
import shop.voenix.payment.PaymentStarter
import shop.voenix.promotion.Discount
import shop.voenix.promotion.PromotionCodeResult
import shop.voenix.promotion.PromotionCodes

/**
 * The orchestration itself: which step runs, in which order, and what stops the sequence.
 *
 * Every capability is a fake, and every fake suspends exactly where the real one does — through
 * [dispatchLikeATransaction], in every member a checkout actually calls. The whole point of this
 * test is the *ordering* of five independent commits, and a fake that answered without suspending
 * would prove an ordering the runtime does not have. What each capability does behind its interface
 * is proven in its own module; what is proven here is what a checkout does with the answers.
 */
internal class CheckoutServiceTest {
    @Test
    fun `a visitor without a guest cookie is told their cart is empty, and nothing runs`() {
        val world = World()

        val result = world.checkout(guestToken = null)

        assertEquals(CheckoutResult.EmptyCart, result)
        assertEquals(emptyList(), world.events)
    }

    @Test
    fun `a visitor without a cart is told their cart is empty`() {
        val world = World(cart = null)

        assertEquals(CheckoutResult.EmptyCart, world.checkout())
        assertEquals(listOf("activeCart"), world.events)
    }

    @Test
    fun `a cart without a single line is empty too`() {
        val world = World(cart = cart(lines = emptyList(), subtotalCents = 0, shippingCents = 0))

        assertEquals(CheckoutResult.EmptyCart, world.checkout())
        assertEquals(listOf("activeCart"), world.events)
    }

    @Test
    fun `a cart beyond the cents an order can hold is refused before anything is reserved`() {
        val world =
            World(
                cart =
                    cart(
                        promotionId = PROMOTION_ID,
                        subtotalCents = Int.MAX_VALUE.toLong(),
                        shippingCents = 490,
                    )
            )

        assertEquals(CheckoutResult.TotalTooLarge, world.checkout())
        assertEquals(
            listOf("activeCart"),
            world.events,
            "Nothing may be reserved or written for a cart that could never be stored",
        )
    }

    @Test
    fun `a promotion that cannot be reserved stops the checkout before the order is placed`() {
        val world =
            World(
                cart = cart(promotionId = PROMOTION_ID),
                reservation = PromotionCodeResult.TotalExhausted,
            )

        assertEquals(
            CheckoutResult.PromotionRejected(PromotionCodeResult.TotalExhausted),
            world.checkout(),
        )
        assertEquals(listOf("activeCart", "reserve"), world.events)
    }

    @Test
    fun `a reserved promotion is the discount and the promotion id the order is placed with`() {
        val world =
            World(
                cart = cart(promotionId = PROMOTION_ID, subtotalCents = 4_000, shippingCents = 490),
                reservation =
                    PromotionCodeResult.Applicable(
                        id = PROMOTION_ID,
                        name = "Ten off",
                        couponCode = "TENOFF",
                        discount = Discount.Percentage(BigDecimal(10)),
                    ),
            )

        world.checkout()

        val placed = world.placedInput()
        assertEquals(PROMOTION_ID, placed.promotionId)
        assertEquals(449, placed.discountCents, "Ten percent of 4490 cents, rounded HALF_UP")
        assertEquals(4_000 + 490 - 449, placed.totalCents)
        assertEquals(listOf<Long?>(CART_ID, USER_ID), world.reserveArguments)
    }

    @Test
    fun `a cart without a promotion is placed without one, and nothing is reserved`() {
        val world = World()

        world.checkout()

        assertNull(world.placedInput().promotionId)
        assertEquals(0, world.placedInput().discountCents)
        assertEquals(listOf("activeCart", "place", "start", "markCheckedOut"), world.events)
    }

    @Test
    fun `the payment is started before the cart is closed`() {
        val world = World()

        val result = world.checkout()

        assertEquals(
            CheckoutResult.Started(CheckoutResponse(ORDER_ID, CHECKOUT_URL)),
            result,
        )
        assertEquals(listOf("activeCart", "place", "start", "markCheckedOut"), world.events)
        assertEquals(listOf(CART_ID), world.closedCarts)
    }

    @Test
    fun `an order that was already placed answers with the winning order, not the request`() {
        val world =
            World(
                placement =
                    OrderPlacementResult.AlreadyPlaced(payableOrder(orderId = 99, totalCents = 700))
            )

        val result = world.checkout()

        assertEquals(CheckoutResult.Started(CheckoutResponse(99, CHECKOUT_URL)), result)
        assertEquals(listOf("activeCart", "place", "start", "markCheckedOut"), world.events)
    }

    @Test
    fun `a free order is confirmed first and closed afterwards, without a payment`() {
        val world = World(placement = OrderPlacementResult.Placed(payableOrder(totalCents = 0)))

        val result = world.checkout()

        assertEquals(CheckoutResult.Started(CheckoutResponse(ORDER_ID, null)), result)
        assertEquals(listOf("activeCart", "place", "confirm", "markCheckedOut"), world.events)
    }

    @Test
    fun `a repeated free checkout confirms the same order again and still succeeds`() {
        val world =
            World(
                placement = OrderPlacementResult.AlreadyPlaced(payableOrder(totalCents = 0)),
                confirmation = OrderPaymentOutcome.ALREADY_APPLIED,
            )

        assertEquals(
            CheckoutResult.Started(CheckoutResponse(ORDER_ID, null)),
            world.checkout(),
        )
        assertEquals(listOf("activeCart", "place", "confirm", "markCheckedOut"), world.events)
    }

    @Test
    fun `a free order whose confirmation is refused leaves the cart active`() {
        val world =
            World(
                placement = OrderPlacementResult.Placed(payableOrder(totalCents = 0)),
                confirmation = OrderPaymentOutcome.REFUSED,
            )

        assertEquals(CheckoutResult.UnexpectedFailure, world.checkout())
        assertEquals(listOf("activeCart", "place", "confirm"), world.events)
        assertEquals(emptyList(), world.closedCarts, "A cart is only closed by a success")
    }

    @Test
    fun `a payment that could not be started leaves the cart active`() {
        val world = World(checkoutUrl = null)

        assertEquals(CheckoutResult.PaymentNotStarted, world.checkout())
        assertEquals(listOf("activeCart", "place", "start"), world.events)
        assertEquals(
            emptyList(),
            world.closedCarts,
            "The customer must find their cart again, whatever happened to the order",
        )
    }

    @Test
    fun `a placement the checkout itself broke is never a customer error`() {
        val world =
            World(
                placement =
                    OrderPlacementResult.Invalid(mapOf("email" to listOf("Email is required")))
            )

        assertEquals(CheckoutResult.Invalid, world.checkout())
        assertEquals(listOf("activeCart", "place"), world.events)
    }

    @Test
    fun `an unknown article variant and an unknown print image are their own conflicts`() {
        assertEquals(
            CheckoutResult.ItemUnavailable,
            World(placement = OrderPlacementResult.UnknownArticleReference).checkout(),
        )
        assertEquals(
            CheckoutResult.ImageUnavailable,
            World(placement = OrderPlacementResult.UnknownPrintImage).checkout(),
        )
    }

    @Test
    fun `every placement refusal gives the reservation back before it answers`() {
        listOf(
                OrderPlacementResult.Invalid(mapOf("email" to listOf("Email is required"))),
                OrderPlacementResult.UnknownArticleReference,
                OrderPlacementResult.UnknownPrintImage,
            )
            .forEach { placement ->
                val world =
                    World(
                        cart = reservedCart(),
                        reservation = APPLICABLE,
                        placement = placement,
                    )

                world.checkout()

                assertEquals(
                    listOf("activeCart", "reserve", "place", "releaseAbandoned"),
                    world.events,
                    "The hold outlives every retry of a refusal that cannot heal (D2): $placement",
                )
                assertEquals(listOf(CART_ID), world.releasedCarts)
            }
    }

    @Test
    fun `a placed order keeps its reservation, because only its payment may end it`() {
        val world = World(cart = reservedCart(), reservation = APPLICABLE)

        world.checkout()

        assertEquals(
            listOf("activeCart", "reserve", "place", "start", "markCheckedOut"),
            world.events,
        )
        assertEquals(emptyList(), world.releasedCarts)
    }

    @Test
    fun `a refused placement of a cart without a coupon releases nothing`() {
        val world = World(placement = OrderPlacementResult.UnknownArticleReference)

        assertEquals(CheckoutResult.ItemUnavailable, world.checkout())
        assertEquals(listOf("activeCart", "place"), world.events)
    }

    /**
     * The customer who closes the tab on the error is exactly the one who never comes back, so the
     * release must survive their cancelled request — which is what `NonCancellable` is for. The
     * placement ends the job while it answers, and every suspending step after it would abort.
     */
    @Test
    fun `a customer who hung up on the refusal still gets their capacity back`() {
        val world =
            World(
                cart = reservedCart(),
                reservation = APPLICABLE,
                placement = OrderPlacementResult.UnknownArticleReference,
                hangUpWhilePlacing = true,
            )

        val job = world.checkoutOnItsOwnJob()

        assertTrue(job.isCancelled, "the request really ended while the order was being placed")
        assertEquals(listOf(CART_ID), world.releasedCarts)
    }

    @Test
    fun `the blank phone the frontend sends becomes no phone at all`() {
        val world = World()

        world.checkout(request = frontendRequest())

        val placed = world.placedInput()
        assertNull(placed.phone, "A blank phone is the absent one (D12)")
        assertEquals("ada@example.org", placed.email)
        assertNull(placed.billingAddress, "No billing address means the shipping one")
        assertEquals("München", placed.shippingAddress.city)
        assertEquals("DE", placed.shippingAddress.country)
    }

    @Test
    fun `a given billing address is placed as its own address`() {
        val world = World()

        world.checkout(
            request =
                frontendRequest()
                    .copy(
                        billingAddress =
                            CheckoutRequest.AddressInput(
                                firstName = "Grace",
                                lastName = "Hopper",
                                street = "Rechenweg",
                                houseNumber = "1",
                                postalCode = "10115",
                                city = "Berlin",
                                country = "DE",
                            )
                    )
        )

        assertEquals("Berlin", world.placedInput().billingAddress?.city)
    }

    @Test
    fun `the cart snapshot is what the order is placed from`() {
        val world = World()

        world.checkout()

        val placed = world.placedInput()
        assertEquals(CART_ID, placed.cartId)
        assertEquals(GUEST_TOKEN, placed.guestToken)
        assertEquals(USER_ID, placed.userId)
        assertEquals(2_000, placed.subtotalCents)
        assertEquals(490, placed.shippingCostCents)
        assertEquals(1, placed.lines.size)
        assertEquals(10, placed.lines.single().articleId)
    }

    @Test
    fun `retrying reads the stored order and starts its payment, without touching a cart`() {
        val world = World()

        val result = world.retry()

        assertEquals(CheckoutResult.Started(CheckoutResponse(ORDER_ID, CHECKOUT_URL)), result)
        assertEquals(listOf("payable", "start"), world.events)
    }

    @Test
    fun `an unknown or foreign order never reaches the provider`() {
        val world = World(payable = PayableOrderResult.NotFound)

        assertEquals(CheckoutResult.OrderNotFound, world.retry())
        assertEquals(listOf("payable"), world.events)
    }

    @Test
    fun `an order that is paid, cancelled, or free cannot be paid again`() {
        listOf(
                PayableOrderResult.AlreadyPaid to CheckoutResult.OrderNotPayable.AlreadyPaid,
                // Cancelled and free are the same dead end to a customer, and the order module
                // keeps the four-way distinction for its own callers.
                PayableOrderResult.Cancelled to CheckoutResult.OrderNotPayable.NotPayable,
                PayableOrderResult.Free to CheckoutResult.OrderNotPayable.NotPayable,
            )
            .forEach { (answer, expected) ->
                val world = World(payable = answer)

                assertEquals(expected, world.retry())
                assertEquals(listOf("payable"), world.events)
            }
    }

    @Test
    fun `a retry whose payment cannot be started reports exactly that`() {
        val world = World(checkoutUrl = null)

        assertEquals(CheckoutResult.PaymentNotStarted, world.retry())
        assertEquals(listOf("payable", "start"), world.events)
    }

    /**
     * The guest token is a bearer credential: whoever reads it from a log file *is* that visitor
     * (deviation D9). The .NET original logged it next to the order id on every creation; this
     * module logs the order id and nothing else that identifies anybody.
     *
     * The assertion is deliberately blunt — no message anywhere may contain the token — and the
     * token really did travel through this checkout, which the placement input proves.
     */
    @Test
    fun `a checkout logs its order id and never the guest token`() {
        val world = World()
        val events = ListAppender<ILoggingEvent>().apply { start() }
        val moduleLogger = LoggerFactory.getLogger("shop.voenix.checkout") as Logger
        moduleLogger.addAppender(events)
        val result =
            try {
                world.checkout()
            } finally {
                moduleLogger.detachAppender(events)
            }

        assertEquals(CheckoutResult.Started(CheckoutResponse(ORDER_ID, CHECKOUT_URL)), result)
        assertEquals(
            GUEST_TOKEN,
            world.placedInput().guestToken,
            "the token this checkout ran with is the one the log must not contain",
        )
        val messages = events.list.map(ILoggingEvent::getFormattedMessage)
        assertTrue(
            messages.any { message -> message.contains(ORDER_ID.toString()) },
            "the order id is the one identifier a checkout owes its log, but got $messages",
        )
        assertTrue(
            messages.none { message -> message.contains(GUEST_TOKEN) },
            "the guest token is a bearer credential and may never be logged (D9): $messages",
        )
    }

    /**
     * The service under test together with the five fakes it composes, all writing into one event
     * log — which is what makes "confirmed, *then* closed" a statement this test can make.
     */
    private class World(
        cart: CheckoutCart? = cart(),
        reservation: PromotionCodeResult = PromotionCodeResult.InvalidCode,
        placement: OrderPlacementResult = OrderPlacementResult.Placed(payableOrder()),
        payable: PayableOrderResult = PayableOrderResult.Payable(payableOrder()),
        confirmation: OrderPaymentOutcome = OrderPaymentOutcome.APPLIED,
        checkoutUrl: String? = CHECKOUT_URL,
        /** Ends the caller's job while the placement runs, the way a closed tab would. */
        val hangUpWhilePlacing: Boolean = false,
    ) {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val closedCarts: MutableList<Long> = mutableListOf()
        val reserveArguments: MutableList<Long?> = mutableListOf()
        val placedInputs: MutableList<PlaceOrderInput> = mutableListOf()
        val releasedCarts: MutableList<Long> = Collections.synchronizedList(mutableListOf())

        private val carts = FakeCarts(cart, this)
        private val promotions = FakePromotions(reservation, this)
        private val orders = FakeOrders(placement, payable, this)
        private val orderPayments = FakeOrderPayments(confirmation, this)
        private val payments = FakePayments(checkoutUrl, this)

        private val service = CheckoutService(carts, promotions, orders, orderPayments, payments)

        fun checkout(
            guestToken: String? = GUEST_TOKEN,
            request: CheckoutRequest = frontendRequest(),
        ): CheckoutResult = runBlocking { service.checkout(guestToken, USER_ID, request) }

        fun retry(): CheckoutResult = runBlocking {
            service.startPayment(ORDER_ID, GUEST_TOKEN, USER_ID)
        }

        /** The checkout on a job of its own, so the test can watch that job be cancelled. */
        fun checkoutOnItsOwnJob(): Job = runBlocking {
            val job =
                launch(Dispatchers.Default) {
                    service.checkout(GUEST_TOKEN, USER_ID, frontendRequest())
                }
            job.join()
            job
        }

        fun placedInput(): PlaceOrderInput = placedInputs.single()
    }

    private class FakeCarts(
        private val cart: CheckoutCart?,
        private val world: World,
    ) : CheckoutCarts {
        override suspend fun activeCart(guestToken: String): CheckoutCart? {
            dispatchLikeATransaction()
            world.events += "activeCart"
            return cart
        }

        override suspend fun markCheckedOut(cartId: Long): Boolean {
            dispatchLikeATransaction()
            world.events += "markCheckedOut"
            world.closedCarts += cartId
            return true
        }
    }

    private class FakePromotions(
        private val reservation: PromotionCodeResult,
        private val world: World,
    ) : PromotionCodes {
        override suspend fun validate(
            code: String,
            userId: Long?,
            reservationKey: Long?,
        ): PromotionCodeResult = error("A checkout never validates a code")

        override suspend fun reserve(
            promotionId: Long,
            cartId: Long,
            userId: Long?,
        ): PromotionCodeResult {
            dispatchLikeATransaction()
            world.events += "reserve"
            world.reserveArguments += listOf(cartId, userId)
            return reservation
        }

        override suspend fun release(cartId: Long): Unit =
            error("A checkout has no transaction a release could join")

        /**
         * The one member whose suspension a test depends on directly: the cancellation test below
         * would pass with `NonCancellable` removed if this fake answered on the caller's own
         * cancelled job.
         */
        override suspend fun releaseAbandoned(cartId: Long) {
            dispatchLikeATransaction()
            world.events += "releaseAbandoned"
            world.releasedCarts += cartId
        }

        override suspend fun redeem(
            promotionId: Long,
            orderId: Long,
            cartId: Long,
            userId: Long?,
        ): PromotionCodeResult = error("A checkout never redeems")

        override suspend fun find(
            promotionIds: Set<Long>
        ): Map<Long, PromotionCodeResult.Applicable> = error("A checkout never reads promotions")
    }

    private class FakeOrders(
        private val placement: OrderPlacementResult,
        private val payable: PayableOrderResult,
        private val world: World,
    ) : OrderPlacement {
        override suspend fun place(input: PlaceOrderInput): OrderPlacementResult {
            dispatchLikeATransaction()
            world.events += "place"
            world.placedInputs += input
            if (world.hangUpWhilePlacing) currentCoroutineContext().job.cancel()
            return placement
        }

        override suspend fun payable(
            orderId: Long,
            userId: Long?,
            guestToken: String?,
        ): PayableOrderResult {
            dispatchLikeATransaction()
            world.events += "payable"
            return payable
        }
    }

    private class FakeOrderPayments(
        private val confirmation: OrderPaymentOutcome,
        private val world: World,
    ) : OrderPaymentGateway {
        override suspend fun confirm(orderId: Long): OrderPaymentOutcome {
            dispatchLikeATransaction()
            world.events += "confirm"
            return confirmation
        }

        override suspend fun cancel(orderId: Long): OrderPaymentOutcome =
            error("A checkout never cancels an order")

        override suspend fun paymentEnded(orderId: Long): Unit =
            error("A checkout never ends a payment")
    }

    private class FakePayments(
        private val checkoutUrl: String?,
        private val world: World,
    ) : PaymentStarter {
        override suspend fun start(order: PayableOrder): String? {
            // The real payment start is an HTTP call to the provider plus its own transaction, so
            // this is the longest suspension of them all.
            dispatchLikeATransaction()
            world.events += "start"
            return checkoutUrl
        }
    }

    private companion object {
        const val CART_ID = 42L
        const val ORDER_ID = 4711L
        const val PROMOTION_ID = 7L
        const val USER_ID = 3L

        /** Distinctive on purpose: the D9 test below searches the whole log for this string. */
        const val GUEST_TOKEN = "guest-token-1f0a7c94b2e5"
        const val CHECKOUT_URL = "https://www.mollie.com/checkout/select-method/abc123"

        /** The reservation a cart with a coupon comes back with. */
        val APPLICABLE: PromotionCodeResult.Applicable =
            PromotionCodeResult.Applicable(
                id = PROMOTION_ID,
                name = "Ten off",
                couponCode = "TENOFF",
                discount = Discount.Percentage(BigDecimal(10)),
            )

        /** A cart carrying a coupon, so the checkout under test really holds a reservation. */
        fun reservedCart(): CheckoutCart = cart(promotionId = PROMOTION_ID)

        fun cart(
            promotionId: Long? = null,
            lines: List<CheckoutCart.Line> =
                listOf(
                    CheckoutCart.Line(
                        articleId = 10,
                        variantId = 20,
                        quantity = 2,
                        priceCents = 900,
                        promptId = null,
                        promptPriceCents = 100,
                        printImageId = null,
                    )
                ),
            subtotalCents: Long = 2_000,
            shippingCents: Long = 490,
        ): CheckoutCart =
            CheckoutCart(
                cartId = CART_ID,
                promotionId = promotionId,
                lines = lines,
                subtotalCents = subtotalCents,
                shippingCents = shippingCents,
            )

        fun payableOrder(
            orderId: Long = ORDER_ID,
            totalCents: Int = 2_490,
        ): PayableOrder =
            PayableOrder(
                orderId = orderId,
                totalCents = totalCents,
                email = "ada@example.org",
                phone = null,
                shippingAddress = payableAddress(),
                billingAddress = payableAddress(),
            )

        fun payableAddress(): PayableOrder.Address =
            PayableOrder.Address(
                firstName = "Ada",
                lastName = "Lovelace",
                street = "Musterweg",
                houseNumber = "12a",
                postalCode = "80331",
                city = "München",
                country = "DE",
            )

        /** The exact shape the Vue store sends today, blank phone included. */
        fun frontendRequest(): CheckoutRequest =
            CheckoutRequest(
                shippingAddress =
                    CheckoutRequest.ShippingAddressInput(
                        firstName = "Ada",
                        lastName = "Lovelace",
                        street = "Musterweg",
                        houseNumber = "12a",
                        postalCode = "80331",
                        city = "München",
                        country = "DE",
                        email = "ada@example.org",
                        phone = "",
                    ),
                billingAddress = null,
            )
    }
}

/**
 * The suspension every fake above owes the capability it stands in for.
 *
 * Each of those capabilities is a `suspendTransaction` behind `Dispatchers.IO` — or, for the
 * payment start, an HTTP call — so every one of them really does leave the caller's thread and
 * really does observe cancellation. Dispatching an empty block is the cheapest honest imitation:
 * the fake answers on a different thread and resumes the checkout the way the runtime would, so an
 * ordering this test proves is an ordering the composed application has.
 */
private suspend fun dispatchLikeATransaction() {
    withContext(Dispatchers.IO) {}
}
