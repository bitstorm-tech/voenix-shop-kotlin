// Detekt counts the private response helpers below as functions of this file. They were members of
// a route object before the routes became a top-level installer, and moving them into a second file
// would only separate an answer from the route that gives it.
@file:Suppress("TooManyFunctions")

package shop.voenix.order

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.currentUserSession
import shop.voenix.auth.installAdminRouteProtection
import shop.voenix.auth.installGuestCapableRouteProtection
import shop.voenix.http.ApiError
import shop.voenix.http.ConflictHandling
import shop.voenix.http.InvalidHandling
import shop.voenix.http.OperationResultHttpMapping
import shop.voenix.http.longPathParameterOrRespond
import shop.voenix.http.respondResult
import shop.voenix.production.ProductionPdfDocument
import shop.voenix.production.ProductionPdfError
import shop.voenix.production.ProductionPdfGenerator
import shop.voenix.production.ProductionPdfResult

/**
 * The HTTP surface of the order module: what a customer may read about their own orders, what a
 * mail link may read, and what an admin may download for production.
 *
 * The three subtrees are deliberately disjoint route nodes. Ktor merges paths into one tree, so a
 * route-scoped protection plugin reaches every descendant of the node it is installed on — hanging
 * the admin downloads under `/api/orders` would have put a guest-capable plugin above them.
 * Customer reads therefore own `/api/orders`, the token lookup owns `/api/order-lookup`, and the
 * production PDFs own `/api/admin/orders`, and no request can ever reach one subtree with another
 * one's authorization.
 *
 * Two rules are the same on every route here. Nothing is cacheable: an order answer is personal,
 * and a shared cache holding it is the whole IDOR the legacy PDF endpoint had. And an id a caller
 * may not read is answered exactly like an id that never existed — the routes never distinguish
 * "forbidden" from "unknown", because that distinction is itself the information an attacker probes
 * for.
 *
 * Reading never mints a guest cookie. [GuestTokens.tryGet] reads the cookie that is there and
 * creates nothing, so looking at an order history does not turn an anonymous visitor into a tracked
 * one; only a mutation does, and this wave has none.
 */
internal fun Application.installOrderRoutes(
    orders: OrderOperations,
    productionPdfs: ProductionPdfGenerator,
    guestTokens: GuestTokens,
) {
    routing {
        installCustomerRoutes(orders, guestTokens)
        installLookupRoutes(orders)
        installAdminRoutes(productionPdfs)
    }
}

/**
 * The customer's own orders: a history and one order, both guest-capable.
 *
 * The identity is assembled here rather than in the operations, because who the caller is, is an
 * HTTP question: a signed-in customer is their session, a visitor is their guest cookie, and a
 * request with neither carries no identity at all and reads nothing (deviation D4).
 */
private fun Route.installCustomerRoutes(
    orders: OrderOperations,
    guestTokens: GuestTokens,
) {
    route(CUSTOMER_PATH) {
        installGuestCapableRouteProtection()

        get {
            call.noStore()
            val history = orders.history(call.userId(), guestTokens.tryGet(call))
            call.respondResult(history, ORDER_RESPONSES)
        }

        get("/{orderId}") {
            call.noStore()
            val orderId = call.idOrRespond("orderId") ?: return@get
            val order = orders.order(orderId, call.userId(), guestTokens.tryGet(call))
            call.respondResult(order, ORDER_RESPONSES)
        }
    }
}

