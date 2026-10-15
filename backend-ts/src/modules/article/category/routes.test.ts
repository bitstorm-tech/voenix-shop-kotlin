import { beforeAll, beforeEach, describe, expect, test } from 'bun:test'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import type { Hono } from 'hono'
import { testDatabase } from '@test-support/database'
import { jsonOf, signedInHeaders, testApp, testAuth } from '@test-support/http'
import { pngBytes } from '@test-support/values'
import {
  articleTestModule,
  insertCategory,
  insertSubcategory,
  resetArticles,
} from '../test-fixtures'

const database = testDatabase()
let app: Hono
let publicRoot: string

beforeAll(async () => {
  const fixture = await articleTestModule(database.db, testAuth.guestTokens)
  app = testApp(fixture.article.routes)
  publicRoot = fixture.publicRoot
})
beforeEach(() => resetArticles(database.db))

const send = async (method: string, path: string, body?: unknown) =>
  app.request(path, {
    method,
    headers: { ...(await signedInHeaders()), 'content-type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })

const CATEGORIES = '/api/admin/articles/categories'
const SUBCATEGORIES = '/api/admin/articles/subcategories'

const uploadExample = async () => {
  const form = new FormData()
  form.append('file', new Blob([await pngBytes()], { type: 'image/png' }), 'example.png')
  return app.request(`${SUBCATEGORIES}/example-images`, {
    method: 'POST',
    headers: await signedInHeaders(),
    body: form,
  })
}

describe('categories', () => {
  test('POST appends a trimmed category and answers 201 with a Location header', async () => {
    await insertCategory(database.db, 'Posters')
    const created = await send('POST', CATEGORIES, { name: ' Mugs ', description: ' Drink ' })
    expect(created.status).toBe(201)
    const body = (await jsonOf(created)) as Record<string, unknown> & { id: number }
    expect(body).toEqual({
      id: body.id,
      name: 'Mugs',
      description: 'Drink',
      position: 2,
      active: true,
    })
    expect(created.headers.get('Location')).toBe(`${CATEGORIES}/${String(body.id)}`)
  })

  test('GET lists in display order and reads one; an unknown id is 404, a malformed one 400', async () => {
    const first = await insertCategory(database.db, 'Mugs')
    await insertCategory(database.db, 'Posters')
    const list = await send('GET', CATEGORIES)
    expect(((await jsonOf(list)) as { name: string }[]).map((category) => category.name)).toEqual([
      'Mugs',
      'Posters',
    ])
    expect(await jsonOf(await send('GET', `${CATEGORIES}/${String(first)}`))).toEqual({
      id: first,
      name: 'Mugs',
      description: null,
      position: 1,
      active: true,
    })
    const missing = await send('GET', `${CATEGORIES}/999`)
    expect(missing.status).toBe(404)
    expect(await jsonOf(missing)).toEqual({ message: 'Article category not found', errors: {} })
    const malformed = await send('GET', `${CATEGORIES}/abc`)
    expect(await jsonOf(malformed)).toEqual({ message: 'Invalid article category id', errors: {} })
  })

  test('PUT replaces name, description, and activation; a case-variant duplicate name is 409', async () => {
    const mugs = await insertCategory(database.db, 'Mugs')
    const posters = await insertCategory(database.db, 'Posters')
    const updated = await send('PUT', `${CATEGORIES}/${String(posters)}`, {
      name: 'Prints',
      active: false,
    })
    expect(await jsonOf(updated)).toEqual({
      id: posters,
      name: 'Prints',
      description: null,
      position: 2,
      active: false,
    })
    const duplicate = await send('PUT', `${CATEGORIES}/${String(posters)}`, { name: 'MUGS' })
    expect(duplicate.status).toBe(409)
    expect(await jsonOf(duplicate)).toEqual({
      message: 'Article category name already exists',
      errors: {},
    })
    expect((await send('POST', CATEGORIES, { name: 'mugs' })).status).toBe(409)
    expect((await send('PUT', `${CATEGORIES}/999`, { name: 'X' })).status).toBe(404)
    expect(mugs).toBe(1)
  })

  test('DELETE closes the gap; a category in use answers 409', async () => {
    const mugs = await insertCategory(database.db, 'Mugs')
    const posters = await insertCategory(database.db, 'Posters')
    const shirts = await insertCategory(database.db, 'Shirts')
    await insertSubcategory(database.db, shirts, 'Classic')
    const deleted = await send('DELETE', `${CATEGORIES}/${String(mugs)}`)
    expect(deleted.status).toBe(204)
    expect(await deleted.text()).toBe('')
    const list = (await jsonOf(await send('GET', CATEGORIES))) as { id: number; position: number }[]
    expect(list.map(({ id, position }) => [id, position])).toEqual([
      [posters, 1],
      [shirts, 2],
    ])
    const inUse = await send('DELETE', `${CATEGORIES}/${String(shirts)}`)
    expect(inUse.status).toBe(409)
    expect(await jsonOf(inUse)).toEqual({
      message: 'Article category is used by subcategories or articles and cannot be deleted',
      errors: {},
    })
    expect((await send('DELETE', `${CATEGORIES}/${String(mugs)}`)).status).toBe(404)
  })

  test('PUT /order moves one category and answers the complete dense order', async () => {
    const first = await insertCategory(database.db, 'First')
    const second = await insertCategory(database.db, 'Second')
    const third = await insertCategory(database.db, 'Third')
    const moved = await send('PUT', `${CATEGORIES}/order`, { sourceId: third, targetId: first })
    expect(moved.status).toBe(200)
    expect(
      ((await jsonOf(moved)) as { id: number; position: number }[]).map(({ id, position }) => [
        id,
        position,
      ]),
    ).toEqual([
      [third, 1],
      [first, 2],
      [second, 3],
    ])
    const unknown = await send('PUT', `${CATEGORIES}/order`, { sourceId: first, targetId: 999 })
    expect(unknown.status).toBe(404)
  })
})

describe('subcategories', () => {
  test('POST appends per category; an unknown category is a categoryId field error', async () => {
    const mugs = await insertCategory(database.db, 'Mugs')
    await insertSubcategory(database.db, mugs, 'Classic')
    const created = await send('POST', SUBCATEGORIES, { categoryId: mugs, name: ' Tall ' })
    expect(created.status).toBe(201)
    const body = (await jsonOf(created)) as Record<string, unknown> & { id: number }
    expect(body).toEqual({
      id: body.id,
      categoryId: mugs,
      name: 'Tall',
      description: null,
      exampleImageFilename: null,
      position: 2,
      active: true,
    })
    expect(created.headers.get('Location')).toBe(`${SUBCATEGORIES}/${String(body.id)}`)
    const unknown = await send('POST', SUBCATEGORIES, { categoryId: 999, name: 'X' })
    expect(unknown.status).toBe(400)
    expect(await jsonOf(unknown)).toEqual({
      message: 'Validation failed',
      errors: { categoryId: ['Article category does not exist'] },
    })
    const duplicate = await send('POST', SUBCATEGORIES, { categoryId: mugs, name: 'CLASSIC' })
    expect(duplicate.status).toBe(409)
    expect(await jsonOf(duplicate)).toEqual({
      message: 'Article subcategory name already exists in this article category',
      errors: {},
    })
  })

  test('GET lists by category order and reads one; unknown and malformed ids answer 404 and 400', async () => {
    const posters = await insertCategory(database.db, 'Posters')
    const mugs = await insertCategory(database.db, 'Mugs')
    const tall = await insertSubcategory(database.db, mugs, 'Tall')
    await insertSubcategory(database.db, posters, 'Large')
    const list = (await jsonOf(await send('GET', SUBCATEGORIES))) as { name: string }[]
    expect(list.map((subcategory) => subcategory.name)).toEqual(['Large', 'Tall'])
    const one = await send('GET', `${SUBCATEGORIES}/${String(tall)}`)
    expect(((await jsonOf(one)) as { categoryId: number }).categoryId).toBe(mugs)
    expect(await jsonOf(await send('GET', `${SUBCATEGORIES}/999`))).toEqual({
      message: 'Article subcategory not found',
      errors: {},
    })
    expect(await jsonOf(await send('GET', `${SUBCATEGORIES}/x`))).toEqual({
      message: 'Invalid article subcategory id',
      errors: {},
    })
  })

  test('PUT moves a subcategory to another category: it appends there and compacts the source', async () => {
    const mugs = await insertCategory(database.db, 'Mugs')
    const posters = await insertCategory(database.db, 'Posters')
    const classic = await insertSubcategory(database.db, mugs, 'Classic')
    const tall = await insertSubcategory(database.db, mugs, 'Tall')
    await insertSubcategory(database.db, posters, 'Large')
    const moved = await send('PUT', `${SUBCATEGORIES}/${String(classic)}`, {
      categoryId: posters,
      name: 'Classic',
    })
    expect(moved.status).toBe(200)
    expect(await jsonOf(moved)).toMatchObject({ categoryId: posters, position: 2 })
    expect(await jsonOf(await send('GET', `${SUBCATEGORIES}/${String(tall)}`))).toMatchObject({
      position: 1,
    })
    expect(
      (await send('PUT', `${SUBCATEGORIES}/999`, { categoryId: mugs, name: 'X' })).status,
    ).toBe(404)
  })

  test('a subcategory an article uses can be neither moved nor deleted', async () => {
    const mugs = await insertCategory(database.db, 'Mugs')
    const posters = await insertCategory(database.db, 'Posters')
    const classic = await insertSubcategory(database.db, mugs, 'Classic')
    const created = await send('POST', '/api/admin/articles/mugs', {
      name: 'Mug',
      descriptionShort: 'A mug',
      descriptionLong: 'A mug',
      categoryId: mugs,
      subcategoryId: classic,
    })
    expect(created.status).toBe(201)
    const move = await send('PUT', `${SUBCATEGORIES}/${String(classic)}`, {
      categoryId: posters,
      name: 'Classic',
    })
    expect(move.status).toBe(400)
    expect(await jsonOf(move)).toEqual({
      message: 'Validation failed',
      errors: {
        categoryId: [
          'Article subcategory is used by articles and cannot be moved to another category',
        ],
      },
    })
    const remove = await send('DELETE', `${SUBCATEGORIES}/${String(classic)}`)
    expect(remove.status).toBe(409)
    expect(await jsonOf(remove)).toEqual({
      message: 'Article subcategory is used by articles and cannot be deleted',
      errors: {},
    })
  })

  test('DELETE compacts the category and removes the example image nobody names any more', async () => {
    const mugs = await insertCategory(database.db, 'Mugs')
    const upload = await uploadExample()
    expect(upload.status).toBe(201)
    const { filename } = (await jsonOf(upload)) as { filename: string }
    const created = (await jsonOf(
      await send('POST', SUBCATEGORIES, {
        categoryId: mugs,
        name: 'Classic',
        exampleImageFilename: filename,
      }),
    )) as { id: number; exampleImageFilename: string }
    expect(created.exampleImageFilename).toBe(filename)
    const tall = await insertSubcategory(database.db, mugs, 'Tall')
    const file = join(publicRoot, 'articles/subcategory-example-images', filename)
    expect(existsSync(file)).toBe(true)
    expect((await send('DELETE', `${SUBCATEGORIES}/${String(created.id)}`)).status).toBe(204)
    expect(existsSync(file)).toBe(false)
    expect(await jsonOf(await send('GET', `${SUBCATEGORIES}/${String(tall)}`))).toMatchObject({
      position: 1,
    })
    expect((await send('DELETE', `${SUBCATEGORIES}/${String(created.id)}`)).status).toBe(404)
  })

  test('a submitted example image must have been uploaded', async () => {
    const mugs = await insertCategory(database.db, 'Mugs')
    const unknown = await send('POST', SUBCATEGORIES, {
      categoryId: mugs,
      name: 'Classic',
      exampleImageFilename: '0b5e7f6a-3c1d-4e2f-9a8b-7c6d5e4f3a2b.webp',
    })
    expect(await jsonOf(unknown)).toEqual({
      message: 'Validation failed',
      errors: { exampleImageFilename: ['Example image does not exist'] },
    })
  })

  test('POST /example-images answers 400 on the file field without a file part', async () => {
    const response = await app.request(`${SUBCATEGORIES}/example-images`, {
      method: 'POST',
      headers: await signedInHeaders(),
      body: new FormData(),
    })
    expect(response.status).toBe(400)
    expect(await jsonOf(response)).toEqual({
      message: 'Validation failed',
      errors: { file: ['An example image file part is required'] },
    })
  })

  test('PUT /order reorders inside the category; a sibling of another category is 404', async () => {
    const mugs = await insertCategory(database.db, 'Mugs')
    const posters = await insertCategory(database.db, 'Posters')
    const first = await insertSubcategory(database.db, mugs, 'First')
    const second = await insertSubcategory(database.db, mugs, 'Second')
    const elsewhere = await insertSubcategory(database.db, posters, 'Elsewhere')
    const moved = await send('PUT', `${SUBCATEGORIES}/order`, { sourceId: second, targetId: first })
    expect(
      ((await jsonOf(moved)) as { id: number; position: number }[]).map(({ id, position }) => [
        id,
        position,
      ]),
    ).toEqual([
      [second, 1],
      [first, 2],
    ])
    const foreign = await send('PUT', `${SUBCATEGORIES}/order`, {
      sourceId: first,
      targetId: elsewhere,
    })
    expect(foreign.status).toBe(404)
  })
})
