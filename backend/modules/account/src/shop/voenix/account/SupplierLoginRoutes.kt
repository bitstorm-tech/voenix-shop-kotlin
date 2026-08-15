package shop.voenix.account

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.installAdminRouteProtection
import shop.voenix.http.ApiError
import shop.voenix.http.ConflictHandling
import shop.voenix.http.InvalidHandling
import shop.voenix.http.OperationResultHttpMapping
import shop.voenix.http.longPathParameterOrRespond
import shop.voenix.http.respondFailure
import shop.voenix.http.respondResult
import shop.voenix.operation.OperationResult
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

/**
 * The administrator's management of supplier logins. Everything the invited person does with the
 * mailed link happens on the anonymous `/api/auth` routes of [installAccountRoutes]; this node only
 * creates, lists, and revokes.
 *
 * The path is deliberately its own node instead of a child of `/api/admin/suppliers`: that subtree
 * belongs to the supplier module, and two modules installing a route protection on the same node
 * would merge into one tree with two plugins.
 */
internal fun Application.installSupplierLoginRoutes(logins: SupplierLoginOperations) {
    routing {
        authenticate(AuthRouting.PROVIDER) {
            route("/api/admin/supplier-logins") {
                installAdminRouteProtection()

                post {
                    val input = call.receive<CreateSupplierLoginInput>()
                    call.respondCreateSupplierLogin(logins.createSupplierLogin(input))
                }

                get {
                    val supplierId = call.supplierIdQueryOrRespond() ?: return@get
                    call.respondResult(
                        logins.listSupplierLogins(supplierId),
                        SUPPLIER_LOGIN_RESPONSES,
                    )
                }

                delete("{userId}") {
                    // A non-numeric id can never name a supplier login, so it gets the same answer
                    // as an id that names a customer: `404`, and nothing about which of the two it
                    // was.
                    val userId = call.userIdOrRespond() ?: return@delete
                    when (val result = logins.deleteSupplierLogin(userId)) {
                        is OperationResult.Success -> call.response.status(HttpStatusCode.NoContent)
                        else -> call.respondFailure(result, SUPPLIER_LOGIN_RESPONSES)
                    }
                }
            }
        }
    }
}

/**
 * The administrator's request for a new supplier login: which supplier, and which address gets the
 * invitation. There is no password field — the invited person sets one through the mailed link.
 *
 * Only the *shape* of [supplierId] is checked here. Whether that supplier exists is decided by the
 * foreign key of the insert, because a preliminary lookup could not answer it without a race.
 */
@Serializable
internal data class CreateSupplierLoginInput(
    val supplierId: Long? = null,
    val email: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        accountEmailErrors(email).takeIf { it.isNotEmpty() }?.let { put("email", it) }
        when {
            supplierId == null -> put("supplierId", listOf("Supplier id is required"))
            supplierId <= 0 -> put("supplierId", listOf("Supplier id must be positive"))
        }
    }
}

private val SUPPLIER_LOGIN_RESPONSES =
    OperationResultHttpMapping(
        notFound = ApiError("Supplier login not found"),
        conflict =
            ConflictHandling.Unreachable("Listing and deleting supplier logins cannot conflict"),
        invalid =
            InvalidHandling.Unreachable(
                "Listing and deleting supplier logins carry no input that could be invalid"
            ),
    )

/**
 * The creation answer stays hand-written: it maps four outcomes that no shared mapping describes —
 * `201` with a `Location`, the `409` for a taken address, the unknown supplier as a `supplierId`
 * field error, and the `502` whose message has to say that the login exists.
 */
private suspend fun ApplicationCall.respondCreateSupplierLogin(result: CreateSupplierLoginResult) {
    when (result) {
        is CreateSupplierLoginResult.Created -> {
            response.header(
                HttpHeaders.Location,
                "/api/admin/supplier-logins/${result.login.userId}",
            )
            respond(HttpStatusCode.Created, result.login)
        }
        CreateSupplierLoginResult.EmailTaken ->
            respond(HttpStatusCode.Conflict, ApiError("Email already exists"))
        // The foreign key, not a lookup, decided this — and the caller learns which field is at
        // fault without ever seeing a constraint name.
        CreateSupplierLoginResult.UnknownSupplier ->
            respond(
                HttpStatusCode.BadRequest,
                ApiError(
                    "Validation failed",
                    mapOf("supplierId" to listOf("Supplier does not exist")),
                ),
            )
        CreateSupplierLoginResult.InvitationDeliveryFailed ->
            respond(
                HttpStatusCode.BadGateway,
                ApiError(
                    "The supplier login was created, but its invitation email could not be " +
                        "delivered"
                ),
            )
        is CreateSupplierLoginResult.Invalid ->
            respond(
                HttpStatusCode.BadRequest,
                ApiError("Validation failed", result.errors),
            )
        CreateSupplierLoginResult.UnexpectedFailure ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
    }
}

/** The list is always scoped to one supplier; an absent or unusable id is a validation failure. */
private suspend fun ApplicationCall.supplierIdQueryOrRespond(): Long? {
    val supplierId = request.queryParameters["supplierId"]?.toLongOrNull()?.takeIf { it > 0 }
    if (supplierId == null) {
        respond(
            HttpStatusCode.BadRequest,
            ApiError(
                "Validation failed",
                mapOf("supplierId" to listOf("A positive supplier id is required")),
            ),
        )
    }
    return supplierId
}

private suspend fun ApplicationCall.userIdOrRespond(): Long? =
    longPathParameterOrRespond(
        "userId",
        HttpStatusCode.NotFound,
        ApiError("Supplier login not found"),
    )
