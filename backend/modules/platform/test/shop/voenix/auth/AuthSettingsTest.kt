package shop.voenix.auth

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class AuthSettingsTest {
    @Test
    fun `application configuration supplies the secret`() {
        val config = config("config-auth-session-secret-value-long")

        assertEquals(
            "config-auth-session-secret-value-long",
            AuthSettings.from(config).sessionSecret,
        )
    }

    @Test
    fun `missing and blank configuration values fail clearly`() {
        val missingFailure = assertFailsWith<IllegalStateException> { AuthSettings.from(config()) }
        assertEquals(
            "Missing required configuration value: Auth.SessionSecret",
            missingFailure.message,
        )

        val blankFailure =
            assertFailsWith<IllegalStateException> { AuthSettings.from(config("   ")) }
        assertEquals(
            "Missing required configuration value: Auth.SessionSecret",
            blankFailure.message,
        )
    }

    @Test
    fun `secret minimum is measured in utf8 bytes`() {
        val exactlyThirtyTwoBytes = "é".repeat(16)
        assertEquals(exactlyThirtyTwoBytes, AuthSettings(exactlyThirtyTwoBytes).sessionSecret)

        val thirtyOneBytes = "é".repeat(15) + "a"
        val failure = assertFailsWith<IllegalArgumentException> { AuthSettings(thirtyOneBytes) }
        assertEquals(
            "The auth session secret must contain at least 32 UTF-8 bytes",
            failure.message,
        )
    }

    private fun config(sessionSecret: String? = null): MapApplicationConfig =
        MapApplicationConfig().apply { sessionSecret?.let { put("Auth.SessionSecret", it) } }
}
