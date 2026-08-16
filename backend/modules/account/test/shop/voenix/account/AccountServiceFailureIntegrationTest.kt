package shop.voenix.account

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import shop.voenix.email.EmailDeliveryException
import shop.voenix.operation.OperationResult
import shop.voenix.testing.PostgresIntegrationTest

/**
 * The error policy of [AccountService]: what a failing mail delivery, a bug of our own, a
 * cancellation, and a failing database each become. The happy flows live in
 * [AccountServiceIntegrationTest]; the two classes share their wiring through
 * [AccountServiceHarness].
 */
internal class AccountServiceFailureIntegrationTest : PostgresIntegrationTest() {
    @Test
    fun `a failing provider fails required deliveries but never enumeration-safe flows`() =
        runBlocking {
            withService { harness ->
                harness.sender.failure = { EmailDeliveryException() }

                assertSame(
                    RegisterResult.DeliveryFailed,
                    harness.service.register(RegisterInput("user@example.com", "password-1")),
                )
                assertSame(
                    RegisterResult.EmailTaken,
                    harness.service.register(RegisterInput("user@example.com", "password-1")),
                    "the account exists although the mail failed; resend is the retry path",
                )
                assertIs<OperationResult.Success<Unit>>(
                    harness.service.resendConfirmation(AccountEmailInput("user@example.com")),
                    "a failed resend delivery never changes the response",
                )
                assertIs<OperationResult.Success<Unit>>(
                    harness.service.forgotPassword(AccountEmailInput("user@example.com")),
                    "a failed reset delivery never changes the response",
                )

                harness.sender.failure = null
                harness.service.resendConfirmation(AccountEmailInput("user@example.com"))
                val url = harness.sender.lastConfirmationUrl()
                val userId = queryParameter(url, "userId").toLong()
                assertIs<OperationResult.Success<Unit>>(
                    harness.service.confirmEmail(
                        ConfirmEmailInput(userId, queryParameter(url, "token"))
                    )
                )

                harness.sender.failure = { EmailDeliveryException() }
                assertSame(
                    ChangeEmailResult.DeliveryFailed,
                    harness.service.changeEmail(
                        userId,
                        ChangeEmailInput("new@example.com", "password-1"),
                    ),
                )
            }
        }

    @Test
    fun `a mail failure that is not the provider becomes an internal failure, not a 502`() =
        runBlocking {
            withService { harness ->
                // Everything a send can throw except EmailDeliveryException is a bug on our side —
                // a rendering failure, a malformed link. It must not be reported as "the provider
                // rejected it", because the customer's retry could never fix it.
                harness.sender.failure = { IllegalStateException("a rendering bug of ours") }

                assertSame(
                    RegisterResult.UnexpectedFailure,
                    harness.service.register(RegisterInput("user@example.com", "password-1")),
                    "a bug of ours is an internal failure, never a delivery failure",
                )

                harness.sender.failure = null
                val userId = harness.registerAndConfirm("other@example.com", "password-1")

                harness.sender.failure = { IllegalStateException("a rendering bug of ours") }
                assertSame(
                    ChangeEmailResult.UnexpectedFailure,
                    harness.service.changeEmail(
                        userId,
                        ChangeEmailInput("new@example.com", "password-1"),
                    ),
                    "the required change-email confirmation follows the same rule",
                )
                assertSame(
                    ChangePasswordResult.Changed,
                    harness.service.changePassword(
                        userId,
                        ChangePasswordInput("password-1", "password-2"),
                    ),
                    "the best-effort notification still swallows the very same bug",
                )
                assertIs<OperationResult.Success<Unit>>(
                    harness.service.forgotPassword(AccountEmailInput("other@example.com")),
                    "and the enumeration-safe flows still answer identically",
                )
            }
        }

    @Test
    fun `cancellation is rethrown instead of being converted into a result`() = runBlocking {
        withService { harness ->
            harness.sender.failure = { CancellationException("cancelled") }
            assertFailsWith<CancellationException> {
                harness.service.register(RegisterInput("user@example.com", "password-1"))
            }
        }
    }

    @Test
    fun `database failures are hidden behind unexpected failures except enumeration-safe flows`() =
        runBlocking<Unit> {
            val dataSource = migratedDataSource("account-database-failure-${System.nanoTime()}")
            val harness = accountServiceHarness(dataSource)
            dataSource.close()

            assertSame(
                RegisterResult.UnexpectedFailure,
                harness.service.register(RegisterInput("user@example.com", "password-1")),
            )
            assertSame(
                LoginResult.UnexpectedFailure,
                harness.service.login(LoginInput("user@example.com", "password-1")),
            )
            assertSame(
                OperationResult.UnexpectedFailure,
                harness.service.confirmEmail(ConfirmEmailInput(1, "token")),
            )
            assertSame(
                OperationResult.UnexpectedFailure,
                harness.service.resetPassword(
                    ResetPasswordInput("user@example.com", "token", "password-2")
                ),
            )
            assertSame(OperationResult.UnexpectedFailure, harness.service.profile(1))
            assertSame(
                OperationResult.UnexpectedFailure,
                harness.service.updateProfile(1, ProfileInput(Address(firstName = "X"))),
            )
            assertSame(
                ChangeEmailResult.UnexpectedFailure,
                harness.service.changeEmail(1, ChangeEmailInput("new@example.com", "pw")),
            )
            assertSame(
                OperationResult.UnexpectedFailure,
                harness.service.confirmChangeEmail(
                    ConfirmChangeEmailInput(1, "new@example.com", "token")
                ),
            )
            assertSame(
                ChangePasswordResult.UnexpectedFailure,
                harness.service.changePassword(1, ChangePasswordInput("pw", "password-2")),
            )

            assertIs<OperationResult.Success<Unit>>(
                harness.service.resendConfirmation(AccountEmailInput("user@example.com")),
                "enumeration-safe flows suppress even database failures",
            )
            assertIs<OperationResult.Success<Unit>>(
                harness.service.forgotPassword(AccountEmailInput("user@example.com")),
                "enumeration-safe flows suppress even database failures",
            )
        }

    private suspend fun withService(block: suspend (AccountServiceHarness) -> Unit) {
        migratedDataSource("account-failure-test-${System.nanoTime()}").use { dataSource ->
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("TRUNCATE voenix.users RESTART IDENTITY CASCADE")
                }
            }
            block(accountServiceHarness(dataSource))
        }
    }
}
