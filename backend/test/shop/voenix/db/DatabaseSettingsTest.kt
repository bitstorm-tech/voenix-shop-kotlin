package shop.voenix.db

import io.ktor.server.config.MapApplicationConfig
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DatabaseSettingsTest {
    @Test
    fun `secret appsettings override normal database configuration`() {
        val secrets = Files.createTempFile("voenix-appsettings", ".json")
        try {
            secrets.writeText(
                """
                {
                  "Database": {
                    "Host": "secret-host",
                    "Port": 5544,
                    "Database": "secret-db",
                    "Username": "secret-user",
                    "Password": "secret-password",
                    "SearchPath": "catalog",
                    "SslMode": "VerifyFull"
                  }
                }
                """.trimIndent(),
            )
            val config =
                MapApplicationConfig().apply {
                    put("Secrets.AppSettingsPath", secrets.toString())
                    put("Database.Host", "normal-host")
                    put("Database.Database", "normal-db")
                    put("Database.Username", "normal-user")
                    put("Database.Password", "normal-password")
                }

            val settings = DatabaseSettings.from(config)

            assertEquals(
                "jdbc:postgresql://secret-host:5544/secret-db?currentSchema=catalog&sslmode=verify-full",
                settings.jdbcUrl,
            )
            assertEquals("secret-user", settings.username)
            assertEquals("secret-password", settings.password)
            assertEquals(100, settings.maximumPoolSize)
            assertEquals("catalog", settings.searchPath)
        } finally {
            Files.deleteIfExists(secrets)
        }
    }

    @Test
    fun `required database values reject whitespace`() {
        val config =
            MapApplicationConfig().apply {
                put("Secrets.AppSettingsPath", "/path/that/does/not/exist")
                put("Database.Host", "   ")
                put("Database.Database", "voenix")
                put("Database.Username", "voenix")
                put("Database.Password", "voenix")
            }

        assertFailsWith<IllegalStateException> { DatabaseSettings.from(config) }
    }

    @Test
    fun `search path defaults to voenix`() {
        val settings = DatabaseSettings.from(databaseConfig())

        assertEquals("voenix", settings.searchPath)
        assertEquals(
            "jdbc:postgresql://localhost:5432/shop?currentSchema=voenix&sslmode=disable",
            settings.jdbcUrl,
        )
    }

    @Test
    fun `configured search path selects the current schema`() {
        val config = databaseConfig().apply { put("Database.SearchPath", "catalog") }

        assertEquals("catalog", DatabaseSettings.from(config).searchPath)
    }

    @Test
    fun `search path rejects identifiers longer than postgres supports`() {
        val config = databaseConfig().apply { put("Database.SearchPath", "a".repeat(64)) }

        assertFailsWith<IllegalArgumentException> { DatabaseSettings.from(config) }
    }

    private fun databaseConfig(): MapApplicationConfig =
        MapApplicationConfig().apply {
            put("Secrets.AppSettingsPath", "/path/that/does/not/exist")
            put("Database.Host", "localhost")
            put("Database.Database", "shop")
            put("Database.Username", "shop")
            put("Database.Password", "shop")
        }
}
