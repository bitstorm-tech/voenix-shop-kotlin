// Detekt counts the private response helpers below as functions of this file. They were members of
// a route object before the routes became a top-level installer, and moving them into a second file
// would only separate an answer from the route that gives it.
@file:Suppress("TooManyFunctions")

package shop.voenix.account

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.serialization.Serializable
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.UserSession
import shop.voenix.auth.currentUserSession
import shop.voenix.auth.installAdminRouteProtection
import shop.voenix.auth.installAuthenticatedRouteProtection
import shop.voenix.http.ApiError
import shop.voenix.operation.OperationResult
import shop.voenix.validation.Validatable
import shop.voenix.validation.ValidationErrors

internal fun Application.installAccountRoutes(accounts: AccountOperations) {
    routing {
        route("/api/auth") { installAnonymousRoutes(accounts) }
        authenticate(AuthRouting.PROVIDER) {
            route("/api/auth") { installAuthenticatedRoutes(accounts) }
            // Deliberately its own node instead of a child of `/api/admin/suppliers`: that
            // subtree belongs to the supplier module, and two modules installing a route
            // protection on the same node would merge into one tree with two plugins.
            route("/api/admin/supplier-logins") { installSupplierLoginRoutes(accounts) }
        }
    }
}

private fun Route.installAnonymousRoutes(accounts: AccountOperations) {
    post("register") { call.respondRegister(accounts.register(call.receive())) }
    post("login") { call.respondLogin(accounts.login(call.receive())) }
    post("confirm-email") {
        call.respondUnitResult(
            accounts.confirmEmail(call.receive()),
            invalidLinkMessage = CONFIRMATION_LINK_MESSAGE,
        )
    }
    post("resend-confirmation") {
        call.respondUnitResult(
            accounts.resendConfirmation(call.receive()),
            invalidLinkMessage = null,
        )
    }
    post("forgot-password") {
        call.respondUnitResult(
            accounts.forgotPassword(call.receive()),
            invalidLinkMessage = null,
        )
    }
    post("reset-password") {
        call.respondUnitResult(
            accounts.resetPassword(call.receive()),
            invalidLinkMessage = "Invalid or expired password reset link",
        )
    }
    post("confirm-change-email") {
        call.respondUnitResult(
            accounts.confirmChangeEmail(call.receive()),
            invalidLinkMessage = CONFIRMATION_LINK_MESSAGE,
        )
    }
}

private fun Route.installAuthenticatedRoutes(accounts: AccountOperations) {
    installAuthenticatedRouteProtection()

    get("me") {
        val userId = call.sessionUserIdOrRespond() ?: return@get
        call.respondProfileResult(accounts.profile(userId))
    }

    put("profile") {
        val userId = call.sessionUserIdOrRespond() ?: return@put
        val input = call.receive<ProfileInput>()
        call.respondProfileResult(accounts.updateProfile(userId, input))
    }

    post("change-email") {
        val userId = call.sessionUserIdOrRespond() ?: return@post
        val input = call.receive<ChangeEmailInput>()
        call.respondChangeEmail(accounts.changeEmail(userId, input))
    }

    post("change-password") {
        val userId = call.sessionUserIdOrRespond() ?: return@post
        val input = call.receive<ChangePasswordInput>()
        call.respondChangePassword(accounts.changePassword(userId, input))
    }

    post("logout") {
        call.sessions.clear<UserSession>()
        call.response.status(HttpStatusCode.NoContent)
    }
}

/**
 * The administrator's management of supplier logins. Everything the invited person does with the
 * mailed link happens on the anonymous `/api/auth` routes above; this node only creates, lists, and
 * revokes.
 */
