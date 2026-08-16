package shop.voenix.account

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import shop.voenix.email.UserEmail
import shop.voenix.operation.OperationResult
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The flows of [AccountService] over a real database. The error policy — provider failures, bugs of
 * our own, cancellation, database failures — lives in [AccountServiceFailureIntegrationTest]; both
 * classes share their wiring through [AccountServiceHarness].
 */
internal class AccountServiceIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `registration and confirmation flow works end to end via the mailed link`() = runBlocking {
        withService { harness ->
            assertEquals(
                RegisterResult.Registered,
                harness.service.register(RegisterInput("user@example.com", "password-1")),
                "a delivered confirmation mail is what makes a registration succeed",
            )

            val mail = harness.sender.sent.single()
            assertIs<UserEmail.AccountConfirmation>(mail)
            assertEquals("user@example.com", mail.recipient.value)
            val url = harness.sender.lastConfirmationUrl()
            assertTrue(
                url.startsWith("http://localhost:5173/confirm-email?userId=1&token="),
                url,
            )
            assertEquals(0, harness.countRows("email_jobs"))

            assertSame(
                LoginResult.EmailNotConfirmed,
                harness.service.login(LoginInput("user@example.com", "password-1")),
            )

            val userId = queryParameter(url, "userId").toLong()
            val token = queryParameter(url, "token")
            assertEquals(
                OperationResult.NotFound,
                harness.service.confirmEmail(ConfirmEmailInput(userId, "wrong-token")),
            )
            assertEquals(
                OperationResult.NotFound,
                harness.service.confirmEmail(ConfirmEmailInput(userId + 7, token)),
            )
            assertIs<OperationResult.Success<Unit>>(
                harness.service.confirmEmail(ConfirmEmailInput(userId, token))
            )
            assertEquals(
                OperationResult.NotFound,
                harness.service.confirmEmail(ConfirmEmailInput(userId, token)),
                "a confirmation link is single-use",
            )

            val signedIn =
                assertIs<LoginResult.SignedIn>(
                    harness.service.login(LoginInput(" user@example.com ", "password-1"))
                )
            assertEquals(userId, signedIn.userId)
            assertEquals(setOf("CUSTOMER"), signedIn.roles)

            val profile =
                assertIs<OperationResult.Success<AccountProfileView>>(
                    harness.service.profile(userId)
                )
            assertEquals("user@example.com", profile.value.email)
            assertEquals(listOf("CUSTOMER"), profile.value.roles)
            assertNull(profile.value.shippingAddress)
            assertEquals("2026-07-24T10:00:00Z", profile.value.createdAt)
        }
    }

    @Test
    fun `duplicate and concurrent case-variant registrations conflict on the unique index`() =
        runBlocking {
            withService { harness ->
                assertIs<RegisterResult.Registered>(
                    harness.service.register(RegisterInput("user@example.com", "password-1"))
                )
                assertSame(
                    RegisterResult.EmailTaken,
                    harness.service.register(RegisterInput("USER@example.com", "password-2")),
                )

                val concurrent = coroutineScope {
                    listOf(
                            async {
                                harness.service.register(
                                    RegisterInput("race@example.com", "password-1")
                                )
                            },
                            async {
                                harness.service.register(
                                    RegisterInput("Race@Example.com", "password-2")
                                )
                            },
                        )
                        .awaitAll()
                }
                assertEquals(1, concurrent.count { it is RegisterResult.Registered })
                assertEquals(1, concurrent.count { it === RegisterResult.EmailTaken })
            }
        }

    @Test
    fun `a reissued confirmation link invalidates the previous one and links expire after 24h`() =
        runBlocking {
            withService { harness ->
                harness.service.register(RegisterInput("user@example.com", "password-1"))
                val firstUrl = harness.sender.lastConfirmationUrl()
                val userId = queryParameter(firstUrl, "userId").toLong()

                assertIs<OperationResult.Success<Unit>>(
                    harness.service.resendConfirmation(AccountEmailInput("user@example.com"))
                )
                val secondUrl = harness.sender.lastConfirmationUrl()

                assertEquals(
                    OperationResult.NotFound,
                    harness.service.confirmEmail(
                        ConfirmEmailInput(userId, queryParameter(firstUrl, "token"))
                    ),
                    "only the latest link counts",
                )

                harness.clock.advanceBy(Duration.ofHours(24).plusSeconds(1))
                assertEquals(
                    OperationResult.NotFound,
                    harness.service.confirmEmail(
                        ConfirmEmailInput(userId, queryParameter(secondUrl, "token"))
                    ),
                    "links expire after 24 hours",
                )

                harness.service.resendConfirmation(AccountEmailInput("user@example.com"))
                assertIs<OperationResult.Success<Unit>>(
                    harness.service.confirmEmail(
                        ConfirmEmailInput(
                            userId,
                            queryParameter(harness.sender.lastConfirmationUrl(), "token"),
                        )
                    )
                )

                harness.sender.sent.clear()
                assertIs<OperationResult.Success<Unit>>(
                    harness.service.resendConfirmation(AccountEmailInput("user@example.com"))
                )
                assertIs<OperationResult.Success<Unit>>(
                    harness.service.resendConfirmation(AccountEmailInput("unknown@example.com"))
                )
                assertEquals(
                    emptyList(),
                    harness.sender.sent,
                    "resend sends nothing for confirmed or unknown accounts",
                )
            }
        }

    @Test
    fun `fifteen failed logins lock the account for ten minutes and success resets the counter`() =
        runBlocking {
            withService { harness ->
                harness.registerAndConfirm("user@example.com", "password-1")

                repeat(14) {
                    assertSame(
                        LoginResult.InvalidCredentials,
                        harness.service.login(LoginInput("user@example.com", "wrong")),
                    )
                }
                assertSame(
                    LoginResult.LockedOut,
                    harness.service.login(LoginInput("user@example.com", "wrong")),
                    "the fifteenth failure locks",
                )
                assertSame(
                    LoginResult.LockedOut,
                    harness.service.login(LoginInput("user@example.com", "password-1")),
                    "even the right password is refused while locked",
                )

                harness.clock.advanceBy(Duration.ofMinutes(10).plusSeconds(1))
                assertSame(
                    LoginResult.InvalidCredentials,
                    harness.service.login(LoginInput("user@example.com", "wrong")),
                    "an expired lockout starts counting from zero again",
                )
                assertIs<LoginResult.SignedIn>(
                    harness.service.login(LoginInput("user@example.com", "password-1"))
                )

                repeat(14) { harness.service.login(LoginInput("user@example.com", "wrong")) }
                assertIs<LoginResult.SignedIn>(
                    harness.service.login(LoginInput("user@example.com", "password-1")),
                    "a successful login resets the failure counter",
                )
                assertSame(
                    LoginResult.InvalidCredentials,
                    harness.service.login(LoginInput("user@example.com", "wrong")),
                )
            }
        }

    @Test
    fun `forgot password mails a link that resets the password once within its lifetime`() =
        runBlocking {
            withService { harness ->
                harness.registerAndConfirm("user@example.com", "password-1")

                harness.sender.sent.clear()
                assertIs<OperationResult.Success<Unit>>(
                    harness.service.forgotPassword(AccountEmailInput("unknown@example.com"))
                )
                assertEquals(
                    emptyList(),
                    harness.sender.sent,
                    "no mail for unknown accounts, same response",
                )

                assertIs<OperationResult.Success<Unit>>(
                    harness.service.forgotPassword(AccountEmailInput("user@example.com"))
                )
                val staleUrl = harness.sender.lastResetUrl()
                assertIs<OperationResult.Success<Unit>>(
                    harness.service.forgotPassword(AccountEmailInput("user@example.com"))
                )
                val url = harness.sender.lastResetUrl()
                assertTrue(url.startsWith("http://localhost:5173/reset-password?email="), url)
                val email = queryParameter(url, "email")
                assertEquals("user@example.com", email)

                assertEquals(
                    OperationResult.NotFound,
                    harness.service.resetPassword(
                        ResetPasswordInput(email, queryParameter(staleUrl, "token"), "password-2")
                    ),
                    "reissuing invalidated the previous reset link",
                )
                assertEquals(
                    OperationResult.NotFound,
                    harness.service.resetPassword(
                        ResetPasswordInput("unknown@example.com", "whatever", "password-2")
                    ),
                )

                val token = queryParameter(url, "token")
                assertIs<OperationResult.Success<Unit>>(
                    harness.service.resetPassword(ResetPasswordInput(email, token, "password-2"))
                )
                assertIs<UserEmail.PasswordChangedNotification>(harness.sender.sent.last())
                assertEquals(
                    OperationResult.NotFound,
                    harness.service.resetPassword(ResetPasswordInput(email, token, "password-3")),
                    "a reset link is single-use",
                )

                assertSame(
                    LoginResult.InvalidCredentials,
                    harness.service.login(LoginInput("user@example.com", "password-1")),
                )
                assertIs<LoginResult.SignedIn>(
                    harness.service.login(LoginInput("user@example.com", "password-2"))
                )
            }
        }

    @Test
    fun `profile update replaces all fields normalizes after validation and clears billing`() =
        runBlocking {
            withService { harness ->
                val userId = harness.registerAndConfirm("user@example.com", "password-1")

                val invalid =
                    assertIs<OperationResult.Invalid>(
                        harness.service.updateProfile(
                            userId,
                            ProfileInput(shippingAddress = Address(country = "DEU")),
                        )
                    )
                assertEquals(
                    mapOf("shippingAddress.country" to listOf("Country must be a two-letter code")),
                    invalid.errors,
                )

                val updated =
                    assertIs<OperationResult.Success<AccountProfileView>>(
                        harness.service.updateProfile(
                            userId,
                            ProfileInput(
                                shippingAddress =
                                    Address(
                                        firstName = " Erika ",
                                        lastName = "Musterfrau",
                                        street = "Musterstraße",
                                        houseNumber = "1a",
                                        postalCode = "12345",
                                        city = "Berlin",
                                        country = " DE ",
                                        phone = "   ",
                                    ),
                                hasSeparateBillingAddress = true,
                                billingAddress = Address(firstName = "Max", city = "Hamburg"),
                            ),
                        )
                    )
                assertEquals(
                    Address(
                        firstName = "Erika",
                        lastName = "Musterfrau",
                        street = "Musterstraße",
                        houseNumber = "1a",
                        postalCode = "12345",
                        city = "Berlin",
                        country = "DE",
                        phone = null,
                    ),
                    updated.value.shippingAddress,
                    "fields are trimmed and a blank phone becomes null",
                )
                assertEquals(
                    Address(firstName = "Max", city = "Hamburg"),
                    updated.value.billingAddress,
                )
                assertTrue(updated.value.hasSeparateBillingAddress)

                val cleared =
                    assertIs<OperationResult.Success<AccountProfileView>>(
                        harness.service.updateProfile(
                            userId,
                            ProfileInput(
                                shippingAddress = Address(firstName = "Erika"),
                                hasSeparateBillingAddress = false,
                                billingAddress = Address(firstName = "Max"),
                            ),
                        )
                    )
                assertNull(
                    cleared.value.billingAddress,
                    "switching the flag off clears the stored billing address",
                )
                assertEquals(
                    Address(firstName = "Erika"),
                    cleared.value.shippingAddress,
                    "the update replaces the whole shipping address",
                )

                assertSame(
                    OperationResult.NotFound,
                    harness.service.updateProfile(999, ProfileInput(Address(firstName = "X"))),
                )
                assertSame(OperationResult.NotFound, harness.service.profile(999))
            }
        }

    @Test
    fun `change email requires the password sends both mails and survives a late conflict`() =
        runBlocking {
            withService { harness ->
                val userId = harness.registerAndConfirm("old@example.com", "password-1")
                harness.registerAndConfirm("taken@example.com", "password-2")

                assertSame(
                    ChangeEmailResult.WrongPassword,
                    harness.service.changeEmail(
                        userId,
                        ChangeEmailInput("new@example.com", "wrong"),
                    ),
                )
                assertSame(
                    ChangeEmailResult.EmailTaken,
                    harness.service.changeEmail(
                        userId,
                        ChangeEmailInput("TAKEN@example.com", "password-1"),
                    ),
                )
                assertSame(
                    ChangeEmailResult.NotFound,
                    harness.service.changeEmail(
                        999,
                        ChangeEmailInput("new@example.com", "password-1"),
                    ),
                )

                harness.sender.sent.clear()
                assertSame(
                    ChangeEmailResult.ConfirmationSent,
                    harness.service.changeEmail(
                        userId,
                        ChangeEmailInput("new@example.com", "password-1"),
                    ),
                )
                val confirmation =
                    assertIs<UserEmail.ChangeEmailConfirmation>(harness.sender.sent.first())
                assertEquals("new@example.com", confirmation.recipient.value)
                val notification =
                    assertIs<UserEmail.ChangeEmailNotification>(harness.sender.sent.last())
                assertEquals("old@example.com", notification.recipient.value)
                assertEquals("new@example.com", notification.newEmail.value)

                val url = harness.sender.lastChangeEmailUrl()
                assertEquals("new@example.com", queryParameter(url, "newEmail"))

                // The new address is registered by someone else between request and confirm.
                harness.registerAndConfirm("new@example.com", "password-3")
                assertSame(
                    OperationResult.Conflict,
                    harness.service.confirmChangeEmail(
                        ConfirmChangeEmailInput(
                            userId,
                            queryParameter(url, "newEmail"),
                            queryParameter(url, "token"),
                        )
                    ),
                    "the unique index decides again at confirmation time",
                )

                harness.service.changeEmail(
                    userId,
                    ChangeEmailInput("final@example.com", "password-1"),
                )
                val finalUrl = harness.sender.lastChangeEmailUrl()
                assertEquals(
                    OperationResult.NotFound,
                    harness.service.confirmChangeEmail(
                        ConfirmChangeEmailInput(
                            userId,
                            "different@example.com",
                            queryParameter(finalUrl, "token"),
                        )
                    ),
                    "the link only confirms the address it was issued for",
                )
                assertIs<OperationResult.Success<Unit>>(
                    harness.service.confirmChangeEmail(
                        ConfirmChangeEmailInput(
                            userId,
                            queryParameter(finalUrl, "newEmail"),
                            queryParameter(finalUrl, "token"),
                        )
                    )
                )

                assertSame(
                    LoginResult.InvalidCredentials,
                    harness.service.login(LoginInput("old@example.com", "password-1")),
                )
                assertIs<LoginResult.SignedIn>(
                    harness.service.login(LoginInput("final@example.com", "password-1"))
                )
            }
        }

    @Test
    fun `change password verifies the current password and keeps a best-effort notification`() =
        runBlocking {
            withService { harness ->
                val userId = harness.registerAndConfirm("user@example.com", "password-1")

                assertSame(
                    ChangePasswordResult.WrongPassword,
                    harness.service.changePassword(
                        userId,
                        ChangePasswordInput("wrong", "password-2"),
                    ),
                )
                assertSame(
                    ChangePasswordResult.NotFound,
                    harness.service.changePassword(
                        999,
                        ChangePasswordInput("password-1", "password-2"),
                    ),
                )

                harness.sender.failure = { IllegalStateException("a rendering bug of ours") }
                assertSame(
                    ChangePasswordResult.Changed,
                    harness.service.changePassword(
                        userId,
                        ChangePasswordInput("password-1", "password-2"),
                    ),
                    "a best-effort mail never fails the operation, not even on a bug of ours",
                )
                harness.sender.failure = null

                assertIs<LoginResult.SignedIn>(
                    harness.service.login(LoginInput("user@example.com", "password-2"))
                )
            }
        }

    private suspend fun withService(block: suspend (AccountServiceHarness) -> Unit) {
        migratedDataSource("account-service-test-${System.nanoTime()}").use { dataSource ->
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("TRUNCATE voenix.users RESTART IDENTITY CASCADE")
                }
            }
            block(accountServiceHarness(dataSource))
        }
    }
}
