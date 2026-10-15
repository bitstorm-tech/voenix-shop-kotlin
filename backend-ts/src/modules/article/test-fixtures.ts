import { mkdtemp } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { sql, type Kysely } from 'kysely'
import sharp from 'sharp'
import { createImageModule } from '@/modules/image'
import { createPricingModule, type PriceInput } from '@/modules/pricing'
import { createSpodClient, type SpodClient } from '@/modules/spod'
import { createSupplierModule } from '@/modules/supplier'
import type { Auth } from '@/platform/auth/auth'
import type { Tables } from '@/platform/db/database'
import { createArticleModule } from './index'

/**
 * What the article tests share: the module wired to the real pricing, supplier, country, and image
 * modules (images in a temporary directory), and the rows a test seeds with SQL because no route of
 * this module writes them (VAT, suppliers, SPOD destinations, synced SPOD articles). Test-only; no
 * production code imports it.
 */

export async function articleTestModule(
  db: Kysely<Tables>,
  guestTokens: Auth['guestTokens'],
  spod: SpodClient = createSpodClient({
    fetch: () => Promise.reject(new Error('No partner call is expected in this test')),
  }),
) {
  const root = await mkdtemp(join(tmpdir(), 'article-test-'))
  const settings = {
    publicRoot: join(root, 'public'),
    privateRoot: join(root, 'private'),
    cacheRoot: join(root, 'cache'),
  }
  const images = createImageModule({ settings, guestTokens })
  const pricing = createPricingModule({ db })
  // The supplier rows these tests seed name no country, so the country lookup answers nothing.
  const suppliers = createSupplierModule({
    db,
    countries: { find: () => Promise.resolve(new Map<number, never>()) },
  })
  const article = createArticleModule({
    db,
    images,
    prices: pricing.priceCatalog,
    suppliers,
    spod,
  })
  return { article, images, pricing, publicRoot: settings.publicRoot }
}

/** Empties every article table and everything the tests seed next to them. */
export async function resetArticles(db: Kysely<Tables>): Promise<void> {
  await sql`TRUNCATE article_identities, article_categories, article_subcategories, prices,
    value_added_taxes, production_destinations, suppliers RESTART IDENTITY CASCADE`.execute(db)
}

/** A VAT of 19 % (id 1 after a reset). */
export async function insertVat(db: Kysely<Tables>): Promise<number> {
  const { id } = await db
    .insertInto('value_added_taxes')
    .values({ name: 'Standard', percent: 19, description: null, is_default: true })
    .returning('id')
    .executeTakeFirstOrThrow()
  return id
}

export async function insertSupplier(db: Kysely<Tables>, name: string): Promise<number> {
  const { rows } = await sql<{ id: number }>`
    INSERT INTO suppliers (name) VALUES (${name}) RETURNING id`.execute(db)
  const id = rows[0]?.id
  if (id === undefined) throw new Error('The supplier was not inserted')
  return id
}

/** A SPOD production destination of `supplierId`, the row a synced article references. */
export async function insertSpodDestination(
  db: Kysely<Tables>,
  supplierId: number,
): Promise<number> {
  const { rows } = await sql<{ id: number }>`
    INSERT INTO production_destinations (supplier_id, channel, label)
    VALUES (${supplierId}, 'SPOD', 'Spreadconnect') RETURNING id`.execute(db)
  const id = rows[0]?.id
  if (id === undefined) throw new Error('The destination was not inserted')
  return id
}

export async function insertCategory(
  db: Kysely<Tables>,
  name: string,
  active = true,
): Promise<number> {
  const { rows } = await sql<{ id: number }>`
    INSERT INTO article_categories (name, position, active)
    VALUES (${name}, (SELECT coalesce(max(position), 0) + 1 FROM article_categories), ${active})
    RETURNING id`.execute(db)
  const id = rows[0]?.id
  if (id === undefined) throw new Error('The category was not inserted')
  return id
}

export async function insertSubcategory(
  db: Kysely<Tables>,
  categoryId: number,
  name: string,
  active = true,
): Promise<number> {
  const { rows } = await sql<{ id: number }>`
    INSERT INTO article_subcategories (category_id, name, position, active)
    VALUES (${categoryId}, ${name},
      (SELECT coalesce(max(position), 0) + 1 FROM article_subcategories
       WHERE category_id = ${categoryId}), ${active})
    RETURNING id`.execute(db)
  const id = rows[0]?.id
  if (id === undefined) throw new Error('The subcategory was not inserted')
  return id
}

/** A price whose gross sales total is `grossCents` on VAT `vatId`, optionally discounted. */
export const grossPrice = (
  vatId: number,
  grossCents: number,
  discountPercent: number | null = null,
): PriceInput => ({
  purchaseVatId: vatId,
  purchaseCalculationMode: 'NET',
  purchaseActiveRow: 'COST',
  purchasePriceInputCents: 100,
  purchaseCostInputCents: 0,
  purchaseCostPercent: 0,
  salesVatId: vatId,
  salesCalculationMode: 'GROSS',
  salesActiveRow: 'TOTAL',
  salesMarginInputCents: 0,
  salesMarginPercent: 0,
  salesTotalInputCents: grossCents,
  discountType: discountPercent === null ? null : 'PERCENTAGE',
  discountValue: discountPercent,
})

export interface SeededSpodColor {
  name: string
  hex?: string
  sizes: string[]
  listing?: string[]
  mockup?: string | null
  spodAppearanceId?: number
}

/**
 * A SPOD article as a sync run writes it: identity, row, colours with their pictures, and one
 * variant per colour × size, the first one the default. `framed: false` leaves the frame and the
 * hotspot unmeasured. Answers the ids of the article, its colours, and its variants in order.
 */
