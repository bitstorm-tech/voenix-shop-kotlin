package shop.voenix.auth

import io.ktor.server.application.ApplicationCall
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
public data class UserSession(
    public val userId: String,
    public val roles: Set<String>,
    public val issuedAtEpochSeconds: Long = Instant.now().epochSecond,
    public val expiresAtEpochSeconds: Long =
        issuedAtEpochSeconds + DEFAULT_SESSION_DURATION_SECONDS,
) {
    public constructor(
        userId: String,
        role: String,
    ) : this(userId = userId, roles = setOf(role))

    public companion object {
        private const val DEFAULT_SESSION_DURATION_SECONDS = 86_400L
    }
}

public fun ApplicationCall.currentUserSession(): UserSession? =
    sessions.get<UserSession>()?.takeIf { session ->
        session.expiresAtEpochSeconds > Instant.now().epochSecond
    }
