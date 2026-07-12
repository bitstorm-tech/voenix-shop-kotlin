package shop.voenix.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.DriverManager
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.jetbrains.exposed.v1.jdbc.Database

class DatabaseFactory(private val settings: DatabaseSettings) : AutoCloseable {
    private var dataSource: HikariDataSource? = null

    fun connectAndMigrate(): Database {
        val activeDataSource = dataSource()
        val flyway =
            Flyway.configure()
                .dataSource(activeDataSource)
                .locations("classpath:db/migration")
                .defaultSchema(settings.searchPath)
                .schemas(settings.searchPath)
                .baselineVersion(MigrationVersion.fromVersion("1"))
                .baselineDescription("Existing EF-compatible Country schema")
                .load()

        withMigrationLock {
            if (CountrySchemaCompatibility.requiresBaseline(activeDataSource)) {
                CountrySchemaCompatibility.verify(activeDataSource)
                flyway.baseline()
            }
            flyway.migrate()
        }

        return Database.connect(datasource = activeDataSource)
    }

    override fun close() {
        dataSource?.close()
        dataSource = null
    }

    private fun dataSource(): HikariDataSource =
        dataSource
            ?: HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = settings.jdbcUrl
                        username = settings.username
                        password = settings.password
                        maximumPoolSize = settings.maximumPoolSize
                        minimumIdle = 0
                        poolName = settings.poolName
                    }
                )
                .also { dataSource = it }

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
    }
}
