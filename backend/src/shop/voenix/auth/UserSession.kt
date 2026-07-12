package shop.voenix.auth

import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class UserSession(
    val userId: String,
    val roles: Set<String>,
    val issuedAtEpochSeconds: Long = Instant.now().epochSecond,
    val expiresAtEpochSeconds: Long = issuedAtEpochSeconds + DEFAULT_SESSION_DURATION_SECONDS,
) {
    constructor(
        userId: String,
        role: String,
    ) : this(userId = userId, roles = setOf(role))

    companion object {
        private const val DEFAULT_SESSION_DURATION_SECONDS = 86_400L
    }
}
