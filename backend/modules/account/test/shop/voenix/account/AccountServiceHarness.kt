package shop.voenix.account

import com.zaxxer.hikari.HikariDataSource
import java.time.Instant
import kotlin.test.assertIs
import org.jetbrains.exposed.v1.jdbc.Database
import shop.voenix.account.api.ConfirmEmailInput
import shop.voenix.account.api.RegisterInput
import shop.voenix.account.api.RegisterResult
import shop.voenix.account.persistence.AccountRepository
import shop.voenix.http.FrontendBaseUrl
import shop.voenix.operation.OperationResult

/**
 * One wired [AccountService] over a real database, shared by [AccountServiceIntegrationTest] and
 * [AccountServiceFailureIntegrationTest] — the flows and the error policy exercise the same
 * service, so they build it the same way.
 */
internal class AccountServiceHarness(
    val service: AccountService,
    val sender: RecordingUserEmailSender,
    val clock: MutableClock,
    private val dataSource: HikariDataSource,
) {
    suspend fun registerAndConfirm(email: String, password: String): Long {
        assertIs<RegisterResult.Registered>(service.register(RegisterInput(email, password)))
        val url = sender.lastConfirmationUrl()
        val userId = queryParameter(url, "userId").toLong()
        assertIs<OperationResult.Success<Unit>>(
            service.confirmEmail(ConfirmEmailInput(userId, queryParameter(url, "token")))
        )
        return userId
    }

    fun countRows(table: String): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM voenix.$table").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }
}

internal fun accountServiceHarness(dataSource: HikariDataSource): AccountServiceHarness {
    val sender = RecordingUserEmailSender()
    val clock = MutableClock(Instant.parse("2026-07-24T10:00:00Z"))
    val settings =
        AccountSettings(
            frontendBaseUrl = FrontendBaseUrl("http://localhost:5173"),
            pbkdf2Iterations = 1_000,
        )
    val service =
        AccountService(
            repository = AccountRepository(Database.connect(datasource = dataSource)),
            mails = AccountMailer(settings, sender),
            passwords = PasswordHasher(settings.pbkdf2Iterations),
            clock = clock,
        )
    return AccountServiceHarness(service, sender, clock, dataSource)
}
