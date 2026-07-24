package shop.voenix.account

import kotlin.test.Test
import kotlin.test.assertEquals

internal class AccountInputValidationTest {
    @Test
    fun `register validates email format and the shared password rule`() {
        assertEquals(
            mapOf(
                "email" to listOf("Email is required"),
                "password" to listOf("Password is required"),
            ),
            RegisterInput().validate(),
        )
        listOf("no-at-sign", "two@@signs", "two@at@signs", "@missing-local", "missing-domain@")
            .forEach { email ->
                assertEquals(
                    mapOf("email" to listOf("Invalid email format")),
                    RegisterInput(email, "password-1").validate(),
                    "email: $email",
                )
            }
        assertEquals(
            mapOf("email" to listOf("Invalid email format")),
            RegisterInput("a".repeat(250) + "@example.com", "password-1").validate(),
        )
        assertEquals(
            mapOf("password" to listOf("Password must be at least 8 characters")),
            RegisterInput("user@example.com", "1234567").validate(),
        )
        assertEquals(emptyMap(), RegisterInput("user@example.com", "12345678").validate())
        assertEquals(emptyMap(), RegisterInput(" user@example.com ", "12345678").validate())
    }

    @Test
    fun `login validates the email but never the password shape`() {
        assertEquals(
            mapOf(
                "email" to listOf("Email is required"),
                "password" to listOf("Password is required"),
            ),
            LoginInput().validate(),
        )
        assertEquals(
            mapOf("email" to listOf("Invalid email format")),
            LoginInput("not-an-email", "x").validate(),
        )
        assertEquals(emptyMap(), LoginInput("user@example.com", "x").validate())
    }

    @Test
    fun `confirm email requires user id and token`() {
        assertEquals(
            mapOf(
                "userId" to listOf("User id is required"),
                "token" to listOf("Token is required"),
            ),
            ConfirmEmailInput().validate(),
        )
        assertEquals(
            mapOf("token" to listOf("Token is required")),
            ConfirmEmailInput(userId = 1, token = " ").validate(),
        )
        assertEquals(emptyMap(), ConfirmEmailInput(userId = 1, token = "abc").validate())
    }

    @Test
    fun `resend confirmation and forgot password share the email-only input`() {
        assertEquals(
            mapOf("email" to listOf("Email is required")),
            AccountEmailInput().validate(),
        )
        assertEquals(
            mapOf("email" to listOf("Invalid email format")),
            AccountEmailInput("nope").validate(),
        )
        assertEquals(emptyMap(), AccountEmailInput("user@example.com").validate())
    }

    @Test
    fun `reset password validates email token and the shared password rule`() {
        assertEquals(
            mapOf(
                "email" to listOf("Email is required"),
                "token" to listOf("Token is required"),
                "newPassword" to listOf("Password is required"),
            ),
            ResetPasswordInput().validate(),
        )
        assertEquals(
            mapOf("newPassword" to listOf("Password must be at least 8 characters")),
            ResetPasswordInput("user@example.com", "token", "1234567").validate(),
        )
        assertEquals(
            emptyMap(),
            ResetPasswordInput("user@example.com", "token", "12345678").validate(),
        )
    }

    @Test
    fun `profile requires a shipping address and applies the address field rules to both`() {
        assertEquals(
            mapOf("shippingAddress" to listOf("Shipping address is required")),
            ProfileInput().validate(),
        )
        assertEquals(
            emptyMap(),
            ProfileInput(shippingAddress = Address(firstName = "Erika")).validate(),
        )

        val tooLong =
            Address(
                firstName = "a".repeat(101),
                lastName = "a".repeat(101),
                street = "a".repeat(201),
                houseNumber = "a".repeat(21),
                postalCode = "a".repeat(11),
                city = "a".repeat(101),
            )
        assertEquals(
            mapOf(
                "shippingAddress.firstName" to listOf("Must be at most 100 characters"),
                "shippingAddress.lastName" to listOf("Must be at most 100 characters"),
                "shippingAddress.street" to listOf("Must be at most 200 characters"),
                "shippingAddress.houseNumber" to listOf("Must be at most 20 characters"),
                "shippingAddress.postalCode" to listOf("Must be at most 10 characters"),
                "shippingAddress.city" to listOf("Must be at most 100 characters"),
            ),
            ProfileInput(shippingAddress = tooLong).validate(),
        )

        listOf("DEU", "D", "D1", "12").forEach { country ->
            assertEquals(
                mapOf("shippingAddress.country" to listOf("Country must be a two-letter code")),
                ProfileInput(shippingAddress = Address(country = country)).validate(),
                "country: $country",
            )
        }
        listOf("DE", "de", " DE ").forEach { country ->
            assertEquals(
                emptyMap(),
                ProfileInput(shippingAddress = Address(country = country)).validate(),
                "country: $country",
            )
        }

        listOf("abc", "+49a", "12#34").forEach { phone ->
            assertEquals(
                mapOf("shippingAddress.phone" to listOf("Phone is not a valid phone number")),
                ProfileInput(shippingAddress = Address(phone = phone)).validate(),
                "phone: $phone",
            )
        }
        listOf("+49 (0) 30 123-456/78", "030 1234567", "+1.23").forEach { phone ->
            assertEquals(
                emptyMap(),
                ProfileInput(shippingAddress = Address(phone = phone)).validate(),
                "phone: $phone",
            )
        }

        assertEquals(
            mapOf("billingAddress.country" to listOf("Country must be a two-letter code")),
            ProfileInput(
                    shippingAddress = Address(firstName = "Erika"),
                    hasSeparateBillingAddress = true,
                    billingAddress = Address(country = "DEU"),
                )
                .validate(),
        )
    }

    @Test
    fun `change email requires the new email and the current password`() {
        assertEquals(
            mapOf(
                "newEmail" to listOf("Email is required"),
                "currentPassword" to listOf("Current password is required"),
            ),
            ChangeEmailInput().validate(),
        )
        assertEquals(
            mapOf("newEmail" to listOf("Invalid email format")),
            ChangeEmailInput("nope", "secret").validate(),
        )
        assertEquals(emptyMap(), ChangeEmailInput("new@example.com", "secret").validate())
    }

    @Test
    fun `confirm change email requires user id new email and token`() {
        assertEquals(
            mapOf(
                "userId" to listOf("User id is required"),
                "newEmail" to listOf("Email is required"),
                "token" to listOf("Token is required"),
            ),
            ConfirmChangeEmailInput().validate(),
        )
        assertEquals(
            emptyMap(),
            ConfirmChangeEmailInput(1, "new@example.com", "token").validate(),
        )
    }

    @Test
    fun `change password requires the current password and validates the new one`() {
        assertEquals(
            mapOf(
                "currentPassword" to listOf("Current password is required"),
                "newPassword" to listOf("Password is required"),
            ),
            ChangePasswordInput().validate(),
        )
        assertEquals(
            mapOf("newPassword" to listOf("Password must be at least 8 characters")),
            ChangePasswordInput("current", "1234567").validate(),
        )
        assertEquals(emptyMap(), ChangePasswordInput("current", "12345678").validate())
    }
}
