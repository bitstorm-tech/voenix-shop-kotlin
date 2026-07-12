package shop.voenix.config

import io.ktor.server.config.yaml.YamlConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ApplicationYamlConfigTest {
    @Test
    fun `application yaml loads modules and environment fallbacks`() {
        val source =
            assertNotNull(javaClass.classLoader.getResource("application.yaml"))
                .readText()
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
        ).forEach { fallback -> assertContains(source, fallback) }

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
                "Database.MaximumPoolSize" to resolvedEnvironmentValue("DATABASE_MAX_POOL_SIZE", "100"),
                "Auth.SessionSecret" to resolvedEnvironmentValue("AUTH_SESSION_SECRET", ""),
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
