package shop.voenix.account

import shop.voenix.email.EmailRecipient

/**
 * The single implementation of the field rules shared by several account inputs: the e-mail format
 * rule (register, login, resend, forgot, reset, change-email) and the password rule (register,
 * reset, change-password). An e-mail is valid exactly when [EmailRecipient] accepts it, so
 * validation can never pass a value the mail-sending seam would reject.
 */
internal object AccountFieldRules {
    const val MINIMUM_PASSWORD_LENGTH = 8

    fun emailErrors(value: String?): List<String> =
        when {
            value.isNullOrBlank() -> listOf("Email is required")
            runCatching { EmailRecipient(value) }.isFailure -> listOf("Invalid email format")
            else -> emptyList()
        }

    fun passwordErrors(value: String?): List<String> =
        when {
            value.isNullOrEmpty() -> listOf("Password is required")
            value.length < MINIMUM_PASSWORD_LENGTH ->
                listOf("Password must be at least 8 characters")
            else -> emptyList()
        }
}
