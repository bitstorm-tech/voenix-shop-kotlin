package shop.voenix.config

import io.ktor.server.config.yaml.YamlConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

internal class ApplicationYamlConfigTest {
    @Test
    fun `application yaml loads modules and environment fallbacks`() {
        val source = assertNotNull(javaClass.classLoader.getResource("application.yaml")).readText()
        val config = assertNotNull(YamlConfig("application.yaml"))

        listOf(
                "port: \"\$PORT:8080\"",
                "Host: \"\$DATABASE_HOST:localhost\"",
                "Port: \"\$DATABASE_PORT:5432\"",
                "Database: \"\$DATABASE_NAME:voenix\"",
                "Username: \"\$DATABASE_USERNAME:\"",
                "Password: \"\$DATABASE_PASSWORD:\"",
                "SearchPath: \"\$DATABASE_SEARCH_PATH:voenix\"",
                "SslMode: \"\$DATABASE_SSL_MODE:Disable\"",
                "MaximumPoolSize: \"\$DATABASE_MAX_POOL_SIZE:100\"",
                "SessionSecret: \"\$AUTH_SESSION_SECRET:\"",
                "Enabled: \"\$EMAIL_ENABLED:false\"",
                "PollIntervalMinutes: \"\$EMAIL_POLL_INTERVAL_MINUTES:5\"",
                "ApiKey: \"\$SWEEGO_API_KEY:\"",
                "FromEmail: \"\$EMAIL_FROM_ADDRESS:\"",
                "FromName: \"\$EMAIL_FROM_NAME:Voenix Shop\"",
                "ArtifactRoot: \"\$PRODUCTION_ARTIFACT_ROOT:./data/production/artifacts\"",
                "PublicRoot: \"\$IMAGE_PUBLIC_ROOT:./data/images/public\"",
                "PrivateRoot: \"\$IMAGE_PRIVATE_ROOT:./data/images/private\"",
                "CacheRoot: \"\$IMAGE_CACHE_ROOT:./data/images/cache\"",
            )
            .forEach { fallback -> assertContains(source, fallback) }

        assertEquals(
            listOf("shop.voenix.ApplicationKt.module"),
            config.property("ktor.application.modules").getList(),
        )

        val expectedValues =
            mapOf(
                "ktor.deployment.port" to resolvedEnvironmentValue("PORT", "8080"),
                "Database.Host" to resolvedEnvironmentValue("DATABASE_HOST", "localhost"),
                "Database.Port" to resolvedEnvironmentValue("DATABASE_PORT", "5432"),
                "Database.Database" to resolvedEnvironmentValue("DATABASE_NAME", "voenix"),
                "Database.Username" to resolvedEnvironmentValue("DATABASE_USERNAME", ""),
                "Database.Password" to resolvedEnvironmentValue("DATABASE_PASSWORD", ""),
                "Database.SearchPath" to resolvedEnvironmentValue("DATABASE_SEARCH_PATH", "voenix"),
                "Database.SslMode" to resolvedEnvironmentValue("DATABASE_SSL_MODE", "Disable"),
                "Database.MaximumPoolSize" to
                    resolvedEnvironmentValue("DATABASE_MAX_POOL_SIZE", "100"),
                "Auth.SessionSecret" to resolvedEnvironmentValue("AUTH_SESSION_SECRET", ""),
                "Email.Enabled" to resolvedEnvironmentValue("EMAIL_ENABLED", "false"),
                "Email.PollIntervalMinutes" to
                    resolvedEnvironmentValue("EMAIL_POLL_INTERVAL_MINUTES", "5"),
                "Email.ApiKey" to resolvedEnvironmentValue("SWEEGO_API_KEY", ""),
                "Email.FromEmail" to resolvedEnvironmentValue("EMAIL_FROM_ADDRESS", ""),
                "Email.FromName" to resolvedEnvironmentValue("EMAIL_FROM_NAME", "Voenix Shop"),
                "Production.ArtifactRoot" to
                    resolvedEnvironmentValue(
                        "PRODUCTION_ARTIFACT_ROOT",
                        "./data/production/artifacts",
                    ),
                "Image.PublicRoot" to
                    resolvedEnvironmentValue("IMAGE_PUBLIC_ROOT", "./data/images/public"),
                "Image.PrivateRoot" to
                    resolvedEnvironmentValue("IMAGE_PRIVATE_ROOT", "./data/images/private"),
                "Image.CacheRoot" to
                    resolvedEnvironmentValue("IMAGE_CACHE_ROOT", "./data/images/cache"),
            )

        expectedValues.forEach { (path, expectedValue) ->
            assertEquals(expectedValue, config.property(path).getString(), path)
        }
    }

    private fun resolvedEnvironmentValue(
        name: String,
        fallback: String,
    ): String = System.getProperty(name) ?: System.getenv(name) ?: fallback
}
