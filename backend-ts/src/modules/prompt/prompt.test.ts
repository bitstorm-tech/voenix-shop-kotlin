import { afterAll, beforeAll, beforeEach, describe, expect, test } from 'bun:test'
import { existsSync } from 'node:fs'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { sql } from 'kysely'
import sharp from 'sharp'
import { createImageModule } from '@/modules/image'
import { createPricingModule } from '@/modules/pricing'
import { createPromptModule } from '@/modules/prompt'
import { testDatabase } from '@test-support/database'
import { jsonOf, signedInHeaders, testApp, testAuth } from '@test-support/http'

const database = testDatabase()
let directory: string
beforeAll(async () => {
  directory = await mkdtemp(join(tmpdir(), 'prompt-prompts-'))
})
afterAll(() => rm(directory, { recursive: true, force: true }))

const exampleImagePath = (filename: string) =>
  join(directory, 'public', 'prompt-example-images', filename)

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

const upload = async (form: FormData) =>
  app().request('/api/admin/prompts/example-images', {
    method: 'POST',
    headers: await signedInHeaders(),
    body: form,
  })

const uploadedImage = async (): Promise<string> => {
  const form = new FormData()
  const png = await sharp({ create: { width: 8, height: 8, channels: 3, background: '#369' } })
    .png()
    .toBuffer()
  form.append('file', new File([new Uint8Array(png)], 'example.png', { type: 'image/png' }))
  const response = await upload(form)
  expect(response.status).toBe(201)
  return ((await jsonOf(response)) as { filename: string }).filename
}

const body = (overrides: Record<string, unknown> = {}) => ({
  title: 'Watercolor portrait',
  promptText: 'Paint it.',
  categoryId: 1,
  subcategoryId: 1,
  slotVariantIds: [1],
  active: true,
  price: { purchaseVatId: 1, salesVatId: 1, salesTotalInputCents: 499 },
  ...overrides,
})

const createdPrompt = async (overrides: Record<string, unknown> = {}) => {
  const response = await call('POST', '', body(overrides))
  expect(response.status).toBe(201)
  return (await jsonOf(response)) as { id: number; price: { id: number } }
}

const count = async (table: 'prompts' | 'prices') => {
  const { rows } = await sql<{ count: number }>`SELECT count(*)::int AS count FROM ${sql.table(
    table,
  )}`.execute(database.db)
  return rows[0]?.count
}

/** Resolves once `waiting` sessions of this database wait for a lock. */
async function lockWaiters(waiting: number): Promise<void> {
  for (;;) {
    const { rows } = await sql<{ count: number }>`SELECT count(*)::int AS count
      FROM pg_stat_activity
      WHERE datname = current_database() AND wait_event_type = 'Lock'`.execute(database.db)
    if ((rows[0]?.count ?? 0) >= waiting) return
    await new Promise((resolve) => setTimeout(resolve, 20))
  }
}

beforeEach(async () => {
  await sql`TRUNCATE prompts, prompt_slot_variant_mappings, prompt_categories, prompt_subcategories,
    prompt_slots, prompt_slot_variants, prices, value_added_taxes RESTART IDENTITY CASCADE`.execute(
    database.db,
  )
  await sql`INSERT INTO value_added_taxes (name, percent, description, is_default)
    VALUES ('Standard', 19, NULL, TRUE)`.execute(database.db)
  await sql`INSERT INTO prompt_categories (name, position, active)
    VALUES ('Portraits', 1, TRUE), ('Animals', 2, TRUE)`.execute(database.db)
  await sql`INSERT INTO prompt_subcategories (category_id, name, position, active)
    VALUES (1, 'Adults', 1, TRUE), (2, 'Dogs', 1, TRUE)`.execute(database.db)
  await sql`INSERT INTO prompt_slots (name, position) VALUES ('Style', 1)`.execute(database.db)
  await sql`INSERT INTO prompt_slot_variants (slot_id, name, prompt)
    VALUES (1, 'Watercolor', 'in watercolor'), (1, 'Oil', 'in oil'), (1, 'Ink', 'in ink')`.execute(
    database.db,
  )
})