private fun Route.installSupplierLoginRoutes(accounts: AccountOperations) {
    installAdminRouteProtection()

    post {
        val input = call.receive<CreateSupplierLoginInput>()
        call.respondCreateSupplierLogin(accounts.createSupplierLogin(input))
    }

    get {
        val supplierId = call.supplierIdQueryOrRespond() ?: return@get
        when (val result = accounts.listSupplierLogins(supplierId)) {
            is OperationResult.Success -> call.respond(result.value)
            else ->
                call.respondError(
                    HttpStatusCode.InternalServerError,
                    "Internal server error",
                )
        }
    }

    delete("{userId}") {
        // A non-numeric id can never name a supplier login, so it gets the same answer as an
        // id that names a customer: `404`, and nothing about which of the two it was.
        val userId = call.parameters["userId"]?.toLongOrNull()
        val deleted = userId?.let { accounts.deleteSupplierLogin(it) }
        when (deleted) {
            is OperationResult.Success -> call.response.status(HttpStatusCode.NoContent)
            OperationResult.UnexpectedFailure ->
                call.respondError(
                    HttpStatusCode.InternalServerError,
                    "Internal server error",
                )
            else -> call.respondError(HttpStatusCode.NotFound, "Supplier login not found")
        }
    }
}

@Serializable
internal data class RegisterInput(
    val email: String? = null,
    val password: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        accountEmailErrors(email).takeIf { it.isNotEmpty() }?.let { put("email", it) }
        accountPasswordErrors(password).takeIf { it.isNotEmpty() }?.let { put("password", it) }
    }
}

@Serializable
internal data class ConfirmEmailInput(
    val userId: Long? = null,
    val token: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        if (userId == null) {
            put("userId", listOf("User id is required"))
        }
        if (token.isNullOrBlank()) {
            put("token", listOf("Token is required"))
        }
    }
}

/**
 * Login deliberately does not shape-validate the password: an existing password predating the
 * minimum-length rule must still be able to sign in, and login must not leak which rules current
 * passwords follow.
 */
@Serializable
internal data class LoginInput(
    val email: String? = null,
    val password: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        accountEmailErrors(email).takeIf { it.isNotEmpty() }?.let { put("email", it) }
        if (password.isNullOrEmpty()) {
            put("password", listOf("Password is required"))
        }
    }
}

/** Shared by resend-confirmation and forgot-password: both carry only an e-mail address. */
@Serializable
internal data class AccountEmailInput(val email: String? = null) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        accountEmailErrors(email).takeIf { it.isNotEmpty() }?.let { put("email", it) }
    }
}

@Serializable
internal data class ResetPasswordInput(
    val email: String? = null,
    val token: String? = null,
    val newPassword: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        accountEmailErrors(email).takeIf { it.isNotEmpty() }?.let { put("email", it) }
        if (token.isNullOrBlank()) {
            put("token", listOf("Token is required"))
        }
        accountPasswordErrors(newPassword)
            .takeIf { it.isNotEmpty() }
            ?.let { put("newPassword", it) }
    }
}

@Serializable
internal data class ChangeEmailInput(
    val newEmail: String? = null,
    val currentPassword: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        accountEmailErrors(newEmail).takeIf { it.isNotEmpty() }?.let { put("newEmail", it) }
        if (currentPassword.isNullOrEmpty()) {
            put("currentPassword", listOf("Current password is required"))
        }
    }
}

@Serializable
internal data class ConfirmChangeEmailInput(
    val userId: Long? = null,
    val newEmail: String? = null,
    val token: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        if (userId == null) {
            put("userId", listOf("User id is required"))
        }
        accountEmailErrors(newEmail).takeIf { it.isNotEmpty() }?.let { put("newEmail", it) }
        if (token.isNullOrBlank()) {
            put("token", listOf("Token is required"))
        }
    }
}

@Serializable
internal data class ChangePasswordInput(
    val currentPassword: String? = null,
    val newPassword: String? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        if (currentPassword.isNullOrEmpty()) {
            put("currentPassword", listOf("Current password is required"))
        }
        accountPasswordErrors(newPassword)
            .takeIf { it.isNotEmpty() }
            ?.let { put("newPassword", it) }
    }
}

