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

object AuthRoutes {
    const val CSRF_HEADER = "X-CSRF-Token"
    private const val AUTH_PROVIDER = "voenix-session"
    private const val SESSION_COOKIE_NAME = "voenix_session"
    private const val SESSION_SECRET = "voenix-session-authentication-key"
    private val secureRandom = SecureRandom()

    fun install(
        application: Application,
        database: Database,
    ) {
        val repository = AuthRepository(database)

        application.install(Sessions) {
            cookie<UserSession>(SESSION_COOKIE_NAME) {
                cookie.path = "/"
                cookie.httpOnly = true
                transform(SessionTransportTransformerMessageAuthentication(SESSION_SECRET.toByteArray()))
            }
        }

        application.install(Authentication) {
            session<UserSession>(AUTH_PROVIDER) {
                validate { session ->
                    repository
                        .findById(session.userId)
                        ?.takeIf { user -> user.emailConfirmed && !user.isLocked(nowEpochSeconds()) }
                        ?.let { user ->
                            UserPrincipal(
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
            post("/auth/login") {
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
                call.sessions.set(UserSession(userId = user.id, csrfToken = csrfToken))
                call.response.header(CSRF_HEADER, csrfToken)
                call.respondText("ok")
            }

            authenticate(AUTH_PROVIDER) {
                post("/auth/logout") {
                    call.sessions.clear<UserSession>()
                    call.respondText("ok")
                }

                get("/admin/proof") {
                    val principal = call.requireAdmin() ?: return@get
                    call.respondText("admin:${principal.email}")
                }

                post("/admin/proof") {
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

    private suspend fun ApplicationCall.requireAdmin(): UserPrincipal? {
        val principal = principal<UserPrincipal>() ?: return null
        if (principal.role != AuthRole.Admin) {
            respond(HttpStatusCode.Forbidden)
            return null
        }

        return principal
    }

    private fun ApplicationCall.hasValidCsrfToken(principal: UserPrincipal): Boolean {
        val supplied = request.headers[CSRF_HEADER] ?: return false

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
