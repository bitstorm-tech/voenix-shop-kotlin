package shop.voenix.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.principal
import io.ktor.server.auth.session
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerEncrypt
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.defaultSessionSerializer
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.Serializable
import shop.voenix.http.ApiError

/**
 * Installs everything the application needs to authenticate a request: the two encrypted session
 * cookies, the sliding renewal of a user session, the session authentication provider the protected
 * routes authenticate against, and the endpoint that mints a CSRF token.
 */
public fun Application.installAuthModule(settings: AuthSettings) {
    install(Sessions) {
        sameAsRequestCookie(
            name = AUTH_COOKIE,
            sessionType = UserSession::class,
            serializer = defaultSessionSerializer<UserSession>(),
            transformer = encryptedTransformer(settings, "auth"),
        )
        sameAsRequestCookie(
            name = CSRF_COOKIE,
            sessionType = CsrfSession::class,
            serializer = defaultSessionSerializer<CsrfSession>(),
            transformer = encryptedTransformer(settings, "csrf"),
        )
    }
    install(SlidingSessionRenewal)

    install(Authentication) {
        session<UserSession>(AuthRouting.PROVIDER) {
            validate { session ->
                val now = Instant.now().epochSecond
                session
                    .takeIf { it.expiresAtEpochSeconds > now }
                    ?.let {
                        UserPrincipal(
                            userId = it.userId,
                            roles = it.roles,
                            issuedAtEpochSeconds = it.issuedAtEpochSeconds,
                            expiresAtEpochSeconds = it.expiresAtEpochSeconds,
                        )
                    }
            }
            challenge {
                call.respondAuth(
                    HttpStatusCode.Unauthorized,
                    "Authentication required",
                )
            }
        }
    }

    routing {
        get("/api/antiforgery/token") {
            val token = newCsrfToken()
            call.sessions.set(
                CsrfSession(token = token, userId = call.currentUserSession()?.userId)
            )
            call.respond(AntiforgeryTokenResponse(token))
        }
    }
}

private fun encryptedTransformer(
    settings: AuthSettings,
    purpose: String,
): SessionTransportTransformerEncrypt =
    SessionCookieEncryption.transformer(settings.sessionSecret, purpose)

private fun newCsrfToken(): String {
    val bytes = ByteArray(32)
    secureRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private const val AUTH_COOKIE = "voenix.auth"
private const val CSRF_COOKIE = "XSRF-TOKEN"
private const val SESSION_DURATION_SECONDS = 24L * 60L * 60L

internal suspend fun requireAuthenticated(call: ApplicationCall): Boolean {
    if (call.principal<UserPrincipal>() != null) return true
    call.respondAuth(
        HttpStatusCode.Unauthorized,
        "Authentication required",
    )
    return false
}

internal suspend fun requireAdmin(call: ApplicationCall): Boolean {
    val principal = call.principal<UserPrincipal>()
    return when {
        principal == null -> {
            call.respondAuth(
                HttpStatusCode.Unauthorized,
                "Authentication required",
            )
            false
        }
        AuthRoles.ADMIN !in principal.roles -> {
            call.respondAuth(
                HttpStatusCode.Forbidden,
                "Admin access required",
            )
            false
        }
        else -> true
    }
}

internal suspend fun requireCsrf(call: ApplicationCall): Boolean =
    requireCsrf(call, hasValidCsrfToken(call))

/**
 * CSRF check for subtrees that serve guests and logged-in users alike: a valid CSRF session plus
 * matching header passes without any user. The token binding is still enforced whenever the request
 * carries a user session, so a token minted for another user is rejected.
 */
internal suspend fun requireGuestCapableCsrf(call: ApplicationCall): Boolean =
    requireCsrf(call, hasValidGuestCapableCsrfToken(call))

private suspend fun requireCsrf(
    call: ApplicationCall,
    valid: Boolean,
): Boolean {
    if (valid) return true
    call.respond(
        HttpStatusCode.BadRequest,
        ApiError(message = "Invalid CSRF token"),
    )
    return false
}

private fun hasValidCsrfToken(call: ApplicationCall): Boolean {
    val principal = call.principal<UserPrincipal>() ?: return false
    val csrfSession = call.sessions.get<CsrfSession>() ?: return false
    if (csrfSession.userId != principal.userId) return false
    return suppliedTokenMatches(call, csrfSession)
}

private fun hasValidGuestCapableCsrfToken(call: ApplicationCall): Boolean {
    val csrfSession = call.sessions.get<CsrfSession>() ?: return false
    val userId = call.principal<UserPrincipal>()?.userId ?: call.currentUserSession()?.userId
    if (userId != null && csrfSession.userId != userId) return false
    return suppliedTokenMatches(call, csrfSession)
}

private fun suppliedTokenMatches(
    call: ApplicationCall,
    csrfSession: CsrfSession,
): Boolean {
    val supplied = call.request.headers[AuthRouting.CSRF_HEADER] ?: return false
    return MessageDigest.isEqual(
        csrfSession.token.toByteArray(Charsets.UTF_8),
        supplied.toByteArray(Charsets.UTF_8),
    )
}

private val SlidingSessionRenewal =
    createApplicationPlugin("AuthModuleSlidingSessionRenewal") {
        onCall { call ->
            val session = call.sessions.get<UserSession>() ?: return@onCall
            val now = Instant.now().epochSecond
            val elapsed = now - session.issuedAtEpochSeconds
            val remaining = session.expiresAtEpochSeconds - now
            if (remaining > 0 && elapsed > remaining) {
                call.sessions.set(
                    session.copy(
                        issuedAtEpochSeconds = now,
                        expiresAtEpochSeconds = now + SESSION_DURATION_SECONDS,
                    )
                )
            }
        }
    }

/** One process-wide source of CSRF token randomness. */
private val secureRandom = SecureRandom()

public object AuthRouting {
    public const val PROVIDER: String = "voenix-session"
    public const val CSRF_HEADER: String = "X-XSRF-TOKEN"
}

internal data class UserPrincipal(
    val userId: String,
    val roles: Set<String>,
    val issuedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
)

@Serializable
internal data class CsrfSession(
    val token: String,
    val userId: String?,
)

@Serializable internal data class AntiforgeryTokenResponse(val requestToken: String)
