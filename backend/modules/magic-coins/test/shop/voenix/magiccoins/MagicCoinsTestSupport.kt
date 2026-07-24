package shop.voenix.magiccoins

import com.zaxxer.hikari.HikariDataSource

internal object MagicCoinsTestSupport {
    fun truncateMagicCoins(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "TRUNCATE voenix.users, voenix.magic_coins RESTART IDENTITY CASCADE"
                )
            }
        }
    }

    /**
     * Since the Account migration, `magic_coins.user_id` references `users.id`, so a test that
     * stores a user-owned balance needs the user row first.
     */
    fun seedUser(
        dataSource: HikariDataSource,
        id: Long,
    ) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO voenix.users (id, email, password_hash) " +
                        "VALUES ($id, 'user-$id@example.com', 'hash') " +
                        "ON CONFLICT DO NOTHING"
                )
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
