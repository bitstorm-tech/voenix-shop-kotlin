package shop.voenix.testing

import shop.voenix.db.DatabaseSettings
import io.ktor.server.config.MapApplicationConfig
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@Testcontainers(disabledWithoutDocker = true)
abstract class PostgresIntegrationTest {
    protected fun postgresSettings(poolName: String): DatabaseSettings =
        DatabaseSettings(
            jdbcUrl = postgres.getJdbcUrl(),
            username = postgres.getUsername(),
            password = postgres.getPassword(),
            maximumPoolSize = 2,
            poolName = poolName,
        )

    protected fun postgresConfig(): MapApplicationConfig {
        val config = MapApplicationConfig()
        config.put("database.jdbcUrl", postgres.getJdbcUrl())
        config.put("database.username", postgres.getUsername())
        config.put("database.password", postgres.getPassword())
        config.put("database.maximumPoolSize", "2")
        return config
    }

    companion object {
        @Container
        @JvmField
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
    }
}
