package shop.voenix.cart

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.currentUserSession
import shop.voenix.auth.installGuestCapableRouteProtection
import shop.voenix.http.ApiError
import shop.voenix.image.receiveUploadedImage
import shop.voenix.operation.OperationResult
import shop.voenix.promotion.PromotionCodeResult

/**
 * The HTTP surface of the cart: seven routes that translate a request into one [CartOperations]
 * call and its answer back.
 *
 * The whole subtree hangs below one `/api/cart` node, and that is a decision rather than a path
 * style. Ktor merges paths into one route tree, so a route-scoped plugin installed on a shared node
 * would also protect every sibling that happens to start with the same segment. Owning the second
 * segment keeps the guest-capable CSRF protection installed here from reaching anything but the
 * cart.
 *
 * Reads and mutations differ in exactly one more way: a mutation calls `getOrCreate` and therefore
 * hands out the guest cookie, a read calls `tryGet` and never creates a guest. Looking at a cart
 * must not turn a visitor into a tracked one.
 */
internal object CartRoutes {
    fun install(
        application: Application,
        carts: CartOperations,
        guestTokens: GuestTokens,
    ) {
        application.routing {
            route(BASE_PATH) {
                installGuestCapableRouteProtection()

                get {
                    // Cart contents are per-visitor and change constantly; no cache may keep them.
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    when (val owner = call.readingOwner(guestTokens)) {
                        null -> call.respond(CartView.EMPTY)
                        else -> call.respondResult(carts.cart(owner))
                    }
                }

                post("/images") {
                    val owner = call.mutatingOwner(guestTokens)
                    when (val result = carts.uploadPrintImage(owner, call.receiveUploadedImage())) {
                        is OperationResult.Success ->
                            call.respond(HttpStatusCode.Created, result.value)
                        else -> call.respondFailure(result)
                    }
                }

                route("/items") {
                    post {
                        val owner = call.mutatingOwner(guestTokens)
                        val input = call.receive<AddCartItemInput>()
                        call.respondResult(carts.addItem(owner, input))
                    }

                    patch("/{itemId}") {
                        val owner = call.mutatingOwner(guestTokens)
                        val itemId = call.itemIdOrRespond() ?: return@patch
                        val input = call.receive<CartQuantityInput>()
                        call.respondResult(carts.updateQuantity(owner, itemId, input))
                    }

                    delete("/{itemId}") {
                        val owner = call.mutatingOwner(guestTokens)
                        val itemId = call.itemIdOrRespond() ?: return@delete
                        call.respondResult(carts.removeItem(owner, itemId))
                    }
                }

                route("/promotion") {
                    post {
                        val owner = call.mutatingOwner(guestTokens)
                        val input = call.receive<PromotionCodeInput>()
                        call.respondPromotion(carts.applyPromotion(owner, input))
                    }

                    delete {
                        val owner = call.mutatingOwner(guestTokens)
                        call.respondResult(carts.removePromotion(owner))
                    }
                }
            }
        }
    }

    private const val BASE_PATH = "/api/cart"
}

/**
 * Who this mutating request is for. A visitor without a guest cookie receives one here — the first
 * mutation is what makes an anonymous browser addressable, and nothing before it needs to.
 */
private fun ApplicationCall.mutatingOwner(guestTokens: GuestTokens): CartOwner =
    CartOwner(guestToken = guestTokens.getOrCreate(this), userId = currentUserId())

/**
 * Who this read is for, or `null` when the request carries no guest cookie and therefore no cart.
 */
private fun ApplicationCall.readingOwner(guestTokens: GuestTokens): CartOwner? =
    guestTokens.tryGet(this)?.let { token ->
        CartOwner(guestToken = token, userId = currentUserId())
    }

private fun ApplicationCall.currentUserId(): Long? =
    currentUserSession()?.userId?.toLongOrNull()?.takeIf { id -> id > 0 }

private suspend fun ApplicationCall.itemIdOrRespond(): Long? {
    val itemId = parameters["itemId"]?.toLongOrNull()
    if (itemId == null) {
        respond(HttpStatusCode.NotFound, ApiError("Cart item not found"))
    }
    return itemId
}

private suspend fun ApplicationCall.respondResult(result: OperationResult<CartView>) {
    when (result) {
        is OperationResult.Success -> respond(result.value)
        else -> respondFailure(result)
    }
}

/**
 * The promotion wire format this migration owes the promotion module: the shared [ApiError] with a
 * stable machine-readable `code`, and the status the record fixes for each reason.
 */
private suspend fun ApplicationCall.respondPromotion(result: CartPromotionResult) {
    when (result) {
        is CartPromotionResult.Applied -> respond(result.cart)
        CartPromotionResult.NoCart -> respond(HttpStatusCode.NotFound, ApiError("Cart not found"))
        CartPromotionResult.UnexpectedFailure ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
        is CartPromotionResult.Rejected -> {
            val (status, error) = result.reason.toApiError()
            respond(status, error)
        }
    }
}

private suspend fun ApplicationCall.respondFailure(result: OperationResult<*>) {
    when (result) {
        is OperationResult.Invalid ->
            respond(HttpStatusCode.BadRequest, ApiError("Validation failed", result.errors))
        OperationResult.NotFound -> respond(HttpStatusCode.NotFound, ApiError("Cart not found"))
        OperationResult.Conflict ->
            error("Cart operations do not report conflicts; the row lock removes them")
        OperationResult.UnexpectedFailure ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
        is OperationResult.Success -> error("A success result cannot be handled as a failure")
    }
}

/**
 * The normative `PromotionCodeResult` → HTTP table of the migration record: the status and the
 * stable `code` a frontend branches on. The message is for a human, the code is for the client.
 */
private fun PromotionCodeResult.toApiError(): Pair<HttpStatusCode, ApiError> =
    when (this) {
        PromotionCodeResult.InvalidCode ->
            HttpStatusCode.BadRequest to
                ApiError("Promotion code is invalid", code = "PROMOTION_INVALID_CODE")
        PromotionCodeResult.Inactive ->
            HttpStatusCode.BadRequest to
                ApiError("Promotion code is not active", code = "PROMOTION_INACTIVE")
        PromotionCodeResult.NotStarted ->
            HttpStatusCode.BadRequest to
                ApiError("Promotion code is not valid yet", code = "PROMOTION_NOT_STARTED")
        PromotionCodeResult.Expired ->
            HttpStatusCode.BadRequest to
                ApiError("Promotion code has expired", code = "PROMOTION_EXPIRED")
        PromotionCodeResult.LoginRequired ->
            HttpStatusCode.Forbidden to
                ApiError(
                    "Promotion code requires a signed-in customer",
                    code = "PROMOTION_LOGIN_REQUIRED",
                )
        PromotionCodeResult.TotalExhausted ->
            HttpStatusCode.Conflict to
                ApiError(
                    "Promotion code has reached its usage limit",
                    code = "PROMOTION_TOTAL_EXHAUSTED",
                )
        PromotionCodeResult.PerUserExhausted ->
            HttpStatusCode.Conflict to
                ApiError(
                    "Promotion code has reached your usage limit",
                    code = "PROMOTION_PER_USER_EXHAUSTED",
                )
        is PromotionCodeResult.Applicable -> error("An applicable promotion is not a rejection")
    }
