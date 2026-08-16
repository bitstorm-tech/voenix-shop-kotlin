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
import kotlinx.serialization.Serializable
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.currentUserSession
import shop.voenix.auth.installGuestCapableRouteProtection
import shop.voenix.http.ApiError
import shop.voenix.http.ConflictHandling
import shop.voenix.http.OperationResultHttpMapping
import shop.voenix.http.longPathParameterOrRespond
import shop.voenix.http.respondFailure
import shop.voenix.http.respondResult
import shop.voenix.image.receiveUploadedImage
import shop.voenix.operation.OperationResult
import shop.voenix.promotion.toApiError
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors
import shop.voenix.validation.ValidationErrorsBuilder
import shop.voenix.validation.buildValidationErrors

/**
 * The HTTP surface of the cart: eight routes that translate a request into one [CartOperations]
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
internal fun Application.installCartRoutes(
    carts: CartOperations,
    guestTokens: GuestTokens,
) {
    routing {
        route(BASE_PATH) {
            installGuestCapableRouteProtection()

            get {
                // Cart contents are per-visitor and change constantly; no cache may keep them.
                call.response.header(HttpHeaders.CacheControl, "no-store")
                when (val owner = call.readingOwner(guestTokens)) {
                    null -> call.respond(CartView.EMPTY)
                    else -> call.respondResult(carts.cart(owner), CART_RESPONSES)
                }
            }

            post("/images") {
                val owner = call.mutatingOwner(guestTokens)
                when (val result = carts.uploadPrintImage(owner, call.receiveUploadedImage())) {
                    is OperationResult.Success -> call.respond(HttpStatusCode.Created, result.value)
                    else -> call.respondFailure(result, CART_RESPONSES)
                }
            }

            route("/items") {
                post {
                    val owner = call.mutatingOwner(guestTokens)
                    val input = call.receive<AddCartItemInput>()
                    call.respondResult(carts.addItem(owner, input), CART_RESPONSES)
                }

                patch("/{itemId}") {
                    val owner = call.mutatingOwner(guestTokens)
                    val itemId = call.itemIdOrRespond() ?: return@patch
                    val input = call.receive<CartQuantityInput>()
                    call.respondResult(
                        carts.updateQuantity(owner, itemId, input),
                        CART_RESPONSES,
                    )
                }

                delete("/{itemId}") {
                    val owner = call.mutatingOwner(guestTokens)
                    val itemId = call.itemIdOrRespond() ?: return@delete
                    call.respondResult(carts.removeItem(owner, itemId), CART_RESPONSES)
                }
            }

            // Reordering is a cart route because what it produces is a cart line, even though
            // what it starts from belongs to an order.
            post("/order-items/{orderItemId}") {
                val owner = call.mutatingOwner(guestTokens)
                val orderItemId = call.orderItemIdOrRespond() ?: return@post
                call.respondReorder(carts.reorder(owner, orderItemId))
            }

            route("/promotion") {
                post {
                    val owner = call.mutatingOwner(guestTokens)
                    val input = call.receive<PromotionCodeInput>()
                    call.respondPromotion(carts.applyPromotion(owner, input))
                }

                delete {
                    val owner = call.mutatingOwner(guestTokens)
                    call.respondResult(carts.removePromotion(owner), CART_RESPONSES)
                }
            }
        }
    }
}

private const val BASE_PATH = "/api/cart"

/**
 * What a customer sends to put one line into their cart.
 *
 * Every field is nullable although three of them are required: a missing `articleId` has to reach
 * [validate] and become a field error instead of failing deserialization with a serializer message
 * no client can act on.
 *
 * The image is referenced by id, never uploaded here. `POST /api/cart/images` stores the file
 * first, so a rejected add never leaves a file behind and this request stays plain JSON.
 */
@Serializable
internal data class AddCartItemInput(
    val articleId: Long? = null,
    val variantId: Long? = null,
    val quantity: Int? = null,
    val promptId: Long? = null,
    val imageId: Long? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildValidationErrors {
        validateIdentifier("articleId", "ArticleId", articleId, required = true)
        validateIdentifier("variantId", "VariantId", variantId, required = true)
        validateIdentifier("promptId", "PromptId", promptId, required = false)
        validateIdentifier("imageId", "ImageId", imageId, required = false)
        when {
            quantity == null -> add("quantity", "Quantity is required")
            quantity !in 1..MAXIMUM_LINE_QUANTITY ->
                add("quantity", "Quantity must be between 1 and $MAXIMUM_LINE_QUANTITY")
        }
    }

    private fun ValidationErrorsBuilder.validateIdentifier(
        field: String,
        displayName: String,
        value: Long?,
        required: Boolean,
    ) {
        when {
            value == null -> if (required) add(field, "$displayName is required")
            value <= 0 -> add(field, "$displayName must be positive")
        }
    }
}

