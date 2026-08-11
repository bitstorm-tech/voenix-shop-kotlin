package shop.voenix.config

import io.ktor.server.config.yaml.YamlConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

internal class ApplicationYamlConfigTest {
    @Test
    fun `application yaml loads modules and development defaults`() {
        val source = assertNotNull(javaClass.classLoader.getResource("application.yaml")).readText()
        val config = assertNotNull(YamlConfig("application.yaml"))

        // The base file must stay free of environment substitution. Deployments
        // override it with additional -config files on the command line, never
        // through environment variables.
        assertFalse(source.contains('$'), "application.yaml must not use \$VARIABLE substitution")

        assertEquals(
            listOf("shop.voenix.ApplicationKt.module"),
            config.property("ktor.application.modules").getList(),
        )

        val expectedValues =
            mapOf(
                "ktor.deployment.port" to "8080",
                "Database.Host" to "localhost",
                "Database.Port" to "5432",
                "Database.Database" to "voenix",
                "Database.Username" to "",
                "Database.Password" to "",
                "Database.SearchPath" to "voenix",
                "Database.SslMode" to "Disable",
                "Database.MaximumPoolSize" to "100",
                "Auth.SessionSecret" to "",
                "Account.FrontendBaseUrl" to "http://localhost:5173",
                "Email.Enabled" to "false",
                "Email.PollIntervalMinutes" to "5",
                "Email.ApiKey" to "",
                "Email.FromEmail" to "",
                "Email.FromName" to "Voenix Shop",
                "Production.ArtifactRoot" to "./data/production/artifacts",
                "Generator.DummyMode" to "false",
                "Generator.ApiKey" to "",
                "Mollie.ApiKey" to "",
                "Mollie.RedirectUrl" to "",
                "Mollie.WebhookUrl" to "",
                "Mollie.WebhookSecret" to "",
                "RateLimit.TrustForwardedFor" to "false",
                "Image.PublicRoot" to "./data/images/public",
                "Image.PrivateRoot" to "./data/images/private",
                "Image.CacheRoot" to "./data/images/cache",
            )

        expectedValues.forEach { (path, expectedValue) ->
            assertEquals(expectedValue, config.property(path).getString(), path)
        }
    }
}
