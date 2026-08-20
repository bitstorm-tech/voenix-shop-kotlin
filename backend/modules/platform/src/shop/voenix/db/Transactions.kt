package shop.voenix.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * Runs [operation] in one read-only transaction on this database.
 *
 * Every repository read in this backend wants the same three things, and this helper is where they
 * live so that no repository has to repeat them:
 * - `withContext(Dispatchers.IO)`, because the JDBC driver blocks the thread it runs on while it
 *   talks to PostgreSQL. Moving that work to the I/O dispatcher keeps the threads that serve HTTP
 *   requests free. `suspend` does not make the query parallel; it lets the calling coroutine wait
 *   without holding a request thread.
 * - `readOnly = true`, which tells PostgreSQL that this transaction will not write. The database
 *   then rejects an accidental write instead of performing it.
 * - `maxAttempts = 1`, which switches Exposed's automatic retry off. One repository call therefore
 *   has exactly one observable result — a query is never silently run twice.
 *
 * Use it like a block of code that happens to run in a transaction:
 * ```kotlin
 * internal suspend fun list(): List<Country> = database.read {
 *     Countries.selectAll().map(::toCountry)
 * }
 * ```
 *
 * A module that needs a *different* policy keeps that policy in its own repository, next to the
 * reason for it. `VatRepository.serializableTransaction` is the one example: it asks for
 * serializable isolation and three attempts because it moves the default VAT entry. Such a helper
 * is a deliberate exception, not a second default.
 */
public suspend fun <T> Database.read(operation: suspend () -> T): T =
    withContext(Dispatchers.IO) {
        suspendTransaction(db = this@read, readOnly = true) {
            maxAttempts = 1
            operation()
        }
    }

/**
 * Runs [operation] in one writing transaction on this database.
 *
 * The writing counterpart of [read]: same `Dispatchers.IO` wrap and same `maxAttempts = 1`, but
 * without `readOnly`, so inserts, updates, and deletes are allowed. Everything the block does
 * commits together when the block returns, and is rolled back when it throws.
 *
 * ```kotlin
 * internal suspend fun delete(id: Long): Int = database.write {
 *     Countries.deleteWhere { Countries.id eq id }
 * }
 * ```
 *
 * [executePostgresWrite] is a separate decision and stays where the repository put it. Wrapping it
 * *around* `write` maps a constraint violation that PostgreSQL reports at commit time; calling it
 * *inside* `write` maps one reported while the statement runs. Both orders are in use on purpose,
 * so never move a call across this boundary while changing something else.
 *
 * A module needing a stronger policy writes its own helper; see [read] for the VAT example.
 */
public suspend fun <T> Database.write(operation: suspend () -> T): T =
    withContext(Dispatchers.IO) {
        suspendTransaction(db = this@write) {
            maxAttempts = 1
            operation()
        }
    }