/**
 * The permanent link from the confirmation mail: one order, read by its access token (issue #110).
 *
 * It is its own top-level node and not a child of `/api/orders`, because its security model is the
 * opposite one. There is no guest-capable protection here, no session, no cookie, and no CSRF: the
 * request carries a 256-bit bearer credential in its path and nothing else, and a read mints no
 * cookie either — following a mail link must not turn the reader into a tracked visitor. Hanging it
 * under the customer node would have put that node's plugin above it.
 *
 * **No rate limit, on purpose.** This module's limiter is a cost gate — it exists where a request
 * makes the shop spend money or CPU on an external system. This route spends nothing: it is one
 * indexed read, and the payment status comes from `stored`, so no provider is ever called. What a
 * limiter would otherwise defend against is enumeration, and 256 bits of `SecureRandom` already
 * make that pointless — a guessing attacker is not slowed down by a limiter, they are stopped by
 * the key space. (Decision of issue #110; revisit only if this route ever gains a cost.)
 *
 * Every miss is the same `404` with the same body: a token that is not shaped like one, a token
 * that names no order, and a request without a token at all. Never a `400`, never a `403` — the
 * difference between "malformed" and "unknown" is the only feedback a probe could hope for.
 */
private fun Route.installLookupRoutes(orders: OrderOperations) {
    route(LOOKUP_PATH) {
        // A request that names no token at all lands here rather than on Ktor's bare 404, so the
        // three misses really are one answer on the wire.
        get {
            call.noStore()
            call.respond(HttpStatusCode.NotFound, ApiError(ORDER_NOT_FOUND))
        }

        get("/{token}") {
            call.noStore()
            val order = orders.orderByToken(call.parameters["token"].orEmpty())
            call.respondResult(order, ORDER_RESPONSES)
        }
    }
}

/**
 * The production documents of one order, for admins only (deviation D1).
 *
 * The legacy endpoint was anonymous, which made every order's production data — addresses included
 * — readable by order id. The protection lives on the `/api/admin/orders` node so that every admin
 * order route added later inherits it instead of having to remember it.
 *
 * Both routes generate on demand and neither stores anything: the list exists to name the suppliers
 * a download exists for, and the fetch picks its document out of the same generated set. A supplier
 * the order has no document for is a miss like any other.
 */
private fun Route.installAdminRoutes(productionPdfs: ProductionPdfGenerator) {
    authenticate(AuthRouting.PROVIDER) {
        route(ADMIN_PATH) {
            installAdminRouteProtection()

            route("/{orderId}/production-pdfs") {
                get {
                    call.noStore()
                    val orderId = call.idOrRespond("orderId") ?: return@get
                    when (val result = productionPdfs.generate(orderId)) {
                        is ProductionPdfResult.Generated ->
                            call.respond(result.documents.map(ProductionPdfDocument::toInfo))
                        else -> call.respondPdfFailure(result)
                    }
                }

                get("/{supplierId}") {
                    call.noStore()
                    val orderId = call.idOrRespond("orderId") ?: return@get
                    val supplierId = call.idOrRespond("supplierId") ?: return@get
                    when (val result = productionPdfs.generate(orderId)) {
                        is ProductionPdfResult.Generated ->
                            call.respondDocument(
                                result.documents.firstOrNull { document ->
                                    document.supplierId == supplierId
                                }
                            )
                        else -> call.respondPdfFailure(result)
                    }
                }
            }
        }
    }
}

private const val CUSTOMER_PATH = "/api/orders"
private const val LOOKUP_PATH = "/api/order-lookup"
private const val ADMIN_PATH = "/api/admin/orders"
private const val ORDER_NOT_FOUND = "Order not found"

/** No order answer may be cached: it is personal, and none of it is worth a stale copy. */
private fun ApplicationCall.noStore() {
    response.header(HttpHeaders.CacheControl, "no-store")
}

private fun ApplicationCall.userId(): Long? =
    currentUserSession()?.userId?.toLongOrNull()?.takeIf { id -> id > 0 }

/**
 * An id that is not a number never named an order, so it is answered like an unknown one instead of
 * as a bad request. Anything else would tell a caller that the id space is numeric and where their
 * probe went wrong — which is also why the answer does not say *which* of the two ids was unusable.
 */
private suspend fun ApplicationCall.idOrRespond(name: String): Long? =
    longPathParameterOrRespond(name, HttpStatusCode.NotFound, ApiError(ORDER_NOT_FOUND))

