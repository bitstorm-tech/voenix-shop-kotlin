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
                "database.host" to "localhost",
                "database.port" to "5432",
                "database.database" to "voenix",
                "database.username" to "",
                "database.password" to "",
                "database.searchPath" to "voenix",
                "database.sslMode" to "Disable",
                "database.maximumPoolSize" to "100",
                "auth.sessionSecret" to "",
                "frontend.baseUrl" to "http://localhost:5173",
                "frontend.distPath" to "",
                "email.enabled" to "false",
                "email.pollIntervalMinutes" to "5",
                "email.apiKey" to "",
                "email.fromEmail" to "",
                "email.fromName" to "Voenix Shop",
                "production.artifactRoot" to "./data/production/artifacts",
                "production.spod.webhookSecret" to "",
                "production.spod.alertEmail" to "",
                "generator.dummyMode" to "false",
                "generator.apiKey" to "",
                "mollie.apiKey" to "",
                "mollie.redirectUrl" to "",
                "mollie.webhookUrl" to "",
                "mollie.webhookSecret" to "",
                "rateLimit.trustForwardedFor" to "false",
                "image.publicRoot" to "./data/images/public",
                "image.privateRoot" to "./data/images/private",
                "image.cacheRoot" to "./data/images/cache",
            )

        expectedValues.forEach { (path, expectedValue) ->
            assertEquals(expectedValue, config.property(path).getString(), path)
        }
    }
}
