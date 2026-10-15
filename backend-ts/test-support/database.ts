import { afterAll, beforeAll } from 'bun:test'
import { randomBytes } from 'node:crypto'
import type { Kysely } from 'kysely'
import { Client } from 'pg'
import { createDatabase, type DatabaseSettings, type Tables } from '@/platform/db/database'

/** The migrated database every test file copies; created by `preload.ts`. */
export const TEMPLATE_DATABASE = 'voenix_template'

export interface TestDatabase {
  /** The Kysely instance of this file's database; created in `beforeAll`, so use it inside a test. */
  readonly db: Kysely<Tables>
  /** The connection details, for code that opens its own connection (the migration runner). */
  readonly settings: DatabaseSettings
}

/**
 * Gives the calling test file a database of its own: a fresh copy of the migrated template in
 * `beforeAll`, dropped in `afterAll`. Tests of one file share it and never see another file's rows.
 * `{ template: false }` creates an empty database instead, for the migration runner's own tests.
 */
export function testDatabase(options: { template?: boolean } = {}): TestDatabase {
  const settings: DatabaseSettings = {
    host: testEnv('TEST_DATABASE_HOST'),
    port: Number(testEnv('TEST_DATABASE_PORT')),
    name: `voenix_test_${randomBytes(4).toString('hex')}`,
    username: testEnv('TEST_DATABASE_USERNAME'),
    password: testEnv('TEST_DATABASE_PASSWORD'),
    searchPath: 'voenix',
    sslMode: 'disable',
    // Room for every writer a concurrency test starts at once, plus the one watching them.
    maximumPoolSize: 8,
  }
  let db: Kysely<Tables> | undefined

  beforeAll(async () => {
    const template = options.template === false ? '' : ` TEMPLATE ${TEMPLATE_DATABASE}`
    await onServer(`CREATE DATABASE ${settings.name}${template}`)
    db = createDatabase(settings)
  })
  afterAll(async () => {
    await db?.destroy()
    await onServer(`DROP DATABASE IF EXISTS ${settings.name} WITH (FORCE)`)
  })

  return {
    get db() {
      if (db === undefined) throw new Error('The test database exists only inside a test')
      return db
    },
    settings,
  }
}

/** Runs one statement on the container's own database, which is never copied or dropped. */
async function onServer(statement: string): Promise<void> {
  const client = new Client({
    host: testEnv('TEST_DATABASE_HOST'),
    port: Number(testEnv('TEST_DATABASE_PORT')),
    user: testEnv('TEST_DATABASE_USERNAME'),
    password: testEnv('TEST_DATABASE_PASSWORD'),
    database: testEnv('TEST_DATABASE_SERVER_NAME'),
  })
  await client.connect()
  try {
    await client.query(statement)
  } finally {
    await client.end()
  }
}

function testEnv(name: string): string {
  const value = process.env[name]
  if (value === undefined) {
    throw new Error(
      `${name} is not set: run the tests with \`bun test\`, whose preload starts PostgreSQL`,
    )
  }
  return value
}
