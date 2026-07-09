package shop.voenix.db

import io.ktor.server.config.MapApplicationConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseSettingsTest {
    @Test
    fun `reads database settings from Ktor config`() {
        val config = MapApplicationConfig()
        config.put("database.jdbcUrl", "jdbc:postgresql://localhost:5432/test")
        config.put("database.username", "test-user")
        config.put("database.password", "test-password")
        config.put("database.maximumPoolSize", "3")

        val settings = DatabaseSettings.from(config)

        assertEquals("jdbc:postgresql://localhost:5432/test", settings.jdbcUrl)
        assertEquals("test-user", settings.username)
        assertEquals("test-password", settings.password)
        assertEquals(3, settings.maximumPoolSize)
    }
}
