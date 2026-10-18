import { beforeAll, beforeEach, describe, expect, test } from 'bun:test'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { sql } from 'kysely'
import { createLogger } from '@/platform/logging'
import { testDatabase } from '@test-support/database'
import { testAuth } from '@test-support/http'
import { pngBytes } from '@test-support/values'
import {
  articleTestModule,
  grossPrice,
  insertCategory,
  insertVat,
  resetArticles,
} from '../test-fixtures'
import { createMugRepository } from './repository'
import { createMugService, type MugArticleInput, type MugVariantInput } from './service'

/**
 * The mug write path below the routes: the price joins the article's transaction, the variant
 * array is a diff that cleans up the files it orphans, and the `MUG` anchor keeps positions dense
 * against concurrent writers, while a writer outside it loses the reorder at COMMIT.
 */

const database = testDatabase()
let service: ReturnType<typeof createMugService>
let publicRoot: string
let storeImage: () => Promise<string>
let vat: number
let category: number

beforeAll(async () => {
  const fixture = await articleTestModule(database.db, testAuth.guestTokens)
  publicRoot = fixture.publicRoot
  const images = fixture.images.exampleImages(
    'articles/mugs/variant-example-images',
    createLogger('article-test'),
  )
  service = createMugService(
    createMugRepository(database.db, fixture.pricing.priceCatalog),
    images,
    fixture.pricing.priceCatalog,
    () => Promise.resolve(new Map<number, never>()),
  )
  storeImage = async () => {
    const stored = await images.store({ bytes: await pngBytes(), contentType: 'image/png' })
    if (stored.kind !== 'ok') throw new Error('The image was not stored')
    return stored.value.filename
  }
})
beforeEach(async () => {
  await resetArticles(database.db)
  vat = await insertVat(database.db)
  category = await insertCategory(database.db, 'Mugs')
})

const variant = (overrides: Partial<MugVariantInput> = {}): MugVariantInput => ({
  id: null,
  name: 'White',
  insideColorCode: '#ffffff',
  outsideColorCode: '#ffffff',
  isDefault: true,
  active: true,
  exampleImageFilename: null,
  ...overrides,
})
const input = (overrides: Partial<MugArticleInput> = {}): MugArticleInput => ({
  name: 'Classic mug',
  descriptionShort: 'A mug',
  descriptionLong: 'A classic mug',
  active: false,
  categoryId: category,
  subcategoryId: null,
  supplierId: null,
  supplierArticleName: null,
  supplierArticleNumber: null,
  printAspectRatio: '16:9',
  mugDetails: null,
  mugVariants: [variant()],
  price: grossPrice(vat, 1490),
  ...overrides,
})
const create = async (overrides: Partial<MugArticleInput> = {}) => {
  const created = await service.create(input(overrides))
  if (created.kind !== 'ok') throw new Error(`The mug was not created: ${JSON.stringify(created)}`)
  return created.value
}
const count = async (table: 'prices' | 'article_identities' | 'article_variant_identities') => {
  const { rows } = await sql<{
    n: number
  }>`SELECT count(*)::int AS n FROM ${sql.table(table)}`.execute(database.db)
  return rows[0]?.n
}

describe('the price joins the article transaction', () => {
  test('a rejected article leaves no price, no identity, and no variant identity behind', async () => {
    expect(await service.create(input({ supplierId: 999 }))).toEqual({
      kind: 'invalid',
      errors: { supplierId: ['Supplier does not exist'] },
    })
    expect(await count('prices')).toBe(0)
    expect(await count('article_identities')).toBe(0)
    expect(await count('article_variant_identities')).toBe(0)
  })

  test('a rejected price creates no article', async () => {
    const rejected = await service.create(input({ price: grossPrice(999, 1490) }))
    expect(rejected.kind).toBe('invalid')
    expect(await count('article_identities')).toBe(0)
  })

  test('an update rewrites the owned price in place and the delete removes it', async () => {
    const mug = await create()
    const priceId = mug.price?.id
    const updated = await service.update(mug.id, input({ price: grossPrice(vat, 1990) }))
    expect(updated.kind === 'ok' && updated.value.price).toMatchObject({
      id: priceId,
      salesTotal: { gross: 1990 },
    })
    expect(await service.delete(mug.id)).toEqual({ kind: 'ok', value: undefined })
    expect(await count('prices')).toBe(0)
    expect(await count('article_variant_identities')).toBe(0)
  })
})

