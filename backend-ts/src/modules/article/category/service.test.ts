import { beforeEach, describe, expect, test } from 'bun:test'
import { sql, type Kysely } from 'kysely'
import type { Tables } from '@/platform/db/database'
import { testDatabase } from '@test-support/database'
import { insertCategory, insertSubcategory, resetArticles } from '../test-fixtures'
import { createCategoryRepository } from './repository'

/**
 * The ordering design under concurrency: the anchor serializes position writers, the unique name
 * index decides a race, a gapped sequence is refused, a position written outside the lock loses at
 * COMMIT, and the ascending row lock order keeps two cross-category moves free of deadlocks.
 */

const database = testDatabase()
const repository = () => createCategoryRepository(database.db)
beforeEach(() => resetArticles(database.db))

/** Waits until some statement of this database waits for a lock another transaction holds. */
async function untilBlocked(db: Kysely<Tables>, waiting = 1): Promise<void> {
  for (let attempt = 0; attempt < 200; attempt++) {
    const { rows } = await sql<{ blocked: number }>`
      SELECT count(*)::int AS blocked FROM pg_stat_activity
      WHERE datname = current_database() AND wait_event_type = 'Lock'`.execute(db)
    if ((rows[0]?.blocked ?? 0) >= waiting) return
    await Bun.sleep(10)
  }
  throw new Error('No statement started waiting for the held lock')
}

const positions = async (table: 'article_categories' | 'article_subcategories') =>
  (
    await database.db
      .selectFrom(table)
      .select(['id', 'position'])
      .orderBy('position')
      .orderBy('id')
      .execute()
  ).map(({ id, position }) => [id, position])

describe('categories', () => {
  test('concurrent case-variant creates leave one row and one conflict', async () => {
    const results = await Promise.all([
      repository().insertCategory({ name: 'Mugs', description: null, active: true }),
      repository().insertCategory({ name: 'MUGS', description: null, active: true }),
    ])
    expect(results.filter((result) => result === 'conflict')).toHaveLength(1)
    expect(await positions('article_categories')).toHaveLength(1)
  })

  test('concurrent creates and reorders keep the sequence dense', async () => {
    const ids = [
      await insertCategory(database.db, 'A'),
      await insertCategory(database.db, 'B'),
      await insertCategory(database.db, 'C'),
    ]
    const [first = 0, , third = 0] = ids
    const results = await Promise.all([
      repository().reorderCategories(third, first),
      repository().insertCategory({ name: 'D', description: null, active: true }),
      repository().reorderCategories(first, third),
      repository().insertCategory({ name: 'E', description: null, active: true }),
    ])
    expect(results.every((result) => result !== 'conflict' && result !== 'notFound')).toBe(true)
    expect((await positions('article_categories')).map(([, position]) => position)).toEqual([
      1, 2, 3, 4, 5,
    ])
  })

  test('a gapped sequence is refused and nothing moves', async () => {
    const first = await insertCategory(database.db, 'A')
    const second = await insertCategory(database.db, 'B')
    await sql`UPDATE article_categories SET position = 5 WHERE id = ${second}`.execute(database.db)
    expect(await repository().reorderCategories(second, first)).toBe('conflict')
    expect(await positions('article_categories')).toEqual([
      [first, 1],
      [second, 5],
    ])
  })

  test('a position written outside the anchor makes the reorder fail at COMMIT', async () => {
    const [a, b, c] = [
      await insertCategory(database.db, 'A'),
      await insertCategory(database.db, 'B'),
      await insertCategory(database.db, 'C'),
    ]
    // A writer that ignores the anchor rotates B and C while the reorder decides from the old order.
    const outsider = database.db.transaction().execute(async (trx) => {
      await sql`UPDATE article_categories SET position = 3 WHERE id = ${b}`.execute(trx)
      await sql`UPDATE article_categories SET position = 2 WHERE id = ${c}`.execute(trx)
      await untilBlocked(database.db)
    })
    const reorder = repository().reorderCategories(a, b)
    await outsider
    expect(await reorder).toBe('conflict')
    expect(await positions('article_categories')).toEqual([
      [a, 1],
      [c, 2],
      [b, 3],
    ])
  })
})

describe('subcategories', () => {
  test('concurrent case-variant creates in one category leave one row and one conflict', async () => {
    const mugs = await insertCategory(database.db, 'Mugs')
    const input = { categoryId: mugs, description: null, exampleImageFilename: null, active: true }
    const results = await Promise.all([
      repository().insertSubcategory({ ...input, name: 'Classic' }),
      repository().insertSubcategory({ ...input, name: 'classic' }),
    ])
    expect(results.filter((result) => result === 'conflict')).toHaveLength(1)
  })

  test('two moves in opposite directions both succeed under the ascending lock order', async () => {
    const mugs = await insertCategory(database.db, 'Mugs')
    const posters = await insertCategory(database.db, 'Posters')
    const classic = await insertSubcategory(database.db, mugs, 'Classic')
    const large = await insertSubcategory(database.db, posters, 'Large')
    const input = { description: null, exampleImageFilename: null, active: true }
    // Holding the first category makes both writers queue and then need each other's rows.
    const holder = database.db.transaction().execute(async (trx) => {
      await sql`SELECT id FROM article_categories WHERE id = ${mugs} FOR UPDATE`.execute(trx)
      await untilBlocked(database.db, 2)
    })
    const moves = Promise.all([
      repository().updateSubcategory(classic, { ...input, categoryId: posters, name: 'Classic' }),
      repository().updateSubcategory(large, { ...input, categoryId: mugs, name: 'Large' }),
    ])
    await holder
    const [moved, movedBack] = await moves
    expect(typeof moved === 'object' && moved.subcategory.categoryId).toBe(posters)
    expect(typeof movedBack === 'object' && movedBack.subcategory.categoryId).toBe(mugs)
  })

  test('a gapped sequence inside a category is refused', async () => {
    const mugs = await insertCategory(database.db, 'Mugs')
    const first = await insertSubcategory(database.db, mugs, 'First')
    const second = await insertSubcategory(database.db, mugs, 'Second')
    await sql`UPDATE article_subcategories SET position = 4 WHERE id = ${second}`.execute(
      database.db,
    )
    expect(await repository().reorderSubcategories(second, first)).toBe('conflict')
  })
})
