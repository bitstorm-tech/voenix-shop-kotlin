import { afterAll, beforeAll, beforeEach, describe, expect, test } from 'bun:test'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { sql } from 'kysely'
import { createImageModule } from '@/modules/image'
import { createPricingModule } from '@/modules/pricing'
import { createPromptModule } from '@/modules/prompt'
import { testDatabase } from '@test-support/database'
import { jsonOf, signedInHeaders, testApp, testAuth } from '@test-support/http'

const database = testDatabase()
let directory: string
beforeAll(async () => {
  directory = await mkdtemp(join(tmpdir(), 'prompt-categories-'))
})
afterAll(() => rm(directory, { recursive: true, force: true }))

const app = () => {
  const images = createImageModule({
    settings: {
      publicRoot: join(directory, 'public'),
      privateRoot: join(directory, 'private'),
      cacheRoot: join(directory, 'cache'),
    },
    guestTokens: testAuth.guestTokens,
  })
  const { priceCatalog } = createPricingModule({ db: database.db })
  return testApp(
    createPromptModule({ db: database.db, priceCatalog, exampleImages: images.exampleImages })
      .routes,
  )
}

const call = async (method: string, path: string, body?: unknown) =>
  app().request(`/api/admin/prompts${path}`, {
    method,
    headers: { ...(await signedInHeaders()), 'content-type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })

const created = async (path: string, body: unknown) => {
  const response = await call('POST', path, body)
  expect(response.status).toBe(201)
  return (await jsonOf(response)) as { id: number }
}

const insertPrompt = (categoryId: number, subcategoryId: number | null) =>
  sql`INSERT INTO prompts (position, title, prompt_text, category_id, subcategory_id, active, archived)
    VALUES ((SELECT coalesce(max(position), 0) + 1 FROM prompts), 'P', 'text', ${categoryId},
      ${subcategoryId}, true, false)`.execute(database.db)

/** Resolves once `count` sessions of this database wait for a lock. */
async function lockWaiters(count: number): Promise<void> {
  for (;;) {
    const { rows } = await sql<{ waiting: number }>`SELECT count(*)::int AS waiting
      FROM pg_stat_activity
      WHERE datname = current_database() AND wait_event_type = 'Lock'`.execute(database.db)
    if ((rows[0]?.waiting ?? 0) >= count) return
    await new Promise((resolve) => setTimeout(resolve, 20))
  }
}

const names = (body: unknown) => (body as { name: string }[]).map((row) => row.name)

beforeEach(async () => {
  await sql`TRUNCATE prompt_categories, prompt_subcategories, prompts RESTART IDENTITY CASCADE`.execute(
    database.db,
  )
})

describe('the category routes', () => {
  test('create appends with a Location header; list, get, update, and delete answer the documented shapes', async () => {
    const response = await call('POST', '/categories', { name: ' Portraits ' })
    expect(response.status).toBe(201)
    expect(response.headers.get('Location')).toBe('/api/admin/prompts/categories/1')
    expect(await jsonOf(response)).toEqual({ id: 1, name: 'Portraits', position: 1, active: true })
    await created('/categories', { name: 'Animals', active: false })
    expect(await jsonOf(await call('GET', '/categories'))).toEqual([
      { id: 1, name: 'Portraits', position: 1, active: true },
      { id: 2, name: 'Animals', position: 2, active: false },
    ])
    expect(await jsonOf(await call('GET', '/categories/2'))).toEqual({
      id: 2,
      name: 'Animals',
      position: 2,
      active: false,
    })
    expect(
      await jsonOf(await call('PUT', '/categories/2', { name: 'Pets', active: true })),
    ).toEqual({ id: 2, name: 'Pets', position: 2, active: true })
    const deleted = await call('DELETE', '/categories/2')
    expect(deleted.status).toBe(204)
    expect(await deleted.text()).toBe('')
  })

  test('a delete closes the gap it leaves', async () => {
    for (const name of ['A', 'B', 'C']) await created('/categories', { name })
    expect((await call('DELETE', '/categories/1')).status).toBe(204)
    expect(await jsonOf(await call('GET', '/categories'))).toEqual([
      { id: 2, name: 'B', position: 1, active: true },
      { id: 3, name: 'C', position: 2, active: true },
    ])
  })

  test('PUT /order moves the source to the target place and answers the complete new order', async () => {
    for (const name of ['A', 'B', 'C', 'D']) await created('/categories', { name })
    const response = await call('PUT', '/categories/order', { sourceId: 4, targetId: 2 })
    expect(response.status).toBe(200)
    const order = await jsonOf(response)
    expect(order).toEqual([
      { id: 1, name: 'A', position: 1, active: true },
      { id: 4, name: 'D', position: 2, active: true },
      { id: 2, name: 'B', position: 3, active: true },
      { id: 3, name: 'C', position: 4, active: true },
    ])
    expect(await jsonOf(await call('GET', '/categories'))).toEqual(order)
    const unknown = await call('PUT', '/categories/order', { sourceId: 1, targetId: 99 })
    expect(unknown.status).toBe(404)
    expect(await jsonOf(unknown)).toEqual({ message: 'Prompt category not found', errors: {} })
  })

  test('a duplicate name in any case is 409, a used category cannot be deleted, unknown ids are 404', async () => {
    await created('/categories', { name: 'Portraits' })
    const duplicate = await call('POST', '/categories', { name: 'PORTRAITS' })
    expect(duplicate.status).toBe(409)
    expect(await jsonOf(duplicate)).toEqual({
      message: 'Prompt category name already exists',
      errors: {},
    })
    const animals = await created('/categories', { name: 'Animals' })
    expect(
      (await call('PUT', `/categories/${String(animals.id)}`, { name: 'portraits' })).status,
    ).toBe(409)

    await created('/subcategories', { categoryId: 1, name: 'Adults' })
    const inUse = await call('DELETE', '/categories/1')
    expect(inUse.status).toBe(409)
    expect(await jsonOf(inUse)).toEqual({
      message: 'Prompt category is used by subcategories or prompts and cannot be deleted',
      errors: {},
    })
    await insertPrompt(animals.id, null)
    expect((await call('DELETE', `/categories/${String(animals.id)}`)).status).toBe(409)

    expect((await call('GET', '/categories/99')).status).toBe(404)
    expect((await call('PUT', '/categories/99', { name: 'X' })).status).toBe(404)
    expect((await call('DELETE', '/categories/99')).status).toBe(404)
    expect(await jsonOf(await call('GET', '/categories/x'))).toEqual({
      message: 'Invalid prompt category id',
      errors: {},
    })
  })

  test('concurrent creates append 1..n, and of two case variants of one name exactly one is stored', async () => {
    const appended = await Promise.all(
      ['A', 'B', 'C', 'D'].map((name) => call('POST', '/categories', { name })),
    )
    expect(appended.map((response) => response.status)).toEqual([201, 201, 201, 201])
    const listed = (await jsonOf(await call('GET', '/categories'))) as { position: number }[]
    expect(listed.map((category) => category.position)).toEqual([1, 2, 3, 4])
    const raced = await Promise.all([
      call('POST', '/categories', { name: 'Pets' }),
      call('POST', '/categories', { name: 'PETS' }),
    ])
    expect(raced.map((response) => response.status).sort()).toEqual([201, 409])
  })

  test('two concurrent reorders serialize and leave a dense order', async () => {
    for (const name of ['A', 'B', 'C', 'D']) await created('/categories', { name })
    const results = await Promise.all([
      call('PUT', '/categories/order', { sourceId: 1, targetId: 4 }),
      call('PUT', '/categories/order', { sourceId: 2, targetId: 3 }),
    ])
    expect(results.map((response) => response.status)).toEqual([200, 200])
    const listed = (await jsonOf(await call('GET', '/categories'))) as { position: number }[]
    expect(listed.map((category) => category.position)).toEqual([1, 2, 3, 4])
  })

  test('a gapped order is refused without writing, and a position committed outside the anchor loses the reorder at COMMIT', async () => {
    for (const name of ['A', 'B', 'C']) await created('/categories', { name })
    await sql`UPDATE prompt_categories SET position = 5 WHERE id = 3`.execute(database.db)
    const gapped = await call('PUT', '/categories/order', { sourceId: 1, targetId: 2 })
    expect(gapped.status).toBe(409)
    expect(await jsonOf(gapped)).toEqual({
      message: 'Prompt category order changed concurrently, please retry',
      errors: {},
    })
    expect(names(await jsonOf(await call('GET', '/categories')))).toEqual(['A', 'B', 'C'])
    await sql`UPDATE prompt_categories SET position = 3 WHERE id = 3`.execute(database.db)

    // A writer that ignores the anchor swaps A and C and holds both rows while the reorder waits.
    const rotation = await database.db.startTransaction().execute()
    await rotation
      .updateTable('prompt_categories')
      .set({ position: 3 })
      .where('id', '=', 1)
      .execute()
    await rotation
      .updateTable('prompt_categories')
      .set({ position: 1 })
      .where('id', '=', 3)
      .execute()
    const reorder = call('PUT', '/categories/order', { sourceId: 2, targetId: 1 })
    await lockWaiters(1)
    await rotation.commit().execute()
    expect((await reorder).status).toBe(409)
    expect(names(await jsonOf(await call('GET', '/categories')))).toEqual(['C', 'B', 'A'])
  })
})

describe('the subcategory routes', () => {
  beforeEach(async () => {
    await created('/categories', { name: 'Portraits' })
    await created('/categories', { name: 'Animals' })
  })

  test('create appends per category; list in category order; get, update, and delete answer the documented shapes', async () => {
    const response = await call('POST', '/subcategories', {
      categoryId: 2,
      name: ' Dogs ',
      description: '  ',
    })
    expect(response.status).toBe(201)
    expect(response.headers.get('Location')).toBe('/api/admin/prompts/subcategories/1')
    expect(await jsonOf(response)).toEqual({
      id: 1,
      categoryId: 2,
      name: 'Dogs',
      description: null,
      position: 1,
      active: true,
    })
    await created('/subcategories', { categoryId: 1, name: 'Adults', description: 'Grown-ups' })
    await created('/subcategories', { categoryId: 2, name: 'Cats', active: false })
    expect(await jsonOf(await call('GET', '/subcategories'))).toEqual([
      { id: 2, categoryId: 1, name: 'Adults', description: 'Grown-ups', position: 1, active: true },
      { id: 1, categoryId: 2, name: 'Dogs', description: null, position: 1, active: true },
      { id: 3, categoryId: 2, name: 'Cats', description: null, position: 2, active: false },
    ])
    expect(await jsonOf(await call('GET', '/subcategories/3'))).toMatchObject({ name: 'Cats' })
    expect(
      await jsonOf(
        await call('PUT', '/subcategories/3', { categoryId: 2, name: 'Kittens', active: true }),
      ),
    ).toEqual({
      id: 3,
      categoryId: 2,
      name: 'Kittens',
      description: null,
      position: 2,
      active: true,
    })
    expect((await call('DELETE', '/subcategories/1')).status).toBe(204)
    expect(await jsonOf(await call('GET', '/subcategories/3'))).toMatchObject({ position: 1 })
  })

  test('a move appends in the target category and compacts the source', async () => {
    for (const name of ['Dogs', 'Cats', 'Birds']) {
      await created('/subcategories', { categoryId: 2, name })
    }
    await created('/subcategories', { categoryId: 1, name: 'Adults' })
    expect(
      await jsonOf(await call('PUT', '/subcategories/1', { categoryId: 1, name: 'Dogs' })),
    ).toMatchObject({ categoryId: 1, position: 2 })
    expect(await jsonOf(await call('GET', '/subcategories'))).toMatchObject([
      { name: 'Adults', position: 1 },
      { name: 'Dogs', position: 2 },
      { name: 'Cats', position: 1 },
      { name: 'Birds', position: 2 },
    ])
  })

  test('PUT /order answers the new order of the affected category; a target in another category is 404', async () => {
    for (const name of ['Dogs', 'Cats', 'Birds']) {
      await created('/subcategories', { categoryId: 2, name })
    }
    await created('/subcategories', { categoryId: 1, name: 'Adults' })
    const response = await call('PUT', '/subcategories/order', { sourceId: 3, targetId: 1 })
    expect(response.status).toBe(200)
    expect(await jsonOf(response)).toEqual([
      { id: 3, categoryId: 2, name: 'Birds', description: null, position: 1, active: true },
      { id: 1, categoryId: 2, name: 'Dogs', description: null, position: 2, active: true },
      { id: 2, categoryId: 2, name: 'Cats', description: null, position: 3, active: true },
    ])
    const elsewhere = await call('PUT', '/subcategories/order', { sourceId: 3, targetId: 4 })
    expect(elsewhere.status).toBe(404)
    expect(await jsonOf(elsewhere)).toEqual({ message: 'Prompt subcategory not found', errors: {} })

    await sql`UPDATE prompt_subcategories SET position = 7 WHERE id = 2`.execute(database.db)
    const gapped = await call('PUT', '/subcategories/order', { sourceId: 3, targetId: 1 })
    expect(gapped.status).toBe(409)
    expect(await jsonOf(gapped)).toEqual({
      message: 'Prompt subcategory order changed concurrently, please retry',
      errors: {},
    })
  })

  test('names are unique per category, the category rejections are field errors, a used subcategory cannot be deleted', async () => {
    await created('/subcategories', { categoryId: 1, name: 'Adults' })
    const duplicate = await call('POST', '/subcategories', { categoryId: 1, name: 'ADULTS' })
    expect(duplicate.status).toBe(409)
    expect(await jsonOf(duplicate)).toEqual({
      message: 'Prompt subcategory name already exists in this prompt category',
      errors: {},
    })
    await created('/subcategories', { categoryId: 2, name: 'Adults' })

    const unknownCategory = await call('POST', '/subcategories', { categoryId: 99, name: 'X' })
    expect(unknownCategory.status).toBe(400)
    expect(await jsonOf(unknownCategory)).toEqual({
      message: 'Validation failed',
      errors: { categoryId: ['Prompt category does not exist'] },
    })
    expect(
      await jsonOf(await call('PUT', '/subcategories/1', { categoryId: 99, name: 'Adults' })),
    ).toEqual({
      message: 'Validation failed',
      errors: { categoryId: ['Prompt category does not exist'] },
    })

    await insertPrompt(1, 1)
    const used = await call('PUT', '/subcategories/1', { categoryId: 2, name: 'Grown-ups' })
    expect(used.status).toBe(400)
    expect(await jsonOf(used)).toEqual({
      message: 'Validation failed',
      errors: {
        categoryId: [
          'Prompt subcategory is used by prompts and cannot be moved to another category',
        ],
      },
    })
    const inUse = await call('DELETE', '/subcategories/1')
    expect(inUse.status).toBe(409)
    expect(await jsonOf(inUse)).toEqual({
      message: 'Prompt subcategory is used by prompts and cannot be deleted',
      errors: {},
    })
    expect((await call('GET', '/subcategories/99')).status).toBe(404)
    expect((await call('DELETE', '/subcategories/99')).status).toBe(404)
    expect(await jsonOf(await call('GET', '/subcategories/x'))).toEqual({
      message: 'Invalid prompt subcategory id',
      errors: {},
    })
  })

  test('concurrent creates in one category append 1..n, and of two case variants one is stored', async () => {
    const appended = await Promise.all(
      ['A', 'B', 'C'].map((name) => call('POST', '/subcategories', { categoryId: 1, name })),
    )
    expect(appended.map((response) => response.status)).toEqual([201, 201, 201])
    const raced = await Promise.all([
      call('POST', '/subcategories', { categoryId: 1, name: 'Teens' }),
      call('POST', '/subcategories', { categoryId: 1, name: 'TEENS' }),
    ])
    expect(raced.map((response) => response.status).sort()).toEqual([201, 409])
    const listed = (await jsonOf(await call('GET', '/subcategories'))) as { position: number }[]
    expect(listed.map((subcategory) => subcategory.position)).toEqual([1, 2, 3, 4])
  })

  test('a category reorder and a subcategory move across its rows do not deadlock', async () => {
    for (const name of ['C', 'D']) await created('/categories', { name })
    await created('/subcategories', { categoryId: 4, name: 'Moving' })
    const results = await Promise.all([
      call('PUT', '/categories/order', { sourceId: 4, targetId: 1 }),
      call('PUT', '/subcategories/1', { categoryId: 1, name: 'Moving' }),
      call('PUT', '/categories/order', { sourceId: 1, targetId: 3 }),
    ])
    expect(results.map((response) => response.status)).toEqual([200, 200, 200])
  })
})
