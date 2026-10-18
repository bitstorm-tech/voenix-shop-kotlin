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
  directory = await mkdtemp(join(tmpdir(), 'prompt-storefront-'))
})
afterAll(() => rm(directory, { recursive: true, force: true }))

const promptModule = () => {
  const images = createImageModule({
    settings: {
      publicRoot: join(directory, 'public'),
      privateRoot: join(directory, 'private'),
      cacheRoot: join(directory, 'cache'),
    },
    guestTokens: testAuth.guestTokens,
  })
  const { priceCatalog } = createPricingModule({ db: database.db })
  return createPromptModule({ db: database.db, priceCatalog, exampleImages: images.exampleImages })
}
const catalog = () => promptModule().promptCatalog
const storefront = (query = '') => testApp(promptModule().routes).request(`/api/prompts${query}`)

/** Creates a prompt through the admin route, so its price row is the pricing module's own. */
const createPrompt = async (overrides: Record<string, unknown> = {}) => {
  const response = await testApp(promptModule().routes).request('/api/admin/prompts', {
    method: 'POST',
    headers: { ...(await signedInHeaders()), 'content-type': 'application/json' },
    body: JSON.stringify({
      title: 'Portrait',
      promptText: 'Turn the photo into art.',
      categoryId: 1,
      subcategoryId: null,
      slotVariantIds: [],
      active: true,
      price: { purchaseVatId: 1, salesVatId: 1, salesTotalInputCents: 499 },
      ...overrides,
    }),
  })
  expect(response.status).toBe(201)
  return ((await jsonOf(response)) as { id: number }).id
}

