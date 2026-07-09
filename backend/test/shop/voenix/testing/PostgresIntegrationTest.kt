package shop.voenix.testing

import io.ktor.server.config.MapApplicationConfig
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import shop.voenix.db.DatabaseSettings

@Testcontainers(disabledWithoutDocker = true)
abstract class PostgresIntegrationTest {
    protected fun postgresSettings(poolName: String): DatabaseSettings =
        DatabaseSettings(
            jdbcUrl = postgres.getJdbcUrl(),
            username = postgres.username,
            password = postgres.password,
            maximumPoolSize = 2,
            poolName = poolName,
        )

    protected fun postgresConfig(): MapApplicationConfig {
        val config = MapApplicationConfig()
        config.put("database.jdbcUrl", postgres.getJdbcUrl())
        config.put("database.username", postgres.username)
        config.put("database.password", postgres.password)
        config.put("database.maximumPoolSize", "2")
        return config
    }

    companion object {
        @Container
        @JvmField
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
