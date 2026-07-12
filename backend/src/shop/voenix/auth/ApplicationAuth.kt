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
import io.ktor.server.response.respondBytes
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
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import shop.voenix.http.ApiError

object ApplicationAuth {
    const val PROVIDER = "voenix-session"
    const val CSRF_HEADER = "X-XSRF-TOKEN"
    private const val ADMIN_ROLE = "ADMIN"
    private const val AUTH_COOKIE = "voenix.auth"
    private const val CSRF_COOKIE = "XSRF-TOKEN"
    private const val SESSION_DURATION_SECONDS = 24L * 60L * 60L

    fun install(
        application: Application,
        settings: AuthSettings,
    ) {
        application.install(Sessions) {
            sameAsRequestCookie(
                name = AUTH_COOKIE,
                sessionType = UserSession::class,
                serializer = defaultSessionSerializer<UserSession>(),
                transformer = encryptedTransformer(settings.sessionSecret, "auth"),
            )
            sameAsRequestCookie(
                name = CSRF_COOKIE,
                sessionType = CsrfSession::class,
                serializer = defaultSessionSerializer<CsrfSession>(),
                transformer = encryptedTransformer(settings.sessionSecret, "csrf"),
            )
        }
        application.install(SlidingSessionRenewal)

        application.install(Authentication) {
            session<UserSession>(PROVIDER) {
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

        application.routing {
            get("/api/antiforgery/token") {
                val token = newCsrfToken()
                val now = Instant.now().epochSecond
                val userId =
                    call.sessions
                        .get<UserSession>()
                        ?.takeIf { session -> session.expiresAtEpochSeconds > now }
                        ?.userId
                call.sessions.set(CsrfSession(token = token, userId = userId))
                call.respond(AntiforgeryTokenResponse(token))
            }
        }
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
            ADMIN_ROLE !in principal.roles -> {
                call.respondAuth(
                    HttpStatusCode.Forbidden,
                    "Admin access required",
                )
                false
            }
            else -> true
        }
    }

    internal suspend fun requireCsrf(call: ApplicationCall): Boolean {
        if (hasValidCsrfToken(call)) return true
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
        val supplied = call.request.headers[CSRF_HEADER] ?: return false
        return MessageDigest.isEqual(
            csrfSession.token.toByteArray(Charsets.UTF_8),
            supplied.toByteArray(Charsets.UTF_8),
        )
    }

    private suspend fun ApplicationCall.respondAuth(
        status: HttpStatusCode,
        message: String,
    ) {
        respondBytes(
            bytes = json.encodeToString(AuthResponse(false, message, null)).toByteArray(),
            contentType = io.ktor.http.ContentType.Application.Json,
            status = status,
        )
    }

    private fun encryptedTransformer(
        secret: String,
        purpose: String,
    ): SessionTransportTransformerEncrypt {
        val encryptionKey = digest("$purpose:encryption:$secret").copyOf(16)
        val signingKey = digest("$purpose:signing:$secret")
        return SessionTransportTransformerEncrypt(
            encryptionKeySpec = SecretKeySpec(encryptionKey, "AES"),
            signKeySpec = SecretKeySpec(signingKey, "HmacSHA256"),
        )
    }

    private fun digest(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun newCsrfToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private val SlidingSessionRenewal =
        createApplicationPlugin("ApplicationAuthSlidingSessionRenewal") {
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

    private val secureRandom = SecureRandom()
    private val json = Json { encodeDefaults = true }
}
