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
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import shop.voenix.account.api.ChangeEmailInput
import shop.voenix.account.api.ChangeEmailResult
import shop.voenix.account.api.ChangePasswordInput
import shop.voenix.account.api.ChangePasswordResult
import shop.voenix.account.api.LoginResult
import shop.voenix.account.api.ProfileInput
import shop.voenix.account.api.RegisterResult
import shop.voenix.auth.AuthRouting
import shop.voenix.auth.GuestTokens
import shop.voenix.auth.UserSession
import shop.voenix.auth.currentUserSession
import shop.voenix.auth.installAuthenticatedRouteProtection
import shop.voenix.http.ApiError
import shop.voenix.operation.OperationResult

internal object AccountRoutes {
    fun install(
        application: Application,
        accounts: AccountOperations,
        guestTokens: GuestTokens,
        guestDataClaims: GuestDataClaims,
    ) {
        application.routing {
            route("/api/auth") { installAnonymousRoutes(accounts, guestTokens, guestDataClaims) }
            authenticate(AuthRouting.PROVIDER) {
                route("/api/auth") { installAuthenticatedRoutes(accounts) }
            }
        }
    }

    private fun Route.installAnonymousRoutes(
        accounts: AccountOperations,
        guestTokens: GuestTokens,
        guestDataClaims: GuestDataClaims,
    ) {
        post("register") {
            val result = accounts.register(call.receive())
            if (result is RegisterResult.Registered) {
                // Without a confirmed address a registration proves nothing about the e-mail it
                // was made with, so it claims by guest token only. It also does not rotate the
                // token: a registration starts no user session — the visitor keeps browsing
                // anonymously until the first login — so there is no session for a rotation to
                // belong to.
                call.claimGuestData(guestTokens, guestDataClaims, result.userId, email = null)
            }
            call.respondRegister(result)
        }
        post("login") {
            val result = accounts.login(call.receive())
            if (result is LoginResult.SignedIn) {
                call.claimGuestData(guestTokens, guestDataClaims, result.userId, result.email)
                // Strictly after the claim: the claimed rows now belong to the customer — the cart
                // is found by their user id from here on — so the old token is worth nothing to
                // them, while a rotation before the claim would throw away the very handle the
                // claim needs to find them.
                guestTokens.rotate(call)
            }
            call.respondLogin(result)
        }
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
     * Hands the guest data of this request to [userId] — best effort by design.
     *
     * The guest cookie is one handle on the visitor, [email] — set on login only — is the other, so
     * a missing cookie is no longer a reason to skip the claim: rows can be waiting under the
     * address alone. Only when neither handle is present is there nothing to claim. A failing claim
     * is logged and swallowed: the customer is signed in either way, and the next login claims
     * again. Only [CancellationException] passes through, because a cancelled request must not be
     * reported as a claim failure.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun ApplicationCall.claimGuestData(
        guestTokens: GuestTokens,
        guestDataClaims: GuestDataClaims,
        userId: Long,
        email: String?,
    ) {
        val guestToken = guestTokens.tryGet(this)
        if (guestToken == null && email == null) return
        try {
            guestDataClaims.claim(userId, guestToken, email)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.error("Guest data claim failed for user $userId", exception)
        }
    }

    private const val CONFIRMATION_LINK_MESSAGE = "Invalid or expired confirmation link"

    private val logger: Logger = LoggerFactory.getLogger(AccountRoutes::class.java)
}

private suspend fun ApplicationCall.respondRegister(result: RegisterResult) {
    when (result) {
        is RegisterResult.Registered -> response.status(HttpStatusCode.NoContent)
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

private suspend fun ApplicationCall.respondUnitResult(
    result: OperationResult<Unit>,
    invalidLinkMessage: String?,
) {
    when (result) {
        is OperationResult.Success -> response.status(HttpStatusCode.NoContent)
        is OperationResult.Invalid -> respondValidation(result.errors)
        OperationResult.Conflict -> respondError(HttpStatusCode.Conflict, "Email already exists")
        // An invalid or expired link is a NotFound outcome, but the contract answers 400 so the
        // cause stays indistinguishable from other bad requests.
        OperationResult.NotFound ->
            if (invalidLinkMessage != null) {
                respond(HttpStatusCode.BadRequest, ApiError(invalidLinkMessage))
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