private val ORDER_RESPONSES =
    OperationResultHttpMapping(
        notFound = ApiError(ORDER_NOT_FOUND),
        conflict = ConflictHandling.Unreachable("Order reads never conflict"),
        invalid = InvalidHandling.Unreachable("Order reads carry no input that could be invalid"),
    )

/**
 * Streams one production document as a download.
 *
 * The file name comes from the document itself, which is the producer-facing `ORD-{orderId}.pdf`
 * that the SFTP delivery uses as well — one name for the same artifact, whether it is fetched here
 * or delivered to the supplier.
 */
private suspend fun ApplicationCall.respondDocument(document: ProductionPdfDocument?) {
    if (document == null) {
        respond(HttpStatusCode.NotFound, ApiError(ORDER_NOT_FOUND))
        return
    }
    response.header(
        HttpHeaders.ContentDisposition,
        "attachment; filename=\"${document.fileName}\"",
    )
    respondBytes(document.bytes, ContentType.parse(document.mediaType))
}

private suspend fun ApplicationCall.respondPdfFailure(result: ProductionPdfResult) {
    when (result) {
        ProductionPdfResult.OrderNotFound ->
            respond(HttpStatusCode.NotFound, ApiError(ORDER_NOT_FOUND))
        is ProductionPdfResult.GenerationFailed -> {
            val (status, error) = result.error.toApiError()
            respond(status, error)
        }
        is ProductionPdfResult.Generated -> error("A generated result is not a failure")
    }
}

/**
 * The `ProductionPdfError` → HTTP table.
 *
 * Three of the four reasons are a statement about the order's own production data: something an
 * admin can repair, and a document that will exist once they have — that is a `409`, not a server
 * error. A renderer failure is nobody's data problem and stays a `500` whose details are in the log
 * and never in the body. Every message is written for a human; the stable `code` is what a client
 * branches on, and neither ever names a file, a path, or a renderer.
 */
private fun ProductionPdfError.toApiError(): Pair<HttpStatusCode, ApiError> =
    when (this) {
        ProductionPdfError.MISSING_IMAGE ->
            HttpStatusCode.Conflict to
                ApiError(
                    "An ordered item has no usable production image",
                    code = "PRODUCTION_PDF_MISSING_IMAGE",
                )
        ProductionPdfError.UNREADABLE_IMAGE ->
            HttpStatusCode.Conflict to
                ApiError(
                    "An ordered item's production image cannot be read",
                    code = "PRODUCTION_PDF_UNREADABLE_IMAGE",
                )
        ProductionPdfError.INVALID_SOURCE ->
            HttpStatusCode.Conflict to
                ApiError(
                    "The order carries production data no document can be laid out from",
                    code = "PRODUCTION_PDF_INVALID_SOURCE",
                )
        ProductionPdfError.RENDER_FAILURE ->
            HttpStatusCode.InternalServerError to
                ApiError(
                    "The production document could not be rendered",
                    code = "PRODUCTION_PDF_RENDER_FAILURE",
                )
    }

/**
 * One downloadable production PDF of an order, as the admin list route announces it.
 *
 * An order yields one document per involved supplier (deviation D2), so the list is what tells an
 * admin which downloads exist at all. It carries no bytes and no digest on purpose: the list
 * answers "what can I fetch", the fetch route answers "give it to me", and generating a document
 * twice is cheaper than sending megabytes nobody asked for.
 *
 * [fileName] repeats across the suppliers of one order — it is the producer-facing `ORD-{id}.pdf`,
 * unique per destination rather than per order.
 */
@Serializable
internal data class ProductionPdfInfo(
    val supplierId: Long,
    val fileName: String,
)

private fun ProductionPdfDocument.toInfo(): ProductionPdfInfo =
    ProductionPdfInfo(supplierId = supplierId, fileName = fileName)
