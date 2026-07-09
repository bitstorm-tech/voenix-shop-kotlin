package shop.voenix.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database

class DatabaseFactory(
    private val settings: DatabaseSettings,
) : AutoCloseable {
    private var dataSource: HikariDataSource? = null

    fun connectAndMigrate(): Database {
        val activeDataSource = dataSource()

        Flyway
            .configure()
            .dataSource(activeDataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        return Database.connect(datasource = activeDataSource)
    }

    override fun close() {
        dataSource?.close()
        dataSource = null
    }

    private fun dataSource(): HikariDataSource =
        dataSource ?: HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = settings.jdbcUrl
                username = settings.username
                password = settings.password
                maximumPoolSize = settings.maximumPoolSize
                poolName = settings.poolName
            },
        ).also {
            dataSource = it
        }
}
