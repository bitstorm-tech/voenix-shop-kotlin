package shop.voenix.account

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.HexFormat

/**
 * The token mechanics both account services share: a confirmation, a password reset, an e-mail
 * change, and a supplier invitation all mail the *raw* token and store only its hash.
 *
 * The issuer exists because two services need exactly this and nothing else of each other:
 * [AccountService] issues confirmation, reset, and change-e-mail tokens, [SupplierLoginService]
 * issues the invitation token. Keeping the mechanics here means the lifetime, the randomness, and
 * the hash are decided in one place.
 */
internal class AccountTokenIssuer(
    private val repository: AccountRepository,
    private val clock: Clock,
) {
    /**
     * Stores a fresh token for [userId] and returns it unhashed, so the caller can mail its link.
     * Issuing replaces any previous token of the same [purpose], because the database keeps one row
     * per `(user_id, purpose)`.
     */
    suspend fun issue(
        userId: Long,
        purpose: AccountTokenPurpose,
        newEmail: String? = null,
    ): String {
        val token = newAccountToken()
        repository.issueToken(
            userId = userId,
            purpose = purpose,
            tokenHash = tokenHash(token),
            newEmail = newEmail,
            expiresAt =
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                    .plusHours(TOKEN_LIFETIME_HOURS),
        )
        return token
    }

    /** The stored form of a token a caller sent back to us. */
    fun hashOf(token: String): String = tokenHash(token)

    private companion object {
        const val TOKEN_LIFETIME_HOURS = 24L
    }
}

internal enum class AccountTokenPurpose {
    CONFIRM_EMAIL,
    RESET_PASSWORD,
    CHANGE_EMAIL,
}

/**
 * A fresh 256-bit random value in URL-safe Base64. Besides the mailed tokens it also serves as the
 * unguessable placeholder password of accounts that have none yet.
 */
internal fun newAccountToken(): String {
    val bytes = ByteArray(TOKEN_BYTES)
    tokenRandom.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private val tokenRandom = SecureRandom()

private const val TOKEN_BYTES = 32

private fun tokenHash(token: String): String =
    HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
