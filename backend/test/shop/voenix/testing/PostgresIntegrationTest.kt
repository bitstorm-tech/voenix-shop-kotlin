package shop.voenix.testing

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@Testcontainers
abstract class PostgresIntegrationTest {
    protected fun migratedDataSource(poolName: String): HikariDataSource =
        dataSource(poolName, DEFAULT_SCHEMA).also { dataSource ->
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .defaultSchema(DEFAULT_SCHEMA)
                .schemas(DEFAULT_SCHEMA)
                .load()
                .migrate()
        }

    protected fun dataSource(
        poolName: String,
        schema: String? = null,
    ): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl =
                    postgres.getJdbcUrl() +
                    if (schema == null) "" else "&currentSchema=$schema"
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 2
                this.poolName = poolName
            },
        )

    companion object {
        private const val DEFAULT_SCHEMA = "voenix"

        @Container
        @JvmField
        val postgres: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
