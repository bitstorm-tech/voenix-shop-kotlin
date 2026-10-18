import { readdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { Client } from 'pg'
import { sslOption, type DatabaseSettings } from '@/platform/db/database'

/** The same advisory lock id the Kotlin backend took, so two starting instances never migrate at once. */
const MIGRATION_LOCK_ID = '8661739632123244899'
const directory = fileURLToPath(new URL('../../../migrations/', import.meta.url))

/**
 * Applies the SQL files of `migrations/` that `schema_migrations` does not list yet, ordered by the
 * version in the file name (`V12__…` is version 12), each file in one transaction. Idempotent: a
 * second run applies nothing. It runs on a plain client without the pool's timeouts, because
 * creating an index on a large table may honestly take minutes. Returns the names it applied.
 */
export async function migrate(settings: DatabaseSettings): Promise<string[]> {
  const client = new Client({
    host: settings.host,
    port: settings.port,
    database: settings.name,
    user: settings.username,
    password: settings.password,
    ssl: sslOption(settings.sslMode),
  })
  await client.connect()
  try {
    await client.query('SELECT pg_advisory_lock($1::bigint)', [MIGRATION_LOCK_ID])
    try {
      await client.query(`CREATE SCHEMA IF NOT EXISTS ${settings.searchPath}`)
      await client.query(`SET search_path TO ${settings.searchPath}`)
      await client.query(
        `CREATE TABLE IF NOT EXISTS schema_migrations (
           version integer PRIMARY KEY,
           name text NOT NULL,
           applied_at timestamptz NOT NULL DEFAULT now()
         )`,
      )
      const applied = new Set(
        (await client.query<{ version: number }>('SELECT version FROM schema_migrations')).rows.map(
          (row) => row.version,
        ),
      )
      const appliedNow: string[] = []
      for (const file of migrationFiles()) {
        if (applied.has(file.version)) continue
        await client.query('BEGIN')
        try {
          await client.query(readFileSync(`${directory}${file.name}`, 'utf8'))
          await client.query('INSERT INTO schema_migrations (version, name) VALUES ($1, $2)', [
            file.version,
            file.name,
          ])
          await client.query('COMMIT')
        } catch (error) {
          await client.query('ROLLBACK')
          throw new Error(`Migration ${file.name} failed`, { cause: error })
        }
        appliedNow.push(file.name)
      }
      return appliedNow
    } finally {
      await client.query('SELECT pg_advisory_unlock($1::bigint)', [MIGRATION_LOCK_ID])
    }
  } finally {
    await client.end()
  }
}

function migrationFiles(): { version: number; name: string }[] {
  return readdirSync(directory)
    .flatMap((name) => {
      const version = /^V(\d+)__.+\.sql$/.exec(name)?.[1]
      return version === undefined ? [] : [{ version: Number(version), name }]
    })
    .sort((left, right) => left.version - right.version)
}
