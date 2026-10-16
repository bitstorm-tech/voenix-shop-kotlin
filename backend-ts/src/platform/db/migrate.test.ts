import { describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { migrate } from '@/platform/db/migrate'
import { testDatabase } from '@test-support/database'

const database = testDatabase({ template: false })

describe('migrate', () => {
  test('applies every file in version order to an empty database and nothing on a second run', async () => {
    const applied = await migrate(database.settings)
    expect(applied).toHaveLength(31)
    expect(applied[0]).toBe('V1__create_countries.sql')
    expect(applied[1]).toBe('V2__create_value_added_taxes.sql')
    expect(applied.at(-1)).toBe('V32__email_jobs_payload.sql')

    expect(await migrate(database.settings)).toEqual([])

    const recorded = await database.db
      .selectFrom('schema_migrations')
      .select('version')
      .orderBy('version')
      .execute()
    expect(recorded).toHaveLength(31)
    expect(recorded.at(-1)).toEqual({ version: 32 })
    const seeded = await sql<{
      count: number
    }>`SELECT count(*)::int AS count FROM countries`.execute(database.db)
    expect(seeded.rows[0]).toEqual({ count: 8 })
  })
})
