package shop.voenix.order

import java.security.SecureRandom
import java.util.Base64

/**
 * The durable handle to one order: a bearer credential that opens exactly that order, and nothing
 * else.
 *
 * Every order gets one at placement (issue #110), the confirmation mail links to it, and the lookup
 * route reads an order by it. That makes it a *secret*, and the type is shaped after
 * `EmailActionUrl` for the same reason: the constructor is private, so a token only ever comes from
 * [generate] or from a string that has the right shape, and [toString] redacts [value], so no log
 * line, no data-class trace, and no exception message can ever print it. It is deliberately **not**
 * `@Serializable` — the token must never become part of an API answer, and a type that cannot be
 * serialized cannot be leaked by accident.
 *
 * 32 random bytes from [SecureRandom] are 256 bits, which is what makes enumeration pointless: no
 * amount of guessing finds a token, so the lookup route needs no rate limit to defend the order
 * behind it.
 */
internal class OrderAccessToken private constructor(val value: String) {
    override fun toString(): String = "OrderAccessToken([REDACTED])"

    override fun equals(other: Any?): Boolean = other is OrderAccessToken && value == other.value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        /**
         * The token [rawValue] spells, or `null` when it cannot be one.
         *
         * `null` rather than an exception, because the only caller is a route parameter: a string
         * from a URL that is not a token names no order, and that is a `404` like any other, never
         * a `400`. The check is the shape alone — length and alphabet — and says nothing about
         * whether such an order exists.
         */
        operator fun invoke(rawValue: String): OrderAccessToken? =
            when {
                rawValue.length == LENGTH && rawValue.all(::isTokenCharacter) ->
                    OrderAccessToken(rawValue)
                else -> null
            }

        /** A fresh token: 32 bytes of [SecureRandom], URL-safe Base64 without padding. */
        fun generate(): OrderAccessToken {
            val bytes = ByteArray(BYTES)
            RANDOM.nextBytes(bytes)
            return OrderAccessToken(ENCODER.encodeToString(bytes))
        }

        private fun isTokenCharacter(character: Char): Boolean =
            character in 'A'..'Z' ||
                character in 'a'..'z' ||
                character in '0'..'9' ||
                character == '-' ||
                character == '_'

        /** 32 bytes are 256 bits, and Base64 encodes them as exactly 43 unpadded characters. */
        private const val BYTES = 32
        private const val LENGTH = 43

        private val RANDOM = SecureRandom()
        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
