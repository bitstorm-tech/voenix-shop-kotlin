package shop.voenix.account

import shop.voenix.email.EmailRecipient

private const val MINIMUM_PASSWORD_LENGTH = 8

/**
 * The single implementation of the e-mail format rule shared by several account inputs (register,
 * login, resend, forgot, reset, change-email). An e-mail is valid exactly when [EmailRecipient]
 * accepts it, so validation can never pass a value the mail-sending seam would reject.
 */
internal fun accountEmailErrors(value: String?): List<String> =
    when {
        value.isNullOrBlank() -> listOf("Email is required")
        runCatching { EmailRecipient(value) }.isFailure -> listOf("Invalid email format")
        else -> emptyList()
    }

/**
 * The single implementation of the password rule shared by register, reset, and change-password.
 */
internal fun accountPasswordErrors(value: String?): List<String> =
    when {
        value.isNullOrEmpty() -> listOf("Password is required")
        value.length < MINIMUM_PASSWORD_LENGTH -> listOf("Password must be at least 8 characters")
        else -> emptyList()
    }
