package shop.voenix.checkout

import io.ktor.server.application.Application
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig
import shop.voenix.auth.GuestTokens
import shop.voenix.cart.CheckoutCarts
import shop.voenix.country.ShippableCountries
import shop.voenix.order.OrderPaymentGateway
import shop.voenix.order.OrderPlacement
import shop.voenix.payment.PaymentStarter
import shop.voenix.promotion.PromotionCodes
import shop.voenix.validation.toRequestValidationResult

/**
 * The runtime handle of the installed checkout module.
 *
 * Unlike Cart's or Order's it is `internal`, because this module exports nothing: it owns no table,
 * opens no transaction, and no other module consumes it. It is the last consumer in the chain — the
 * place where five modules' capabilities meet — so the composition root only installs it and moves
 * on, which is why the production install answers with `Unit`.
 */
internal class CheckoutModule(
    private val operations: CheckoutOperations,
    private val guestTokens: GuestTokens,
) {
    fun install(application: Application): Unit =
        application.installCheckoutRoutes(operations, guestTokens)
}

/**
 * Assembles the checkout module from the capabilities it composes.
 *
 * Each one is the *whole* of what its module contributes: [carts] answers the priced snapshot and
 * closes the cart, [promotions] holds the coupon's capacity while the checkout runs, [orders]
 * places the order and reads a payable one back, [orderPayments] confirms the free order that never
 * has a payment, [payments] starts the one that does, and [shippableCountries] answers whether the
 * shop ships to the address the customer typed. Nothing else about those modules is reachable from
 * here — no repository, no table, no transaction.
 */
@Suppress("LongParameterList")
internal fun createCheckoutModule(
    carts: CheckoutCarts,
    promotions: PromotionCodes,
    orders: OrderPlacement,
    orderPayments: OrderPaymentGateway,
    payments: PaymentStarter,
    shippableCountries: ShippableCountries,
    guestTokens: GuestTokens,
): CheckoutModule =
    CheckoutModule(
        operations =
            CheckoutService(
                carts = carts,
                promotions = promotions,
                orders = orders,
                orderPayments = orderPayments,
                payments = payments,
                shippableCountries = shippableCountries,
            ),
        guestTokens = guestTokens,
    )

/**
 * Installs the two checkout routes.
 *
 * Install it after cart, order, payment, promotion, and country — it consumes all five and is
 * consumed by none of them. [guestTokens] is the guest identity behind an anonymous checkout; it is
 * only ever *read* here (deviation D8).
 *
 * There is no handle to keep: this module exports nothing.
 */
@Suppress("LongParameterList")
public fun Application.installCheckoutModule(
    carts: CheckoutCarts,
    promotions: PromotionCodes,
    orders: OrderPlacement,
    orderPayments: OrderPaymentGateway,
    payments: PaymentStarter,
    shippableCountries: ShippableCountries,
    guestTokens: GuestTokens,
): Unit =
    createCheckoutModule(
            carts,
            promotions,
            orders,
            orderPayments,
            payments,
            shippableCountries,
            guestTokens,
        )
        .install(this)

public fun RequestValidationConfig.validateCheckoutRequests() {
    validate<CheckoutRequest> { input -> input.toRequestValidationResult() }
}