/**
 * `PUT profile` replaces the whole profile: every shipping field takes the sent value, and when
 * [hasSeparateBillingAddress] is false the stored billing address is cleared.
 */
@Serializable
internal data class ProfileInput(
    val shippingAddress: Address? = null,
    val hasSeparateBillingAddress: Boolean = false,
    val billingAddress: Address? = null,
) : Validatable {
    override fun validate(): ValidationErrors = buildMap {
        if (shippingAddress == null) {
            put("shippingAddress", listOf("Shipping address is required"))
        } else {
            putAll(shippingAddress.validate("shippingAddress"))
        }
        billingAddress?.let { putAll(it.validate("billingAddress")) }
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

private const val CONFIRMATION_LINK_MESSAGE = "Invalid or expired confirmation link"

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
            respondError(HttpStatusCode.Conflict, "Email already exists")
        // The foreign key, not a lookup, decided this — and the caller learns which field is at
        // fault without ever seeing a constraint name.
        CreateSupplierLoginResult.UnknownSupplier ->
            respondValidation(mapOf("supplierId" to listOf("Supplier does not exist")))
        CreateSupplierLoginResult.InvitationDeliveryFailed ->
            respondError(
                HttpStatusCode.BadGateway,
                "The supplier login was created, but its invitation email could not be delivered",
            )
        is CreateSupplierLoginResult.Invalid -> respondValidation(result.errors)
        CreateSupplierLoginResult.UnexpectedFailure ->
            respondError(HttpStatusCode.InternalServerError, "Internal server error")
    }
}

/** The list is always scoped to one supplier; an absent or unusable id is a validation failure. */
private suspend fun ApplicationCall.supplierIdQueryOrRespond(): Long? {
    val supplierId = request.queryParameters["supplierId"]?.toLongOrNull()?.takeIf { it > 0 }
    if (supplierId == null) {
        respondValidation(mapOf("supplierId" to listOf("A positive supplier id is required")))
    }
    return supplierId
}

private suspend fun ApplicationCall.respondRegister(result: RegisterResult) {
    when (result) {
        RegisterResult.Registered -> response.status(HttpStatusCode.NoContent)
        RegisterResult.EmailTaken -> respondError(HttpStatusCode.Conflict, "Email already exists")
        RegisterResult.DeliveryFailed ->
            respondError(
                HttpStatusCode.BadGateway,
                "Confirmation email could not be delivered",
            )
        is RegisterResult.Invalid -> respondValidation(result.errors)
        RegisterResult.UnexpectedFailure ->
            respondError(HttpStatusCode.InternalServerError, "Internal server error")
    }
}

private suspend fun ApplicationCall.respondLogin(result: LoginResult) {
    when (result) {
        is LoginResult.SignedIn -> {
            sessions.set(UserSession(userId = result.userId.toString(), roles = result.roles))
            response.status(HttpStatusCode.NoContent)
        }
        LoginResult.InvalidCredentials ->
            respond(HttpStatusCode.Unauthorized, ApiError("Invalid email or password"))
        LoginResult.EmailNotConfirmed ->
            respond(HttpStatusCode.Forbidden, ApiError("Email is not confirmed"))
        LoginResult.LockedOut ->
            respond(HttpStatusCode.TooManyRequests, ApiError("Too many failed login attempts"))
        is LoginResult.Invalid -> respondValidation(result.errors)
        LoginResult.UnexpectedFailure ->
            respondError(HttpStatusCode.InternalServerError, "Internal server error")
    }
}