describe('POST /api/admin/prompts', () => {
  test('creates a prompt behind the last one with its price, normalized as documented', async () => {
    await createdPrompt()
    const response = await call(
      'POST',
      '',
      body({
        title: '  Portrait  ',
        promptText: '\n  Paint it.  \n',
        slotVariantIds: [3, 2, 3],
        llm: '  gpt-image-1  ',
        exampleImageFilename: '   ',
      }),
    )
    expect(response.status).toBe(201)
    expect(response.headers.get('Location')).toBe('/api/admin/prompts/2')
    const prompt = (await jsonOf(response)) as Record<string, unknown>
    expect(prompt).toMatchObject({
      id: 2,
      position: 2,
      title: 'Portrait',
      promptText: '\n  Paint it.  \n',
      categoryId: 1,
      subcategoryId: 1,
      slotVariantIds: [2, 3],
      exampleImageFilename: null,
      llm: 'gpt-image-1',
      active: true,
      archived: false,
      price: { id: 2, salesVatId: 1, salesTotalInputCents: 499 },
    })
    expect(prompt.price).toMatchObject({ salesTotal: { net: 419, tax: 80, gross: 499 } })
    expect(Object.keys(prompt)).not.toContain('priceId')
  })

  test('a rejected price creates nothing and keeps the path the client sent it at', async () => {
    const response = await call(
      'POST',
      '',
      body({ price: { purchaseVatId: 1, salesVatId: 9, salesTotalInputCents: 499 } }),
    )
    expect(response.status).toBe(400)
    expect(await jsonOf(response)).toEqual({
      message: 'Validation failed',
      errors: { 'price.salesVatId': ['Sales VAT not found'] },
    })
    expect([await count('prompts'), await count('prices')]).toEqual([0, 0])
  })

  test('the three references are field errors, and a prompt that is not written leaves no price row', async () => {
    const rejected = async (overrides: Record<string, unknown>) => {
      const response = await call('POST', '', body(overrides))
      expect(response.status).toBe(400)
      return ((await jsonOf(response)) as { errors: unknown }).errors
    }
    expect(await rejected({ categoryId: 9 })).toEqual({
      categoryId: ['Prompt category does not exist'],
    })
    expect(await rejected({ subcategoryId: 2 })).toEqual({
      subcategoryId: ['Prompt subcategory does not exist in this prompt category'],
    })
    // The price row is written before the mapping fails; the rollback takes it along.
    expect(await rejected({ slotVariantIds: [1, 99] })).toEqual({
      slotVariantIds: ['One or more prompt slot variants do not exist'],
    })
    expect([await count('prompts'), await count('prices')]).toEqual([0, 0])
  })

  test('concurrent creates append 1..n', async () => {
    const created = await Promise.all([1, 2, 3, 4].map(() => call('POST', '', body())))
    expect(created.map((response) => response.status)).toEqual([201, 201, 201, 201])
    const listed = (await jsonOf(await call('GET', ''))) as { position: number }[]
    expect(listed.map((row) => row.position)).toEqual([1, 2, 3, 4])
  })
})

