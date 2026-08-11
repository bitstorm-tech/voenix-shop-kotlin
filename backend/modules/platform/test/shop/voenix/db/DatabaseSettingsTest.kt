package shop.voenix.db

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal class DatabaseSettingsTest {
    @Test
    fun `application configuration supplies database settings`() {
        val config =
            MapApplicationConfig().apply {
                put("database.host", "configured-host")
                put("database.port", "5544")
                put("database.database", "configured-db")
                put("database.username", "configured-user")
                put("database.password", "configured-password")
                put("database.searchPath", "catalog")
                put("database.sslMode", "VerifyFull")
            }

        val settings = DatabaseSettings.from(config)

        assertEquals(
            "jdbc:postgresql://configured-host:5544/configured-db?currentSchema=catalog&sslmode=verify-full",
            settings.jdbcUrl,
        )
        assertEquals("configured-user", settings.username)
        assertEquals("configured-password", settings.password)
        assertEquals(100, settings.maximumPoolSize)
        assertEquals("catalog", settings.searchPath)
    }

    @Test
    fun `required database values reject whitespace`() {
        val config =
            MapApplicationConfig().apply {
                put("database.host", "   ")
                put("database.database", "voenix")
                put("database.username", "voenix")
                put("database.password", "voenix")
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
        val config = databaseConfig().apply { put("database.searchPath", "catalog") }

        assertEquals("catalog", DatabaseSettings.from(config).searchPath)
    }

    @Test
    fun `search path rejects identifiers longer than postgres supports`() {
        val config = databaseConfig().apply { put("database.searchPath", "a".repeat(64)) }

        assertFailsWith<IllegalArgumentException> { DatabaseSettings.from(config) }
    }

    private fun databaseConfig(): MapApplicationConfig =
        MapApplicationConfig().apply {
            put("database.host", "localhost")
            put("database.database", "shop")
            put("database.username", "shop")
            put("database.password", "shop")
        }
}
