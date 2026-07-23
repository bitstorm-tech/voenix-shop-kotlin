package shop.voenix.magiccoins

import com.zaxxer.hikari.HikariDataSource

internal object MagicCoinsTestSupport {
    fun truncateMagicCoins(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE voenix.magic_coins RESTART IDENTITY")
            }
        }
    }

    fun count(
        dataSource: HikariDataSource,
        sql: String,
    ): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.next())
                    rows.getInt(1)
                }
            }
        }
}
