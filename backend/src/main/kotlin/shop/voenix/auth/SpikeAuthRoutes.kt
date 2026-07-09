package shop.voenix.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.auth.session
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import org.jetbrains.exposed.v1.jdbc.Database
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

object SpikeAuthRoutes {
    const val CsrfHeader = "X-CSRF-Token"
    private const val AuthProvider = "spike-session"
    private const val SessionCookieName = "voenix_spike_session"
    private const val SessionSecret = "voenix-spike-session-authentication-key"
    private val secureRandom = SecureRandom()

    fun install(
        application: Application,
        database: Database,
    ) {
        val repository = SpikeAuthRepository(database)

        application.install(Sessions) {
            cookie<SpikeUserSession>(SessionCookieName) {
                cookie.path = "/"
                cookie.httpOnly = true
                transform(SessionTransportTransformerMessageAuthentication(SessionSecret.toByteArray()))
            }
        }

        application.install(Authentication) {
            session<SpikeUserSession>(AuthProvider) {
                validate { session ->
                    repository
                        .findById(session.userId)
                        ?.takeIf { user -> user.emailConfirmed && !user.isLocked(nowEpochSeconds()) }
                        ?.let { user ->
                            SpikeUserPrincipal(
                                userId = user.id,
                                email = user.email,
                                role = user.role,
                                csrfToken = session.csrfToken,
                            )
                        }
                }
                challenge {
                    call.respond(HttpStatusCode.Unauthorized)
                }
            }
        }

        application.routing {
            post("/spike-auth/login") {
                val parameters = call.receiveParameters()
                val user =
                    repository.authenticate(
                        email = parameters["email"].orEmpty(),
                        password = parameters["password"].orEmpty(),
                        nowEpochSeconds = nowEpochSeconds(),
                    )

                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized)
                    return@post
                }

                val csrfToken = newCsrfToken()
                call.sessions.set(SpikeUserSession(userId = user.id, csrfToken = csrfToken))
                call.response.header(CsrfHeader, csrfToken)
                call.respondText("ok")
            }

            authenticate(AuthProvider) {
                post("/spike-auth/logout") {
                    call.sessions.clear<SpikeUserSession>()
                    call.respondText("ok")
                }

                get("/spike-admin/proof") {
                    val principal = call.requireAdmin() ?: return@get
                    call.respondText("admin:${principal.email}")
                }

                post("/spike-admin/proof") {
                    val principal = call.requireAdmin() ?: return@post
                    if (!call.hasValidCsrfToken(principal)) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@post
                    }

                    call.respondText("changed:${principal.email}")
                }
            }
        }
    }

    private suspend fun ApplicationCall.requireAdmin(): SpikeUserPrincipal? {
        val principal = principal<SpikeUserPrincipal>() ?: return null
        if (principal.role != SpikeAuthRole.Admin) {
            respond(HttpStatusCode.Forbidden)
            return null
        }

        return principal
    }

    private fun ApplicationCall.hasValidCsrfToken(principal: SpikeUserPrincipal): Boolean {
        val supplied = request.headers[CsrfHeader] ?: return false

        return MessageDigest.isEqual(
            supplied.toByteArray(),
            principal.csrfToken.toByteArray(),
        )
    }

    private fun newCsrfToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun nowEpochSeconds(): Long = Instant.now().epochSecond
}