describe('GET and PUT /api/admin/prompts', () => {
  test('the list answers flat rows with names and the small price projection', async () => {
    await createdPrompt({
      price: {
        purchaseVatId: 1,
        salesVatId: 1,
        salesTotalInputCents: 499,
        discountType: 'PERCENTAGE',
        discountValue: 20,
      },
    })
    await createdPrompt({ title: 'Dog', categoryId: 2, subcategoryId: null, slotVariantIds: [] })
    const response = await call('GET', '')
    expect(response.status).toBe(200)
    expect(await jsonOf(response)).toEqual([
      {
        id: 1,
        position: 1,
        title: 'Watercolor portrait',
        categoryId: 1,
        categoryName: 'Portraits',
        subcategoryId: 1,
        subcategoryName: 'Adults',
        exampleImageFilename: null,
        llm: null,
        active: true,
        archived: false,
        price: {
          salesTotalNet: 335,
          salesTotalGross: 399,
          salesTotalTax: 64,
          regularSalesTotalGross: 499,
          salesVatRatePercent: 19,
        },
      },
      {
        id: 2,
        position: 2,
        title: 'Dog',
        categoryId: 2,
        categoryName: 'Animals',
        subcategoryId: null,
        subcategoryName: null,
        exampleImageFilename: null,
        llm: null,
        active: true,
        archived: false,
        price: {
          salesTotalNet: 419,
          salesTotalGross: 499,
          salesTotalTax: 80,
          regularSalesTotalGross: null,
          salesVatRatePercent: 19,
        },
      },
    ])
  })

  test('an update replaces every value and the mapping set, and keeps the position and the price id', async () => {
    const prompt = await createdPrompt({ slotVariantIds: [1, 2] })
    await createdPrompt()
    const response = await call(
      'PUT',
      '/1',
      body({
        title: 'Dog',
        categoryId: 2,
        subcategoryId: 2,
        slotVariantIds: [3],
        archived: true,
        price: { purchaseVatId: 1, salesVatId: 1, salesTotalInputCents: 999 },
      }),
    )
    expect(response.status).toBe(200)
    expect(await jsonOf(response)).toMatchObject({
      id: 1,
      position: 1,
      title: 'Dog',
      categoryId: 2,
      subcategoryId: 2,
      slotVariantIds: [3],
      archived: true,
      price: { id: prompt.price.id, salesTotal: { gross: 999 } },
    })
    expect(await jsonOf(await call('GET', '/1'))).toMatchObject({ slotVariantIds: [3] })
    expect(await count('prices')).toBe(2)
  })

  test('an update of a prompt whose price was never linked creates and links one', async () => {
    await sql`INSERT INTO prompts (position, title, prompt_text, category_id, active, archived)
      VALUES (1, 'Unpriced', 'text', 1, TRUE, FALSE)`.execute(database.db)
    expect(await jsonOf(await call('GET', '/1'))).toMatchObject({ price: null, slotVariantIds: [] })
    const response = await call('PUT', '/1', body())
    expect(response.status).toBe(200)
    expect(await jsonOf(response)).toMatchObject({ price: { id: 1 } })
  })

  test('unknown and malformed ids, and there is no delete route', async () => {
    const missing = await call('GET', '/9')
    expect(missing.status).toBe(404)
    expect(await jsonOf(missing)).toEqual({ message: 'Prompt not found', errors: {} })
    expect((await call('PUT', '/9', body())).status).toBe(404)
    expect(await jsonOf(await call('GET', '/x'))).toEqual({
      message: 'Invalid prompt id',
      errors: {},
    })
    await createdPrompt()
    expect((await call('DELETE', '/1')).status).toBe(404)
    expect(await count('prompts')).toBe(1)
  })
})

