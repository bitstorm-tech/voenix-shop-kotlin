package shop.voenix.testing

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@Testcontainers
public open class PostgresIntegrationTest {
    protected fun migratedDataSource(poolName: String): HikariDataSource =
        dataSource(poolName, DEFAULT_SCHEMA).also { dataSource ->
            Flyway.configure()
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
                    postgres.getJdbcUrl() + if (schema == null) "" else "&currentSchema=$schema"
                username = postgres.username
                password = postgres.password
                maximumPoolSize = MAXIMUM_POOL_SIZE
                connectionTimeout = CONNECTION_TIMEOUT_MILLIS
                this.poolName = poolName
            }
        )

    public companion object {
        private const val DEFAULT_SCHEMA = "voenix"

        /**
         * Room for every writer a concurrency test starts at once, plus the connection such a test
         * uses to watch them. A pool that is smaller than the writers turns a test into a Hikari
         * acquisition timeout that looks like a database failure of the code under test.
         */
        private const val MAXIMUM_POOL_SIZE = 8

        /**
         * Well below Hikari's 30 second default, and far above any acquisition a test may honestly
         * wait for, so pool pressure fails quickly instead of hiding in a slow run.
         */
        private const val CONNECTION_TIMEOUT_MILLIS = 10_000L

        @Container
        @JvmField
        protected val postgres: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }
}
