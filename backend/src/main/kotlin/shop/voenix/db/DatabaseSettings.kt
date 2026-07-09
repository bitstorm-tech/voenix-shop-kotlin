package shop.voenix.db

import io.ktor.server.config.ApplicationConfig

data class DatabaseSettings(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val maximumPoolSize: Int,
    val poolName: String = "voenix-shop-db",
) {
    companion object {
        fun from(config: ApplicationConfig): DatabaseSettings =
            DatabaseSettings(
                jdbcUrl = config.requiredString("database.jdbcUrl"),
                username = config.requiredString("database.username"),
                password = config.requiredString("database.password"),
                maximumPoolSize = config.requiredString("database.maximumPoolSize").toInt(),
            )
    }
}

private fun ApplicationConfig.requiredString(path: String): String = property(path).getString()
