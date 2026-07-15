package shop.voenix.db

import java.sql.SQLException

public suspend fun <T : Any> executePostgresWrite(
    uniqueViolation: T? = null,
    foreignKeyViolation: T? = null,
    operation: suspend () -> T,
): T = PostgresWrite.execute(uniqueViolation, foreignKeyViolation, operation)

private object PostgresWrite {
    suspend fun <T : Any> execute(
        uniqueViolation: T?,
        foreignKeyViolation: T?,
        operation: suspend () -> T,
    ): T =
        try {
            operation()
        } catch (exception: SQLException) {
            when {
                exception.hasSqlState(UNIQUE_VIOLATION_SQL_STATE) && uniqueViolation != null ->
                    uniqueViolation
                exception.hasSqlState(FOREIGN_KEY_VIOLATION_SQL_STATE) &&
                    foreignKeyViolation != null -> foreignKeyViolation
                else -> throw exception
            }
        }

    private fun SQLException.hasSqlState(sqlState: String): Boolean =
        generateSequence(this as Throwable?) { throwable -> throwable.cause }
            .filterIsInstance<SQLException>()
            .any { sqlException -> sqlException.sqlState == sqlState }

    private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
    private const val FOREIGN_KEY_VIOLATION_SQL_STATE = "23503"
}