const titles = async (query = '') =>
  ((await jsonOf(await storefront(query))) as { title: string }[]).map((prompt) => prompt.title)

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
    VALUES (1, 'Adults', 1, TRUE), (1, 'Kids', 2, TRUE)`.execute(database.db)
})

describe('GET /api/prompts', () => {
  test('answers the visible prompts anonymously with nested categories and never a prompt text', async () => {
    await createPrompt({
      subcategoryId: 2,
      exampleImageFilename: null,
      llm: 'gpt-image-1',
      price: {
        purchaseVatId: 1,
        salesVatId: 1,
        salesTotalInputCents: 499,
        discountType: 'PERCENTAGE',
        discountValue: 20,
      },
    })
    const response = await storefront()
    expect(response.status).toBe(200)
    expect(await jsonOf(response)).toEqual([
      {
        id: 1,
        position: 1,
        title: 'Portrait',
        category: { id: 1, name: 'Portraits', position: 1 },
        subcategory: { id: 2, name: 'Kids', position: 2 },
        exampleImageFilename: null,
        llm: 'gpt-image-1',
        price: {
          salesTotalNet: 335,
          salesTotalGross: 399,
          salesTotalTax: 64,
          regularSalesTotalGross: 499,
          salesVatRatePercent: 19,
        },
      },
    ])
  })

  test('lists a prompt only while it, its category, and its subcategory if any are visible', async () => {
    await createPrompt({ title: 'Visible' })
    await createPrompt({ title: 'Inactive', active: false })
    await createPrompt({ title: 'Archived', archived: true })
    await createPrompt({ title: 'In inactive category', categoryId: 2 })
    await createPrompt({ title: 'In inactive subcategory', subcategoryId: 2 })
    await createPrompt({ title: 'In active subcategory', subcategoryId: 1 })
    await sql`UPDATE prompt_categories SET active = FALSE WHERE id = 2`.execute(database.db)
    await sql`UPDATE prompt_subcategories SET active = FALSE WHERE id = 2`.execute(database.db)
    expect(await titles()).toEqual(['Visible', 'In active subcategory'])
  })

  test('keeps the (position, id) order with and without the filter; an unknown category is []', async () => {
    await createPrompt({ title: 'First' })
    await createPrompt({ title: 'Dog', categoryId: 2 })
    await createPrompt({ title: 'Third' })
    await sql`UPDATE prompts SET position = CASE id WHEN 1 THEN 3 WHEN 3 THEN 1 ELSE position END`.execute(
      database.db,
    )
    expect(await titles()).toEqual(['Third', 'Dog', 'First'])
    expect(await titles('?categoryId=1')).toEqual(['Third', 'First'])
    expect(await titles('?categoryId=%20')).toEqual(['Third', 'Dog', 'First'])
    expect(await titles('?categoryId=99')).toEqual([])
    const invalid = await storefront('?categoryId=abc')
    expect(invalid.status).toBe(400)
    expect(await jsonOf(invalid)).toEqual({ message: 'Invalid prompt category id', errors: {} })
  })

  test('a prompt without a price row answers price null, and a VAT change is recalculated', async () => {
    await sql`INSERT INTO prompts (position, title, prompt_text, category_id, active, archived)
      VALUES (1, 'Unpriced', 'text', 1, TRUE, FALSE)`.execute(database.db)
    expect(await jsonOf(await storefront())).toMatchObject([{ title: 'Unpriced', price: null }])
    await createPrompt()
    await sql`UPDATE value_added_taxes SET percent = 7`.execute(database.db)
    expect(await jsonOf(await storefront())).toMatchObject([
      { price: null },
      { price: { salesTotalNet: 466, salesTotalGross: 499, salesVatRatePercent: 7 } },
    ])
  })
})

describe('promptCatalog.composedText', () => {
  beforeEach(async () => {
    // Slot ids run against their positions, so the order can only come from the positions.
    await sql`INSERT INTO prompt_slots (name, position) VALUES ('Style', 2), ('Background', 1)`.execute(
      database.db,
    )
    await sql`INSERT INTO prompt_slot_variants (slot_id, name, prompt) VALUES
      (1, 'Watercolor', '  in watercolor  '), (2, 'Beach', 'on a beach'),
      (1, 'Blank', '   '), (1, 'Bold', 'bold lines')`.execute(database.db)
  })

  test('joins the trimmed prompt text and the variant texts, ordered by slot position, by a blank line', async () => {
    const id = await createPrompt({
      promptText: '  Turn the photo into art.\n',
      slotVariantIds: [1, 2, 3, 4],
    })
    expect(await catalog().composedText(id)).toBe(
      'Turn the photo into art.\n\non a beach\n\nbold lines\n\nin watercolor',
    )
    expect(await catalog().composedText(await createPrompt({ promptText: 'Alone.' }))).toBe(
      'Alone.',
    )
  })

  test('is null for an unknown, inactive, archived, or textless prompt, but ignores the category flags', async () => {
    const inactive = await createPrompt({ active: false })
    const archived = await createPrompt({ archived: true })
    const textless = await createPrompt()
    await sql`UPDATE prompts SET prompt_text = ${' \n '} WHERE id = ${textless}`.execute(
      database.db,
    )
    for (const id of [999, inactive, archived, textless]) {
      expect(await catalog().composedText(id)).toBeNull()
    }
    const hidden = await createPrompt({ categoryId: 2 })
    await sql`UPDATE prompt_categories SET active = FALSE WHERE id = 2`.execute(database.db)
    expect(await catalog().composedText(hidden)).toBe('Turn the photo into art.')
  })
})

describe('promptCatalog.findSalesGrossPriceCents', () => {
  test('answers the effective gross of every eligible prompt and leaves every other id absent', async () => {
    const priced = await createPrompt({
      price: {
        purchaseVatId: 1,
        salesVatId: 1,
        salesTotalInputCents: 500,
        discountType: 'FIXED_AMOUNT',
        discountValue: 100,
      },
    })
    const free = await createPrompt({
      price: { purchaseVatId: 1, salesVatId: 1, salesTotalInputCents: 0 },
    })
    const inactive = await createPrompt({ active: false })
    const archived = await createPrompt({ archived: true })
    const hidden = await createPrompt({ categoryId: 2 })
    await sql`UPDATE prompt_categories SET active = FALSE WHERE id = 2`.execute(database.db)
    await sql`INSERT INTO prompts (position, title, prompt_text, category_id, active, archived)
      VALUES (9, 'Unpriced', 'text', 1, TRUE, FALSE)`.execute(database.db)
    const prices = await catalog().findSalesGrossPriceCents([
      priced,
      free,
      inactive,
      archived,
      hidden,
      999,
      (
        await sql<{ id: number }>`SELECT id FROM prompts WHERE title = 'Unpriced'`.execute(
          database.db,
        )
      ).rows[0]?.id ?? 0,
    ])
    expect([...prices.entries()].sort(([a], [b]) => a - b)).toEqual([
      [priced, 400],
      [free, 0],
      [hidden, 499],
    ])
    expect((await catalog().findSalesGrossPriceCents([])).size).toBe(0)
  })
})
