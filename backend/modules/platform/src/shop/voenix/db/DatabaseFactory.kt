package shop.voenix.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.ApplicationConfig
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.DriverManager
import java.util.Locale
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Owns the application's database connections: the pool every module works on, and the Flyway
 * migration run that prepares the schema before the first module sees it.
 *
 * The two use deliberately different connections. Pooled connections carry the PostgreSQL timeouts
 * described at [CONNECTION_OPTIONS]; the migration run must not, because creating an index on a
 * large table is a single statement that may honestly take minutes.
 */
public class DatabaseFactory(private val settings: DatabaseSettings) : AutoCloseable {
    private val lazyDataSource = lazy {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = settings.jdbcUrl
                username = settings.username
                password = settings.password
                maximumPoolSize = settings.maximumPoolSize
                minimumIdle = 0
                poolName = settings.poolName
                // Every pooled connection starts with the PostgreSQL-side bounds. Hikari
                // hands its data source properties to the JDBC driver, and the driver sends
                // "options" in the startup packet, so the values are session defaults from
                // the connection's first statement on.
                addDataSourceProperty("options", CONNECTION_OPTIONS)
            }
        )
    }

    private val dataSource: HikariDataSource by lazyDataSource

    public fun connectAndMigrate(): Database {
        val activeDataSource = dataSource
        val flyway =
            Flyway.configure()
                // Deliberately not the pooled data source: Flyway opens its own connections from
                // the plain JDBC URL, so no migration statement runs under the pool's timeouts.
                .dataSource(settings.jdbcUrl, settings.username, settings.password)
                .locations("classpath:db/migration")
                .defaultSchema(settings.searchPath)
                .schemas(settings.searchPath)
                .load()

        withMigrationLock { flyway.migrate() }

        return Database.connect(datasource = activeDataSource)
    }

    /**
     * Closes the pool if one was ever opened. Create a new factory instead of reusing a closed one.
     */
    override fun close() {
        if (lazyDataSource.isInitialized()) {
            dataSource.close()
        }
    }

    private fun withMigrationLock(block: () -> Unit) {
        DriverManager.getConnection(settings.jdbcUrl, settings.username, settings.password).use {
            connection ->
            connection.prepareStatement("SELECT pg_advisory_lock(?)").use { statement ->
                statement.setLong(1, MIGRATION_LOCK_ID)
                statement.execute()
            }
            try {
                block()
            } finally {
                connection.prepareStatement("SELECT pg_advisory_unlock(?)").use { statement ->
                    statement.setLong(1, MIGRATION_LOCK_ID)
                    statement.execute()
                }
            }
        }
    }

    private companion object {
        const val MIGRATION_LOCK_ID = 8_661_739_632_123_244_899L

        /**
         * The PostgreSQL bounds every pooled connection runs under.
         *
         * `lock_timeout = 10s` bounds the wait for a row or table lock — the case that motivated
         * this setting is an insert queuing behind an uncommitted competitor on a unique index,
         * which otherwise waits for as long as that competitor's transaction lives.
         * `statement_timeout = 30s` bounds a single statement's own execution.
         *
         * Both are set in this one place and no module overrides them. When one fires, PostgreSQL
         * aborts the statement and the driver raises an `SQLException`, which travels the ordinary
         * unexpected-failure path: repositories rethrow undeclared SQL states, and
         * `Logger.databaseOperation` logs the exception and answers with the service's failure
         * result.
         */
        const val CONNECTION_OPTIONS = "-c lock_timeout=10s -c statement_timeout=30s"
    }
}

public data class DatabaseSettings(
    public val jdbcUrl: String,
    public val username: String,
    public val password: String,
    public val maximumPoolSize: Int,
    public val searchPath: String,
    public val poolName: String = "voenix-shop-db",
) {
    public companion object {
        public fun from(config: ApplicationConfig): DatabaseSettings {
            fun value(
                name: String,
                default: String? = null,
            ): String? = config.propertyOrNull("database.$name")?.getString() ?: default

            fun required(name: String): String =
                value(name)?.takeIf(String::isNotBlank)
                    ?: error("Missing required configuration value: database.$name")

            fun valueOrDefault(
                name: String,
                default: String,
            ): String = value(name) ?: default

            val host = required("host")
            val port = valueOrDefault("port", "5432").toInt()
            val database = required("database")
            val username = required("username")
            val password = required("password")
            val searchPath =
                value("searchPath", "voenix")?.takeIf(String::isNotBlank)
                    ?: error("Missing required configuration value: database.searchPath")
            require(
                searchPath.length <= POSTGRESQL_IDENTIFIER_MAX_LENGTH &&
                    searchPath.matches(POSTGRESQL_SCHEMA_NAME)
            ) {
                "database.searchPath must contain one lowercase PostgreSQL identifier of at most 63 characters"
            }
            val sslMode = valueOrDefault("sslMode", "Disable").toJdbcSslMode()
            val maximumPoolSize = valueOrDefault("maximumPoolSize", "100").toInt()
            val query = "currentSchema=${searchPath.urlEncode()}&sslmode=${sslMode.urlEncode()}"

            return DatabaseSettings(
                jdbcUrl = "jdbc:postgresql://$host:$port/${database.urlEncode()}?$query",
                username = username,
                password = password,
                maximumPoolSize = maximumPoolSize,
                searchPath = searchPath,
            )
        }

        private val POSTGRESQL_SCHEMA_NAME = Regex("[a-z_][a-z0-9_]*")
        private const val POSTGRESQL_IDENTIFIER_MAX_LENGTH = 63
    }
}

private fun String.toJdbcSslMode(): String =
    when (lowercase(Locale.ROOT)) {
        "verifyca",
        "verify-ca" -> "verify-ca"
        "verifyfull",
        "verify-full" -> "verify-full"
        else -> lowercase(Locale.ROOT)
    }

private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)
