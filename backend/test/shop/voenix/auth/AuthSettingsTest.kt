package shop.voenix.auth

import io.ktor.server.config.MapApplicationConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthSettingsTest {
    @Test
    fun `secrets file takes precedence with case-insensitive section and key names`() {
        withSecretsFile(
            """{"aUtH":{"sEsSiOnSeCrEt":"file-auth-session-secret-value-long"}}""",
        ) { path ->
            val config = config(path, fallback = "config-auth-session-secret-value-long")

            assertEquals("file-auth-session-secret-value-long", AuthSettings.from(config).sessionSecret)
        }
    }

    @Test
    fun `application configuration supplies the secret when the secrets file has no auth value`() {
        withSecretsFile("""{"Other":{"Value":"ignored"}}""") { path ->
            val config = config(path, fallback = "config-auth-session-secret-value-long")

            assertEquals("config-auth-session-secret-value-long", AuthSettings.from(config).sessionSecret)
        }
    }

    @Test
    fun `missing and blank configuration values fail clearly`() {
        val missingFile = Files.createTempDirectory("voenix-auth-settings").resolve("missing.json")
        try {
            val missing = config(missingFile)
            val missingFailure = assertFailsWith<IllegalStateException> { AuthSettings.from(missing) }
            assertEquals(
                "Missing required configuration value: Auth.SessionSecret",
                missingFailure.message,
            )

            val blank = config(missingFile, fallback = "   ")
            val blankFailure = assertFailsWith<IllegalStateException> { AuthSettings.from(blank) }
            assertEquals(
                "Missing required configuration value: Auth.SessionSecret",
                blankFailure.message,
            )
        } finally {
            Files.deleteIfExists(missingFile.parent)
        }

        withSecretsFile("""{"Auth":{"SessionSecret":""}}""") { path ->
            val blankFileValue = config(path, fallback = "config-auth-session-secret-value-long")
            assertFailsWith<IllegalArgumentException> { AuthSettings.from(blankFileValue) }
        }
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

    private fun config(
        secretsPath: Path,
        fallback: String? = null,
    ): MapApplicationConfig =
        MapApplicationConfig().apply {
            put("Secrets.AppSettingsPath", secretsPath.toString())
            fallback?.let { put("Auth.SessionSecret", it) }
        }

    private fun withSecretsFile(
        contents: String,
        block: (Path) -> Unit,
    ) {
        val path = Files.createTempFile("voenix-auth-settings", ".json")
        try {
            Files.writeString(path, contents)
            block(path)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
