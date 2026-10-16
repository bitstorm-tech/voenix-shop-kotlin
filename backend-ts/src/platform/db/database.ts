import { Kysely, PostgresDialect, type Transaction } from 'kysely'
import { Pool, types } from 'pg'

/**
 * Every table of the application, keyed by name, as Kysely types queries against it. The platform
 * owns `schema_migrations`; each module adds the tables it owns from its `schema.ts` with a
 * `declare module '@/platform/db/database'` augmentation, so one `Kysely<Tables>` serves every
 * module and a transaction can be handed from one module to another. Kysely's type is invariant in
 * its table type, which is why there is one merged interface instead of one per module. A module
 * still queries only the tables its own `schema.ts` declares; that is a rule of the review, not of
 * the compiler (docs/dev/backend-ts/conventions/module-architecture.md).
 */
export interface Tables {
  schema_migrations: {
    version: number
    name: string
    applied_at: Date
  }
}

export interface DatabaseSettings {
  host: string
  port: number
  name: string
  username: string
  password: string
  searchPath: string
  sslMode: 'disable' | 'require' | 'verify-ca' | 'verify-full'
  maximumPoolSize: number
}

// Every `bigint` column (ids, counts) arrives as a JavaScript number instead of pg's default
// string; the schema never stores values beyond 2^53. `numeric` columns stay strings and are
// parsed once in the owning repository.
types.setTypeParser(types.builtins.INT8, Number)

/**
 * The PostgreSQL bounds every pooled connection runs under, sent in the startup packet so they are
 * session defaults from the connection's first statement on. `lock_timeout` bounds the wait for a
 * row or table lock: an insert queuing behind an uncommitted competitor on a unique index would
 * otherwise wait as long as that competitor's transaction lives. `statement_timeout` bounds one
 * statement's own execution. The migration runner deliberately does not run under them.
 */
const CONNECTION_OPTIONS = '-c lock_timeout=10s -c statement_timeout=30s'

/** The one connection pool every module works on, wrapped in Kysely. `db.destroy()` ends the pool. */
export function createDatabase(settings: DatabaseSettings): Kysely<Tables> {
  const pool = new Pool({
    host: settings.host,
    port: settings.port,
    database: settings.name,
    user: settings.username,
    password: settings.password,
    max: settings.maximumPoolSize,
    options: `${CONNECTION_OPTIONS} -c search_path=${settings.searchPath}`,
    ssl: sslOption(settings.sslMode),
  })
  return new Kysely<Tables>({ dialect: new PostgresDialect({ pool }) })
}

export function sslOption(
  mode: DatabaseSettings['sslMode'],
): boolean | { rejectUnauthorized: boolean } {
  switch (mode) {
    case 'disable':
      return false
    case 'require':
      return { rejectUnauthorized: false }
    case 'verify-ca':
    case 'verify-full':
      return true
  }
}

/**
 * Runs `operation` in one read-only transaction: PostgreSQL rejects an accidental write instead of
 * performing it. There is no retry; one repository call has exactly one observable result.
 */
export function read<DB, T>(
  db: Kysely<DB>,
  operation: (trx: Transaction<DB>) => Promise<T>,
): Promise<T> {
  return db.transaction().setAccessMode('read only').execute(operation)
}

/**
 * Runs `operation` in one writing transaction. Everything the callback does commits together when
 * it returns and is rolled back when it throws. A capability that must join the caller's
 * transaction takes the `trx` as a parameter instead of calling this.
 */
export function write<DB, T>(
  db: Kysely<DB>,
  operation: (trx: Transaction<DB>) => Promise<T>,
): Promise<T> {
  return db.transaction().execute(operation)
}
