package shop.voenix.db

import java.sql.SQLException

internal object PostgresWrite {
    suspend fun <T : Any> writeOrConflict(
        conflict: T,
        operation: suspend () -> T,
    ): T = writeOrSqlState(UNIQUE_VIOLATION_SQL_STATE, conflict, operation)

    suspend fun <T : Any> writeOrForeignKeyViolation(
        foreignKeyViolation: T,
        operation: suspend () -> T,
    ): T = writeOrSqlState(FOREIGN_KEY_VIOLATION_SQL_STATE, foreignKeyViolation, operation)

    private suspend fun <T : Any> writeOrSqlState(
        sqlState: String,
        failure: T,
        operation: suspend () -> T,
    ): T =
        try {
            operation()
        } catch (exception: SQLException) {
            if (!exception.hasSqlState(sqlState)) throw exception
            failure
        }

    private fun SQLException.hasSqlState(sqlState: String): Boolean =
        generateSequence(this as Throwable?) { throwable -> throwable.cause }
            .filterIsInstance<SQLException>()
            .any { sqlException -> sqlException.sqlState == sqlState }

    private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
    private const val FOREIGN_KEY_VIOLATION_SQL_STATE = "23503"
}