/**
 * The body of `PATCH /api/cart/items/{itemId}`: the new quantity of one line.
 *
 * It is its own type rather than a reused [AddCartItemInput] with everything but the quantity left
 * out. The two contracts differ in what they *require*, and a shared type would have to accept a
 * quantity update that also silently carries an article id.
 */
@Serializable
internal data class CartQuantityInput(val quantity: Int? = null) : Validatable {
    override fun validate(): ValidationErrors = buildValidationErrors {
        when {
            quantity == null -> add("quantity", "Quantity is required")
            quantity !in 1..MAXIMUM_LINE_QUANTITY ->
                add("quantity", "Quantity must be between 1 and $MAXIMUM_LINE_QUANTITY")
        }
    }
}

/**
 * The body of `POST /api/cart/promotion`: the coupon code a customer typed.
 *
 * The length limit mirrors the `coupon_code` column, so a code that could not possibly exist is
 * refused before the promotion module is asked about it.
 */
@Serializable
internal data class PromotionCodeInput(val promotionCode: String? = null) : Validatable {
    override fun validate(): ValidationErrors = buildValidationErrors {
        when {
            promotionCode.isNullOrBlank() -> add("promotionCode", "PromotionCode is required")
            promotionCode.trim().length > MAXIMUM_PROMOTION_CODE_LENGTH ->
                add(
                    "promotionCode",
                    "PromotionCode must be at most $MAXIMUM_PROMOTION_CODE_LENGTH characters",
                )
        }
    }

    internal companion object {
        const val MAXIMUM_PROMOTION_CODE_LENGTH: Int = 64
    }
}

/**
 * The answer of the print-image pre-upload: the id an add-to-cart request names as its `imageId`.
 *
 * The file name never leaves the module. A client that learned it could ask the file system for
 * somebody else's image; an id, on the other hand, is checked against the stored owner on every
 * use.
 */
@Serializable internal data class PrintImageId(val id: Long)

/**
 * Who this mutating request is for. A visitor without a guest cookie receives one here — the first
 * mutation is what makes an anonymous browser addressable, and nothing before it needs to.
 */
private fun ApplicationCall.mutatingOwner(guestTokens: GuestTokens): CartOwner =
    CartOwner(guestToken = guestTokens.getOrCreate(this), userId = currentUserId())

/**
 * Who this read is for, or `null` when the request carries neither a user session nor a guest
 * cookie and therefore cannot mean any cart.
 *
 * A signed-in customer is an owner even without a cookie: their cart is found by their user id
 * (issue #77), so a browser that lost its guest cookie still sees the cart it belongs to.
 */
private fun ApplicationCall.readingOwner(guestTokens: GuestTokens): CartOwner? {
    val userId = currentUserId()
    val guestToken = guestTokens.tryGet(this)
    if (userId == null && guestToken == null) return null
    return CartOwner(guestToken = guestToken, userId = userId)
}

private fun ApplicationCall.currentUserId(): Long? =
    currentUserSession()?.userId?.toLongOrNull()?.takeIf { id -> id > 0 }

private suspend fun ApplicationCall.itemIdOrRespond(): Long? =
    longPathParameterOrRespond("itemId", HttpStatusCode.NotFound, ApiError("Cart item not found"))

private suspend fun ApplicationCall.orderItemIdOrRespond(): Long? =
    longPathParameterOrRespond(
        "orderItemId",
        HttpStatusCode.NotFound,
        ApiError("Order item not found"),
    )

/**
 * The two answers a reorder has that no other cart route has: a miss names the *order* item, and
 * the one conflict a cart reports carries the code a frontend branches on to offer a new upload.
 */
private suspend fun ApplicationCall.respondReorder(result: OperationResult<CartView>) {
    when (result) {
        is OperationResult.Success -> respond(result.value)
        OperationResult.NotFound ->
            respond(HttpStatusCode.NotFound, ApiError("Order item not found"))
        OperationResult.Conflict ->
            respond(
                HttpStatusCode.Conflict,
                ApiError(
                    "The image of this order item is no longer available",
                    code = "ORDER_IMAGE_UNAVAILABLE",
                ),
            )
        else -> respondFailure(result, CART_RESPONSES)
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

private val CART_RESPONSES =
    OperationResultHttpMapping(
        notFound = ApiError("Cart not found"),
        conflict =
            ConflictHandling.Unreachable(
                "Only a reorder reports a conflict, and it answers that one itself"
            ),
    )
