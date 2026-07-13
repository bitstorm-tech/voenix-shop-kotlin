package shop.voenix.db

import java.sql.SQLException

internal object PostgresWrite {
    suspend fun <T : Any> writeOrConflict(
        conflict: T,
        operation: suspend () -> T,
    ): T =
        try {
            operation()
        } catch (exception: SQLException) {
            if (!exception.isUniqueViolation()) throw exception
            conflict
        }

    private fun SQLException.isUniqueViolation(): Boolean =
        generateSequence(this as Throwable?) { throwable -> throwable.cause }
            .filterIsInstance<SQLException>()
            .any { sqlException -> sqlException.sqlState == UNIQUE_VIOLATION_SQL_STATE }

    private const val UNIQUE_VIOLATION_SQL_STATE = "23505"
}