describe('the variant diff', () => {
  test('updates, inserts, deletes, swaps the default, and deletes only the files nobody names', async () => {
    const [kept, shared, dropped] = [await storeImage(), await storeImage(), await storeImage()]
    const mug = await create({
      mugVariants: [
        variant({ name: 'A', exampleImageFilename: kept }),
        variant({ name: 'B', isDefault: false, exampleImageFilename: shared }),
        variant({ name: 'C', isDefault: false, exampleImageFilename: dropped }),
      ],
    })
    const [a, b] = mug.mugVariants
    if (a === undefined || b === undefined) throw new Error('The variants were not stored')
    const updated = await service.update(
      mug.id,
      input({
        mugVariants: [
          variant({ id: a.id, name: 'A', isDefault: false, exampleImageFilename: shared }),
          variant({ id: b.id, name: 'B', isDefault: true, exampleImageFilename: shared }),
          variant({ name: 'D', isDefault: false }),
        ],
      }),
    )
    expect(
      updated.kind === 'ok' && updated.value.mugVariants.map((v) => [v.name, v.isDefault]),
    ).toEqual([
      ['B', true],
      ['A', false],
      ['D', false],
    ])
    const file = (name: string) => join(publicRoot, 'articles/mugs/variant-example-images', name)
    expect(existsSync(file(kept))).toBe(false)
    expect(existsSync(file(dropped))).toBe(false)
    expect(existsSync(file(shared))).toBe(true)
  })

  test('a submitted image name that names no file is refused before anything is written', async () => {
    const refused = await service.create(
      input({
        mugVariants: [
          variant({ exampleImageFilename: '0b5e7f6a-3c1d-4e2f-9a8b-7c6d5e4f3a2b.webp' }),
        ],
      }),
    )
    expect(refused).toEqual({
      kind: 'invalid',
      errors: { 'mugVariants[0].exampleImageFilename': ['Example image does not exist'] },
    })
    expect(await count('article_identities')).toBe(0)
  })
})

describe('concurrency under the MUG anchor', () => {
  const positions = async () =>
    (
      await database.db
        .selectFrom('article_mugs')
        .select(['id', 'position'])
        .orderBy('position')
        .execute()
    ).map(({ id, position }) => [id, position])

  test('concurrent creates, deletes, and reorders leave a dense sequence', async () => {
    const [first, second] = [await create(), await create()]
    await Promise.all([
      create(),
      create(),
      service.delete(first.id),
      service.reorder(second.id, first.id),
      create(),
    ])
    expect((await positions()).map(([, position]) => position)).toEqual([1, 2, 3, 4])
  })

  test('a rotation committed outside the anchor makes the reorder lose at COMMIT', async () => {
    const [a, b, c] = [await create(), await create(), await create()]
    const outsider = database.db.transaction().execute(async (trx) => {
      await sql`UPDATE article_mugs SET position = 3 WHERE id = ${b.id}`.execute(trx)
      await sql`UPDATE article_mugs SET position = 2 WHERE id = ${c.id}`.execute(trx)
      for (let attempt = 0; attempt < 200; attempt++) {
        const { rows } = await sql<{ n: number }>`SELECT count(*)::int AS n FROM pg_stat_activity
          WHERE datname = current_database() AND wait_event_type = 'Lock'`.execute(database.db)
        if ((rows[0]?.n ?? 0) > 0) return
        await Bun.sleep(10)
      }
      throw new Error('The reorder never waited for the outsider')
    })
    const reorder = service.reorder(a.id, b.id)
    await outsider
    expect(await reorder).toEqual({ kind: 'conflict' })
    expect(await positions()).toEqual([
      [a.id, 1],
      [c.id, 2],
      [b.id, 3],
    ])
  })
})
