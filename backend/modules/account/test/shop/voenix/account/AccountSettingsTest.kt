package shop.voenix.account

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import shop.voenix.http.FrontendBaseUrl

/**
 * What is left of the account settings once the frontend base URL became one application-wide value
 * (issue #110): the work factor, and that the shared URL arrives unchanged. The URL rules
 * themselves are pinned by `FrontendBaseUrlTest` in platform, where they now live.
 */
internal class AccountSettingsTest {
    private val frontendBaseUrl = FrontendBaseUrl("https://shop.example.com")

    @Test
    fun `the shared frontend base url is what the account mails are built from`() {
        assertEquals(
            frontendBaseUrl,
            AccountSettings.from(MapApplicationConfig(), frontendBaseUrl).frontendBaseUrl,
        )
    }

    @Test
    fun `the pbkdf2 iteration count is configurable but must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            AccountSettings(frontendBaseUrl, pbkdf2Iterations = 0)
        }

        val settings =
            AccountSettings.from(
                MapApplicationConfig("account.pbkdf2Iterations" to "1000"),
                frontendBaseUrl,
            )
        assertEquals(1_000, settings.pbkdf2Iterations)
        assertEquals(600_000, AccountSettings(frontendBaseUrl).pbkdf2Iterations)
    }
}
