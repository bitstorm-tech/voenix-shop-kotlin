const UNIQUE_VIOLATION = '23505'
// A missing referenced row reports 23503. Since PostgreSQL 18, a delete that an ON DELETE RESTRICT
// foreign key blocks reports 23001 (restrict_violation).
const FOREIGN_KEY_VIOLATIONS = ['23503', '23001']

/**
 * Runs a write and maps the constraint violations the repository declared to the value it wants
 * for them (usually an `OperationResult` failure such as `conflict`), letting PostgreSQL stay the
 * concurrency-safe authority for unique business rules. SQLSTATE `23505` becomes
 * `uniqueViolation`; a foreign-key violation becomes `foreignKeyViolation`, which is only
 * unambiguous when exactly one relationship can fail the write. Every undeclared state is
 * rethrown. Never derive a result from a constraint name or a message
 * (docs/dev/backend/conventions/persistence-error-handling.md).
 */
export async function executePostgresWrite<T, const V>(
  violations: { uniqueViolation?: V; foreignKeyViolation?: V },
  operation: () => Promise<T>,
): Promise<T | V> {
  try {
    return await operation()
  } catch (error) {
    const state = sqlState(error)
    if (state === UNIQUE_VIOLATION && violations.uniqueViolation !== undefined) {
      return violations.uniqueViolation
    }
    if (
      state !== undefined &&
      FOREIGN_KEY_VIOLATIONS.includes(state) &&
      violations.foreignKeyViolation !== undefined
    ) {
      return violations.foreignKeyViolation
    }
    throw error
  }
}

/** The SQLSTATE of a `pg` driver error, which carries it as `code`. */
function sqlState(error: unknown): string | undefined {
  return typeof error === 'object' &&
    error !== null &&
    'code' in error &&
    typeof error.code === 'string'
    ? error.code
    : undefined
}
