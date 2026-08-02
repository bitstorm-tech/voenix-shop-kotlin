package shop.voenix.checkout

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
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
 * Every capability is a fake, and every fake suspends exactly where the real one does — the whole
 * point of this test is the *ordering* of five independent commits, and a fake that answered
 * without suspending would prove an ordering the runtime does not have. What each capability does
 * behind its interface is proven in its own module; what is proven here is what a checkout does
 * with the answers.
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
                PayableOrderResult.AlreadyPaid to
                    CheckoutResult.OrderNotPayable.Reason.ALREADY_PAID,
                PayableOrderResult.Cancelled to CheckoutResult.OrderNotPayable.Reason.CANCELLED,
                PayableOrderResult.Free to CheckoutResult.OrderNotPayable.Reason.FREE,
            )
            .forEach { (answer, reason) ->
                val world = World(payable = answer)

                assertEquals(CheckoutResult.OrderNotPayable(reason), world.retry())
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
    ) {
        val events: MutableList<String> = mutableListOf()
        val closedCarts: MutableList<Long> = mutableListOf()
        val reserveArguments: MutableList<Long?> = mutableListOf()
        val placedInputs: MutableList<PlaceOrderInput> = mutableListOf()

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

        fun placedInput(): PlaceOrderInput = placedInputs.single()
    }

    private class FakeCarts(
        private val cart: CheckoutCart?,
        private val world: World,
    ) : CheckoutCarts {
        override suspend fun activeCart(
            guestToken: String,
            userId: Long?,
        ): CheckoutCart? {
            world.events += "activeCart"
            return cart
        }

        override suspend fun markCheckedOut(cartId: Long): Boolean {
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
            world.events += "reserve"
            world.reserveArguments += listOf(cartId, userId)
            return reservation
        }

        override suspend fun release(cartId: Long): Unit = error("A checkout never releases")

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
            world.events += "place"
            world.placedInputs += input
            return placement
        }

        override suspend fun payable(
            orderId: Long,
            userId: Long?,
            guestToken: String?,
        ): PayableOrderResult {
            world.events += "payable"
            return payable
        }
    }

    private class FakeOrderPayments(
        private val confirmation: OrderPaymentOutcome,
        private val world: World,
    ) : OrderPaymentGateway {
        override suspend fun confirm(orderId: Long): OrderPaymentOutcome {
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
            world.events += "start"
            return checkoutUrl
        }
    }

    private companion object {
        const val CART_ID = 42L
        const val ORDER_ID = 4711L
        const val PROMOTION_ID = 7L
        const val USER_ID = 3L
        const val GUEST_TOKEN = "guest-token"
        const val CHECKOUT_URL = "https://www.mollie.com/checkout/select-method/abc123"

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
