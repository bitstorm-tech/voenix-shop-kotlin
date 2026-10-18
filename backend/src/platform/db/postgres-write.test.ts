import { describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { executePostgresWrite } from '@/platform/db/postgres-write'
import { testDatabase } from '@test-support/database'

const database = testDatabase()

/** What a promise rejected with. */
const rejection = (promise: Promise<unknown>): Promise<unknown> =>
  promise.then(
    () => {
      throw new Error('expected a rejection')
    },
    (error: unknown) => error,
  )

describe('executePostgresWrite', () => {
  test('a successful write returns its result', async () => {
    expect(
      await executePostgresWrite({ uniqueViolation: 'conflict' }, () => Promise.resolve('stored')),
    ).toBe('stored')
  })

  test('a unique violation returns the declared result', async () => {
    const result = await executePostgresWrite({ uniqueViolation: 'conflict' }, () =>
      sql`INSERT INTO countries (name, country_code) VALUES ('Deutschland', 'DE')`.execute(
        database.db,
      ),
    )
    expect(result).toBe('conflict')
  })

  test('a foreign-key violation returns the declared result', async () => {
    const result = await executePostgresWrite({ foreignKeyViolation: 'countryMissing' }, () =>
      sql`INSERT INTO suppliers (name, country_id) VALUES ('Printer', 999)`.execute(database.db),
    )
    expect(result).toBe('countryMissing')
  })

  test('an undeclared violation and any other error keep the original exception', async () => {
    const foreignKey = executePostgresWrite({ uniqueViolation: 'conflict' }, () =>
      sql`INSERT INTO suppliers (name, country_id) VALUES ('Printer', 999)`.execute(database.db),
    )
    expect(await rejection(foreignKey)).toMatchObject({ code: '23503' })
    const syntax = executePostgresWrite(
      { uniqueViolation: 'conflict', foreignKeyViolation: 'missing' },
      () => sql`SELECT * FROM no_such_table`.execute(database.db),
    )
    expect(await rejection(syntax)).toMatchObject({ code: '42P01' })
  })
})