describe('PUT /api/admin/prompts/order', () => {
  beforeEach(async () => {
    for (const title of ['A', 'B', 'C']) await createdPrompt({ title })
  })

  const titles = async () =>
    ((await jsonOf(await call('GET', ''))) as { title: string }[]).map((row) => row.title)

  test('answers the complete new order as list rows; an unknown id is 404', async () => {
    const response = await call('PUT', '/order', { sourceId: 3, targetId: 1 })
    expect(response.status).toBe(200)
    expect(await jsonOf(response)).toMatchObject([
      { id: 3, position: 1, title: 'C', price: { salesTotalGross: 499 } },
      { id: 1, position: 2, title: 'A' },
      { id: 2, position: 3, title: 'B' },
    ])
    expect(await titles()).toEqual(['C', 'A', 'B'])
    expect((await call('PUT', '/order', { sourceId: 3, targetId: 9 })).status).toBe(404)
  })

  test('a gapped order is refused without writing, and a position committed outside the anchor loses the reorder at COMMIT', async () => {
    await sql`UPDATE prompts SET position = 7 WHERE id = 3`.execute(database.db)
    const gapped = await call('PUT', '/order', { sourceId: 1, targetId: 2 })
    expect(gapped.status).toBe(409)
    expect(await jsonOf(gapped)).toEqual({
      message: 'Prompt order changed concurrently, please retry',
      errors: {},
    })
    expect(await titles()).toEqual(['A', 'B', 'C'])
    await sql`UPDATE prompts SET position = 3 WHERE id = 3`.execute(database.db)

    // A writer that ignores the anchor swaps A and C and holds both rows while the reorder waits.
    const rotation = await database.db.startTransaction().execute()
    await rotation.updateTable('prompts').set({ position: 3 }).where('id', '=', 1).execute()
    await rotation.updateTable('prompts').set({ position: 1 }).where('id', '=', 3).execute()
    const reorder = call('PUT', '/order', { sourceId: 2, targetId: 1 })
    await lockWaiters(1)
    await rotation.commit().execute()
    expect((await reorder).status).toBe(409)
    expect(await titles()).toEqual(['C', 'B', 'A'])
  })

  test('two concurrent reorders and a create next to them leave a dense order', async () => {
    const results = await Promise.all([
      call('PUT', '/order', { sourceId: 1, targetId: 3 }),
      call('POST', '', body({ title: 'D' })),
      call('PUT', '/order', { sourceId: 2, targetId: 1 }),
    ])
    expect(results.map((response) => response.status)).toEqual([200, 201, 200])
    const listed = (await jsonOf(await call('GET', ''))) as { position: number }[]
    expect(listed.map((row) => row.position)).toEqual([1, 2, 3, 4])
  })
})

describe('the example image', () => {
  test('the pre-upload stores a WebP and answers its name; a missing or oversized part is a field error', async () => {
    const filename = await uploadedImage()
    expect(filename).toMatch(/^[0-9a-f-]{36}\.webp$/)
    expect(existsSync(exampleImagePath(filename))).toBe(true)

    const missing = await upload(new FormData())
    expect(missing.status).toBe(400)
    expect(await jsonOf(missing)).toEqual({
      message: 'Validation failed',
      errors: { file: ['An example image file part is required'] },
    })
    const oversized = new FormData()
    oversized.append('file', new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'big.png'))
    expect(await jsonOf(await upload(oversized))).toEqual({
      message: 'Validation failed',
      errors: { file: ['Example image must not exceed 10 MiB'] },
    })
  })

  test('a malformed or unknown name is a field error, also when the prompt already stores it', async () => {
    const malformed = await call('POST', '', body({ exampleImageFilename: '../secret.webp' }))
    expect(await jsonOf(malformed)).toEqual({
      message: 'Validation failed',
      errors: {
        exampleImageFilename: ['Example image filename must be the name of an uploaded image'],
      },
    })
    const unknown = '0b5e7f6a-3c1d-4e2f-9a8b-7c6d5e4f3a2b.webp'
    expect(await jsonOf(await call('POST', '', body({ exampleImageFilename: unknown })))).toEqual({
      message: 'Validation failed',
      errors: { exampleImageFilename: ['Example image does not exist'] },
    })

    const filename = await uploadedImage()
    await createdPrompt({ exampleImageFilename: filename })
    await rm(exampleImagePath(filename))
    const vanished = await call('PUT', '/1', body({ exampleImageFilename: filename }))
    expect(vanished.status).toBe(400)
  })

  test('a file is deleted once the last prompt stops naming it, and kept while another still does', async () => {
    const shared = await uploadedImage()
    await createdPrompt({ exampleImageFilename: shared })
    await createdPrompt({ exampleImageFilename: shared })
    expect((await call('PUT', '/1', body())).status).toBe(200)
    expect(existsSync(exampleImagePath(shared))).toBe(true)
    const replacement = await uploadedImage()
    expect((await call('PUT', '/2', body({ exampleImageFilename: replacement }))).status).toBe(200)
    expect(existsSync(exampleImagePath(shared))).toBe(false)
    expect(existsSync(exampleImagePath(replacement))).toBe(true)
  })
})
