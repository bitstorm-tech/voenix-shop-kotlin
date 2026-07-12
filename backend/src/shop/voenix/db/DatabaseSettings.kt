package shop.voenix.db

import io.ktor.server.config.ApplicationConfig
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class DatabaseSettings(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val maximumPoolSize: Int,
    val searchPath: String,
    val poolName: String = "voenix-shop-db",
) {
    companion object {
        fun from(config: ApplicationConfig): DatabaseSettings {
            fun value(
                name: String,
                default: String? = null,
            ): String? = config.propertyOrNull("Database.$name")?.getString() ?: default

            fun required(name: String): String =
                value(name)?.takeIf(String::isNotBlank)
                    ?: error("Missing required configuration value: Database.$name")

            fun valueOrDefault(
                name: String,
                default: String,
            ): String = value(name) ?: default

            val host = required("Host")
            val port = valueOrDefault("Port", "5432").toInt()
            val database = required("Database")
            val username = required("Username")
            val password = required("Password")
            val searchPath =
                value("SearchPath", "voenix")?.takeIf(String::isNotBlank)
                    ?: error("Missing required configuration value: Database.SearchPath")
            require(
                searchPath.length <= POSTGRESQL_IDENTIFIER_MAX_LENGTH &&
                    searchPath.matches(POSTGRESQL_SCHEMA_NAME)
            ) {
                "Database.SearchPath must contain one lowercase PostgreSQL identifier of at most 63 characters"
            }
            val sslMode = valueOrDefault("SslMode", "Disable").toJdbcSslMode()
            val maximumPoolSize = valueOrDefault("MaximumPoolSize", "100").toInt()
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
