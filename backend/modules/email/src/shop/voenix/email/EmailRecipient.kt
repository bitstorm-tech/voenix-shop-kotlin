package shop.voenix.email

@JvmInline
public value class EmailRecipient private constructor(public val value: String) {
    public companion object {
        public operator fun invoke(rawValue: String): EmailRecipient {
            val value = rawValue.trim()
            require(value.length <= MAX_LENGTH) {
                "Email address must contain at most 255 characters"
            }
            require(value.none { it.isWhitespace() || it.isISOControl() }) {
                "Email address must not contain whitespace or control characters"
            }
            require(value.count { it == '@' } == 1) { "Email address must contain exactly one @" }
            val (local, domain) = value.split('@', limit = 2)
            require(local.isNotEmpty() && domain.isNotEmpty()) {
                "Email address must contain a local and domain part"
            }
            return EmailRecipient(value)
        }

        private const val MAX_LENGTH = 255
    }
}
