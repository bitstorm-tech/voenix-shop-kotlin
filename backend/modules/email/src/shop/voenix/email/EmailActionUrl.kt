package shop.voenix.email

import java.net.URI

/** [value] is deliberately absent from [toString], so action links never leak into logs. */
public class EmailActionUrl private constructor(public val value: String) {
    override fun toString(): String = "EmailActionUrl([REDACTED])"

    override fun equals(other: Any?): Boolean = other is EmailActionUrl && value == other.value

    override fun hashCode(): Int = value.hashCode()

    public companion object {
        public operator fun invoke(rawValue: String): EmailActionUrl {
            val value = rawValue.trim()
            require(value.length <= MAX_LENGTH) {
                "Email action URL must contain at most 8192 characters"
            }
            require(value.none { it.isISOControl() }) {
                "Email action URL must not contain control characters"
            }
            val uri = runCatching {
                URI(value)
            }
                .getOrElse { throw IllegalArgumentException("Email action URL is invalid") }
            require(uri.isAbsolute && uri.scheme.lowercase() in ALLOWED_SCHEMES) {
                "Email action URL must be an absolute HTTP(S) URL"
            }
            require(!uri.host.isNullOrBlank()) { "Email action URL must contain a host" }
            require(uri.userInfo == null) { "Email action URL must not contain user information" }
            return EmailActionUrl(value)
        }

        private val ALLOWED_SCHEMES = setOf("http", "https")
        private const val MAX_LENGTH = 8192
    }
}
