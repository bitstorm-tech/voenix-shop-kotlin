package shop.voenix.account

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class AccountSettingsTest {
    @Test
    fun `startup fails fast when the frontend base url is missing or blank`() {
        assertFailsWith<IllegalStateException> { AccountSettings.from(MapApplicationConfig()) }
        assertFailsWith<IllegalStateException> {
            AccountSettings.from(MapApplicationConfig("Account.FrontendBaseUrl" to "   "))
        }
    }

    @Test
    fun `the frontend base url must be an absolute http url`() {
        assertFailsWith<IllegalArgumentException> { AccountSettings("not a url") }
        assertFailsWith<IllegalArgumentException> { AccountSettings("/relative/path") }
        assertFailsWith<IllegalArgumentException> { AccountSettings("ftp://shop.example.com") }
    }

    @Test
    fun `https is required outside local environments`() {
        assertFailsWith<IllegalArgumentException> { AccountSettings("http://shop.example.com") }

        assertEquals(
            "https://shop.example.com",
            AccountSettings("https://shop.example.com").frontendBaseUrl,
        )
        assertEquals(
            "http://localhost:5173",
            AccountSettings("http://localhost:5173/").frontendBaseUrl,
            "local development may use HTTP and trailing slashes are trimmed",
        )
        assertEquals(
            "http://127.0.0.1:5173",
            AccountSettings("http://127.0.0.1:5173").frontendBaseUrl,
        )
    }

    @Test
    fun `the pbkdf2 iteration count is configurable but must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            AccountSettings("https://shop.example.com", pbkdf2Iterations = 0)
        }

        val settings =
            AccountSettings.from(
                MapApplicationConfig(
                    "Account.FrontendBaseUrl" to "https://shop.example.com",
                    "Account.Pbkdf2Iterations" to "1000",
                )
            )
        assertEquals(1_000, settings.pbkdf2Iterations)
        assertEquals(600_000, AccountSettings("https://shop.example.com").pbkdf2Iterations)
    }
}