export async function insertSpodArticle(
  db: Kysely<Tables>,
  {
    destinationId,
    supplierId,
    spodArticleId = 'a-1',
    name = 'Classic tee',
    environment = 'PRODUCTION',
    framed = true,
    sizeChart = null,
    colors = [{ name: 'Black', sizes: ['M', 'L'] }],
  }: {
    destinationId: number
    supplierId: number
    spodArticleId?: string
    name?: string
    environment?: string
    framed?: boolean
    sizeChart?: string | null
    colors?: SeededSpodColor[]
  },
): Promise<{ articleId: number; appearanceIds: number[]; variantIds: number[] }> {
  return db.transaction().execute(async (trx) => {
    const { id } = await trx
      .insertInto('article_identities')
      .values({ article_type: 'SPOD' })
      .returning('id')
      .executeTakeFirstOrThrow()
    const { rows } = await sql<{ next: number }>`
      SELECT coalesce(max(position), 0) + 1 AS next FROM article_spod_articles`.execute(trx)
    await trx
      .insertInto('article_spod_articles')
      .values({
        id,
        position: rows[0]?.next ?? 1,
        name,
        description_short: 'A tee',
        description_long: 'A classic heavy cotton tee',
        active: false,
        category_id: null,
        subcategory_id: null,
        supplier_id: supplierId,
        price_id: null,
        size_chart_image_filename: sizeChart,
        print_frame_left_pct: framed ? 35.6 : null,
        print_frame_top_pct: framed ? 18.9 : null,
        print_frame_width_pct: framed ? 28.4 : null,
        print_frame_height_pct: framed ? 28.4 : null,
        spod_destination_id: destinationId,
        spod_environment: environment,
        spod_article_id: spodArticleId,
        spod_synced_at: new Date('2026-08-24T09:15:00Z'),
        spod_missing_since: null,
        spod_size_chart_url: null,
        spod_product_type_id: 812,
        spod_product_type_name: "Men's T-Shirt",
        spod_hotspot: framed ? 'MEDIUM_FRONT' : null,
      })
      .execute()
    const appearanceIds: number[] = []
    const variantIds: number[] = []
    for (const [index, color] of colors.entries()) {
      const appearance = await trx
        .insertInto('article_spod_appearances')
        .values({
          article_id: id,
          spod_appearance_id: color.spodAppearanceId ?? index + 5,
          color_name: color.name,
          color_hex: color.hex ?? '#101010',
          position: index + 1,
        })
        .returning('id')
        .executeTakeFirstOrThrow()
      appearanceIds.push(appearance.id)
      for (const [position, filename] of (color.listing ?? []).entries()) {
        await trx
          .insertInto('article_spod_images')
          .values({
            appearance_id: appearance.id,
            role: 'LISTING',
            position: position + 1,
            spod_image_id: `img-${String(appearance.id)}-${String(position)}`,
            filename,
          })
          .execute()
      }
      if (color.mockup != null) {
        await trx
          .insertInto('article_spod_images')
          .values({
            appearance_id: appearance.id,
            role: 'MOCKUP',
            position: 1,
            filename: color.mockup,
            source_url: 'https://cdn.example.test/mockup.png',
          })
          .execute()
      }
      for (const [sizeIndex, size] of color.sizes.entries()) {
        const variant = await trx
          .insertInto('article_variant_identities')
          .values({ article_id: id, article_type: 'SPOD' })
          .returning('id')
          .executeTakeFirstOrThrow()
        await trx
          .insertInto('article_spod_variants')
          .values({
            id: variant.id,
            article_id: id,
            appearance_id: appearance.id,
            size_label: size,
            spod_product_type_id: 812,
            spod_size_id: sizeIndex + 1,
            spod_variant_id: `v-${String(variant.id)}`,
            sku: null,
            is_default: variantIds.length === 0,
            active: true,
          })
          .execute()
        variantIds.push(variant.id)
      }
    }
    return { articleId: id, appearanceIds, variantIds }
  })
}

export interface Box {
  x: number
  y: number
  width: number
  height: number
}

/**
 * A preview as the partner renders the calibration design: an opaque magenta box on a grey
 * garment. `holed` leaves a 100 × 100 patch of garment in the middle; `antiAliased` draws a
 * translucent one-pixel rim around the box.
 */
export async function magentaBoxPng(
  box: Box,
  { edge = 1000, holed = false, antiAliased = false } = {},
): Promise<Buffer> {
  const pixels = Buffer.alloc(edge * edge * 4)
  const paint = (x: number, y: number, rgba: readonly number[]) => {
    if (x < 0 || y < 0 || x >= edge || y >= edge) return
    pixels.set(rgba, (y * edge + x) * 4)
  }
  for (let y = 0; y < edge; y++) for (let x = 0; x < edge; x++) paint(x, y, [200, 200, 200, 255])
  if (antiAliased) {
    for (let y = box.y - 1; y <= box.y + box.height; y++) {
      for (let x = box.x - 1; x <= box.x + box.width; x++) paint(x, y, [255, 0, 255, 128])
    }
  }
  for (let y = box.y; y < box.y + box.height; y++) {
    for (let x = box.x; x < box.x + box.width; x++) paint(x, y, [255, 0, 255, 255])
  }
  if (holed) {
    const left = box.x + Math.floor(box.width / 2) - 50
    const top = box.y + Math.floor(box.height / 2) - 50
    for (let y = top; y < top + 100; y++)
      for (let x = left; x < left + 100; x++) paint(x, y, [200, 200, 200, 255])
  }
  return sharp(pixels, { raw: { width: edge, height: edge, channels: 4 } })
    .png()
    .toBuffer()
}
