package shop.voenix.checkout

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.currentUserSession
import shop.voenix.auth.installGuestCapableRouteProtection
import shop.voenix.http.ApiError
import shop.voenix.promotion.toApiError

/**
 * The HTTP surface of the checkout: two routes that translate a request into one
 * [CheckoutOperations] call and its answer back.
 *
 * Both hang below one `/api/checkout` node, and that is a decision rather than a path style. Ktor
 * merges paths into one route tree, so a route-scoped plugin installed on a shared node would also
 * protect every sibling starting with the same segment. Owning the second segment keeps the
 * guest-capable CSRF protection installed here from reaching anything but the checkout — and lets
 * the module own its retry path, `/api/checkout/orders/{orderId}/payment`, without touching
 * `/api/orders`.
 *
 * Neither route hands out a guest cookie. The token is read and never minted (deviation D8): a
 * visitor without one has no cart, which the empty-cart answer already covers, and no order, which
 * the not-found answer already covers.
 */
internal object CheckoutRoutes {
    fun install(
        application: Application,
        checkouts: CheckoutOperations,
        guestTokens: GuestTokens,
    ) {
        application.routing {
            route(BASE_PATH) {
                installGuestCapableRouteProtection()

                post {
                    call.noStore()
                    val request = call.receive<CheckoutRequest>()
                    val result =
                        checkouts.checkout(
                            guestToken = guestTokens.tryGet(call),
                            userId = call.currentUserId(),
                            request = request,
                        )
                    call.respondCheckout(result)
                }

                // Retrying the payment of an order that was already placed (deviation D16). It has
                // no body at all: everything the payment needs is what the order stored.
                post("/orders/{orderId}/payment") {
                    call.noStore()
                    val orderId = call.orderIdOrRespond() ?: return@post
                    val result =
                        checkouts.startPayment(
                            orderId = orderId,
                            guestToken = guestTokens.tryGet(call),
                            userId = call.currentUserId(),
                        )
                    call.respondPaymentStart(result)
                }
            }
        }
    }

    private const val BASE_PATH = "/api/checkout"
}

/** Whatever a checkout answers is about one visitor's one order; no cache may keep any of it. */
private fun ApplicationCall.noStore() {
    response.header(HttpHeaders.CacheControl, "no-store")
}

private fun ApplicationCall.currentUserId(): Long? =
    currentUserSession()?.userId?.toLongOrNull()?.takeIf { id -> id > 0 }

/** An order id that is not a number names no order, which is the same answer as a foreign one. */
private suspend fun ApplicationCall.orderIdOrRespond(): Long? {
    val orderId = parameters["orderId"]?.toLongOrNull()
    if (orderId == null) {
        respond(HttpStatusCode.NotFound, ApiError("Order not found"))
    }
    return orderId
}

/**
 * A completed checkout is a created order: `201` and the `Location` of the order itself, whether or
 * not a payment had to be started for it.
 */
private suspend fun ApplicationCall.respondCheckout(result: CheckoutResult) {
    when (result) {
        is CheckoutResult.Started -> {
            response.header(HttpHeaders.Location, "/api/orders/${result.response.orderId}")
            respond(HttpStatusCode.Created, result.response)
        }
        else -> respondFailure(result)
    }
}

/** A retried payment creates nothing: the order already existed, so its answer is a plain `200`. */
private suspend fun ApplicationCall.respondPaymentStart(result: CheckoutResult) {
    when (result) {
        is CheckoutResult.Started -> respond(HttpStatusCode.OK, result.response)
        else -> respondFailure(result)
    }
}

/**
 * The one error table of this module: every refusal with the status it has and the stable `code` a
 * frontend branches on.
 *
 * The promotion reasons are deliberately not translated here. The mapping lives in the promotion
 * module, so a coupon rejected while it is entered into the cart and the same coupon rejected while
 * the checkout reserves it reach the customer as the very same answer.
 */
private suspend fun ApplicationCall.respondFailure(result: CheckoutResult) {
    when (result) {
        is CheckoutResult.Started -> error("A started checkout is not a failure")
        CheckoutResult.EmptyCart ->
            respond(HttpStatusCode.BadRequest, ApiError("Your cart is empty", code = "CART_EMPTY"))
        is CheckoutResult.PromotionRejected -> {
            val (status, error) = result.reason.toApiError()
            respond(status, error)
        }
        CheckoutResult.ItemUnavailable ->
            respond(
                HttpStatusCode.Conflict,
                ApiError(
                    "An item in your cart is no longer available",
                    code = "CART_ITEM_UNAVAILABLE",
                ),
            )
        CheckoutResult.ImageUnavailable ->
            respond(
                HttpStatusCode.Conflict,
                ApiError(
                    "An image in your cart is no longer available",
                    code = "CART_IMAGE_UNAVAILABLE",
                ),
            )
        CheckoutResult.TotalTooLarge ->
            respond(
                HttpStatusCode.Conflict,
                ApiError("Your cart total is too large", code = "CART_TOTAL_TOO_LARGE"),
            )
        // Deliberately vague: the checkout does not know whether the order was cancelled (D7).
        CheckoutResult.PaymentNotStarted ->
            respond(
                HttpStatusCode.BadGateway,
                ApiError("The payment could not be started", code = "PAYMENT_NOT_STARTED"),
            )
        CheckoutResult.OrderNotFound ->
            respond(HttpStatusCode.NotFound, ApiError("Order not found"))
        is CheckoutResult.OrderNotPayable -> respondNotPayable(result.reason)
        CheckoutResult.Invalid,
        CheckoutResult.UnexpectedFailure ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
    }
}

private suspend fun ApplicationCall.respondNotPayable(
    reason: CheckoutResult.OrderNotPayable.Reason
) {
    val error =
        when (reason) {
            CheckoutResult.OrderNotPayable.Reason.ALREADY_PAID ->
                ApiError("This order has already been paid", code = "ORDER_ALREADY_PAID")
            CheckoutResult.OrderNotPayable.Reason.CANCELLED ->
                ApiError("This order cannot be paid", code = "ORDER_NOT_PAYABLE")
            CheckoutResult.OrderNotPayable.Reason.FREE ->
                ApiError("This order cannot be paid", code = "ORDER_NOT_PAYABLE")
        }
    respond(HttpStatusCode.Conflict, error)
}
