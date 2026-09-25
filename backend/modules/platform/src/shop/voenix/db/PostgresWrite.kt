package shop.voenix.db

import java.sql.SQLException

public suspend fun <T : Any> executePostgresWrite(
    uniqueViolation: T? = null,
    foreignKeyViolation: T? = null,
    operation: suspend () -> T,
): T =
    try {
        operation()
    } catch (exception: SQLException) {
        when {
            exception.hasSqlState(UNIQUE_VIOLATION_SQL_STATE) && uniqueViolation != null ->
                uniqueViolation
            FOREIGN_KEY_VIOLATION_SQL_STATES.any(exception::hasSqlState) &&
                foreignKeyViolation != null -> foreignKeyViolation
            else -> throw exception
        }
    }

private fun SQLException.hasSqlState(sqlState: String): Boolean =
    generateSequence(this as Throwable?) { throwable -> throwable.cause }
        .filterIsInstance<SQLException>()
        .any { sqlException -> sqlException.sqlState == sqlState }

private const val UNIQUE_VIOLATION_SQL_STATE = "23505"

// A missing referenced row reports 23503. Since PostgreSQL 18, a delete that an
// ON DELETE RESTRICT foreign key blocks reports 23001 (restrict_violation).
private val FOREIGN_KEY_VIOLATION_SQL_STATES = listOf("23503", "23001")