private suspend fun ApplicationCall.respondChangeEmail(result: ChangeEmailResult) {
    when (result) {
        ChangeEmailResult.ConfirmationSent -> response.status(HttpStatusCode.NoContent)
        ChangeEmailResult.WrongPassword ->
            respondError(HttpStatusCode.Unauthorized, "Invalid password")
        ChangeEmailResult.EmailTaken ->
            respondError(HttpStatusCode.Conflict, "Email already exists")
        ChangeEmailResult.DeliveryFailed ->
            respondError(
                HttpStatusCode.BadGateway,
                "Confirmation email could not be delivered",
            )
        ChangeEmailResult.NotFound -> respondError(HttpStatusCode.Unauthorized, "User not found")
        is ChangeEmailResult.Invalid -> respondValidation(result.errors)
        ChangeEmailResult.UnexpectedFailure ->
            respondError(HttpStatusCode.InternalServerError, "Internal server error")
    }
}

private suspend fun ApplicationCall.respondChangePassword(result: ChangePasswordResult) {
    when (result) {
        ChangePasswordResult.Changed -> response.status(HttpStatusCode.NoContent)
        ChangePasswordResult.WrongPassword ->
            respondError(HttpStatusCode.Unauthorized, "Invalid password")
        ChangePasswordResult.NotFound -> respondError(HttpStatusCode.Unauthorized, "User not found")
        is ChangePasswordResult.Invalid -> respondValidation(result.errors)
        ChangePasswordResult.UnexpectedFailure ->
            respondError(HttpStatusCode.InternalServerError, "Internal server error")
    }
}

/** Machine-readable code for an invalid or expired confirmation, reset, or change-email link. */
private const val INVALID_LINK_CODE = "INVALID_LINK"

private suspend fun ApplicationCall.respondUnitResult(
    result: OperationResult<Unit>,
    invalidLinkMessage: String?,
) {
    when (result) {
        is OperationResult.Success -> response.status(HttpStatusCode.NoContent)
        is OperationResult.Invalid -> respondValidation(result.errors)
        OperationResult.Conflict -> respondError(HttpStatusCode.Conflict, "Email already exists")
        // An invalid or expired link is a NotFound outcome, but the contract answers 400 so the
        // status alone does not tell a caller whether the link ever existed. The machine-readable
        // `INVALID_LINK` code lets the frontend show link-specific copy without parsing the
        // message; it still says nothing about *why* the link is invalid.
        OperationResult.NotFound ->
            if (invalidLinkMessage != null) {
                respond(
                    HttpStatusCode.BadRequest,
                    ApiError(invalidLinkMessage, code = INVALID_LINK_CODE),
                )
            } else {
                respondError(HttpStatusCode.NotFound, "Not found")
            }
        OperationResult.UnexpectedFailure ->
            respondError(HttpStatusCode.InternalServerError, "Internal server error")
    }
}

private suspend fun ApplicationCall.respondProfileResult(result: OperationResult<AccountProfile>) {
    when (result) {
        is OperationResult.Success -> respond(result.value)
        OperationResult.NotFound -> respondError(HttpStatusCode.Unauthorized, "User not found")
        is OperationResult.Invalid -> respondValidation(result.errors)
        OperationResult.Conflict -> error("Profile operations cannot produce a conflict")
        OperationResult.UnexpectedFailure ->
            respondError(HttpStatusCode.InternalServerError, "Internal server error")
    }
}

/**
 * Bridges the platform session's string user id back to the numeric database id. Inside the
 * protected subtree a session always exists; a non-numeric id only occurs for sessions that were
 * not created by the account login and cannot belong to a stored user.
 */
private suspend fun ApplicationCall.sessionUserIdOrRespond(): Long? {
    val userId = currentUserSession()?.userId?.toLongOrNull()?.takeIf { it > 0 }
    if (userId == null) {
        respond(HttpStatusCode.Unauthorized, ApiError("Authentication required"))
    }
    return userId
}

private suspend fun ApplicationCall.respondValidation(errors: Map<String, List<String>>) {
    respond(HttpStatusCode.BadRequest, ApiError("Validation failed", errors))
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) {
    respond(status, ApiError(message))
}
