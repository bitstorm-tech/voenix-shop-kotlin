package shop.voenix.account

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
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

private const val CONFIRMATION_LINK_MESSAGE = "Invalid or expired confirmation link"

private suspend fun ApplicationCall.respondRegister(result: RegisterResult) {
    when (result) {
        RegisterResult.Registered -> response.status(HttpStatusCode.NoContent)
        RegisterResult.EmailTaken ->
            respond(HttpStatusCode.Conflict, ApiError("Email already exists"))
        RegisterResult.DeliveryFailed ->
            respond(
                HttpStatusCode.BadGateway,
                ApiError("Confirmation email could not be delivered"),
            )
        is RegisterResult.Invalid ->
            respond(
                HttpStatusCode.BadRequest,
                ApiError("Validation failed", result.errors),
            )
        RegisterResult.UnexpectedFailure ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
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
        is LoginResult.Invalid ->
            respond(
                HttpStatusCode.BadRequest,
                ApiError("Validation failed", result.errors),
            )
        LoginResult.UnexpectedFailure ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
    }
}

private suspend fun ApplicationCall.respondChangeEmail(result: ChangeEmailResult) {
    when (result) {
        ChangeEmailResult.ConfirmationSent -> response.status(HttpStatusCode.NoContent)
        ChangeEmailResult.WrongPassword ->
            respond(HttpStatusCode.Unauthorized, ApiError("Invalid password"))
        ChangeEmailResult.EmailTaken ->
            respond(HttpStatusCode.Conflict, ApiError("Email already exists"))
        ChangeEmailResult.DeliveryFailed ->
            respond(
                HttpStatusCode.BadGateway,
                ApiError("Confirmation email could not be delivered"),
            )
        ChangeEmailResult.NotFound ->
            respond(HttpStatusCode.Unauthorized, ApiError("User not found"))
        is ChangeEmailResult.Invalid ->
            respond(
                HttpStatusCode.BadRequest,
                ApiError("Validation failed", result.errors),
            )
        ChangeEmailResult.UnexpectedFailure ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
    }
}

private suspend fun ApplicationCall.respondChangePassword(result: ChangePasswordResult) {
    when (result) {
        ChangePasswordResult.Changed -> response.status(HttpStatusCode.NoContent)
        ChangePasswordResult.WrongPassword ->
            respond(HttpStatusCode.Unauthorized, ApiError("Invalid password"))
        ChangePasswordResult.NotFound ->
            respond(HttpStatusCode.Unauthorized, ApiError("User not found"))
        is ChangePasswordResult.Invalid ->
            respond(
                HttpStatusCode.BadRequest,
                ApiError("Validation failed", result.errors),
            )
        ChangePasswordResult.UnexpectedFailure ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
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
        is OperationResult.Invalid ->
            respond(
                HttpStatusCode.BadRequest,
                ApiError("Validation failed", result.errors),
            )
        OperationResult.Conflict ->
            respond(HttpStatusCode.Conflict, ApiError("Email already exists"))
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
                respond(HttpStatusCode.NotFound, ApiError("Not found"))
            }
        OperationResult.UnexpectedFailure ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
    }
}

private suspend fun ApplicationCall.respondProfileResult(
    result: OperationResult<AccountProfileView>
) {
    when (result) {
        is OperationResult.Success -> respond(result.value)
        OperationResult.NotFound -> respond(HttpStatusCode.Unauthorized, ApiError("User not found"))
        is OperationResult.Invalid ->
            respond(
                HttpStatusCode.BadRequest,
                ApiError("Validation failed", result.errors),
            )
        OperationResult.Conflict -> error("Profile operations cannot produce a conflict")
        OperationResult.UnexpectedFailure ->
            respond(HttpStatusCode.InternalServerError, ApiError("Internal server error"))
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
