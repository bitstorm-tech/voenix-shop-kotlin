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
import { calibrationColorAt } from './spod-article/calibration'
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
 * A SPOD article as a sync run writes it: identity, row, colours with their pictures, one variant
 * per colour × size, the first one the default, and the size chart measurements. A size's SPOD id
 * is its place in the colour's `sizes`, from 1. `framed: false` leaves the frame and the hotspot
 * unmeasured. Answers the ids of the article, its colours, and its variants in order.
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
    sizeMeasurements = [],
  }: {
    destinationId: number
    supplierId: number
    spodArticleId?: string
    name?: string
    environment?: string
    framed?: boolean
    sizeChart?: string | null
    colors?: SeededSpodColor[]
    sizeMeasurements?: { sizeId: number; name: string; valueMm: number }[]
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
    for (const measurement of sizeMeasurements) {
      await trx
        .insertInto('article_spod_size_measurements')
        .values({
          article_id: id,
          spod_size_id: measurement.sizeId,
          name: measurement.name,
          value_mm: measurement.valueMm,
        })
        .execute()
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

export interface CalibrationPreviewOptions {
  edge?: number
  garmentRgb?: readonly [number, number, number]
  visibleRadius?: number
  /** Alpha in `[0, 1]` for pixel `(x, y)` inside `box`; defaults to `1` (or `visibleRadius`). */
  visibleAt?: (x: number, y: number, box: Box) => number
  /** Multiplicative lighting/shading `[r, g, b]` applied on top of both garment and print. */
  shadingAt?: (x: number, y: number) => readonly [number, number, number]
  /** Additive specular highlight `[0..255]` applied on top of both garment and print. */
  highlightAt?: (x: number, y: number) => number
  /** Gamma applied during compositing (`1` = linear, `2.2` = sRGB-like). */
  gamma?: number
  /** Rotates the printed pattern around the box centre (degrees), to test distorted mockups. */
  rotationDeg?: number
  /** Transparent outer border whose RGB bytes differ between preview A and preview B. */
  transparentBorder?: number
  /** Draws a single-pixel noise speck outside the print area on preview B. */
  noiseSpeck?: boolean
}

/**
 * Renders one complementary calibration preview (`variant: 'a' | 'b'`) of `box` on a garment.
 * Supports shading, specular highlights, arbitrary garment colours, gamma, circular/custom masks,
 * rotation, transparent borders with differing RGB, and canvas overhang.
 */
export async function calibrationPreviewPng(
  box: Box,
  variant: 'a' | 'b',
  options: CalibrationPreviewOptions = {},
): Promise<Buffer> {
  const edge = options.edge ?? 1000
  const garmentRgb = options.garmentRgb ?? GARMENT_RGB
  const visibleRadius = options.visibleRadius ?? Infinity
  const gamma = options.gamma ?? 1
  const rad = ((options.rotationDeg ?? 0) * Math.PI) / 180
  const cosR = Math.cos(rad)
  const sinR = Math.sin(rad)
  const border = options.transparentBorder ?? 0
  const pixels = Buffer.alloc(edge * edge * 4)

  const toLin = (value: number) =>
    gamma === 1 ? value : Math.pow(Math.max(0, Math.min(1, value)), gamma)
  const fromLin = (value: number) =>
    gamma === 1 ? value : Math.pow(Math.max(0, Math.min(1, value)), 1 / gamma)

  const cx = box.x + box.width / 2
  const cy = box.y + box.height / 2

  for (let y = 0; y < edge; y++) {
    for (let x = 0; x < edge; x++) {
      const offset = (y * edge + x) * 4
      if (x < border || x >= edge - border || y < border || y >= edge - border) {
        const transparentRgb = variant === 'a' ? 0 : 255
        pixels[offset] = transparentRgb
        pixels[offset + 1] = transparentRgb
        pixels[offset + 2] = transparentRgb
        pixels[offset + 3] = 0
        continue
      }

      const shade = options.shadingAt ? options.shadingAt(x, y) : ([1, 1, 1] as const)
      const highlight = options.highlightAt ? options.highlightAt(x, y) : 0
      const bg = [
        Math.min(255, Math.round(garmentRgb[0] * shade[0] + highlight)),
        Math.min(255, Math.round(garmentRgb[1] * shade[1] + highlight)),
        Math.min(255, Math.round(garmentRgb[2] * shade[2] + highlight)),
      ] as const

      let alpha = 0
      let u = -1
      let v = -1
      if (box.width > 0 && box.height > 0) {
        const dx = x + 0.5 - cx
        const dy = y + 0.5 - cy
        const rx = rad === 0 ? dx : dx * cosR + dy * sinR
        const ry = rad === 0 ? dy : -dx * sinR + dy * cosR
        u = rx / box.width + 0.5
        v = ry / box.height + 0.5
        if (u >= 0 && u <= 1 && v >= 0 && v <= 1) {
          if (options.visibleAt) {
            alpha = options.visibleAt(x, y, box)
          } else if (Number.isFinite(visibleRadius)) {
            const dist = Math.hypot(dx, dy)
            alpha = Math.max(0, Math.min(1, visibleRadius - dist + 0.5))
          } else {
            alpha = 1
          }
        }
      }

      if (alpha <= 0) {
        pixels[offset] = bg[0]
        pixels[offset + 1] = bg[1]
        pixels[offset + 2] = bg[2]
        pixels[offset + 3] = 255
        continue
      }

      const design = calibrationColorAt(u, v, variant)
      for (let channel = 0; channel < 3; channel++) {
        const dLin = toLin((design[channel] ?? 0) / 255)
        const bLin = toLin((bg[channel] ?? 0) / 255)
        const lit = dLin * (shade[channel] ?? 1) + highlight / 255
        const out = fromLin(bLin * (1 - alpha) + lit * alpha)
        pixels[offset + channel] = Math.max(0, Math.min(255, Math.round(out * 255)))
      }
      pixels[offset + 3] = 255
    }
  }

  if (options.noiseSpeck === true && variant === 'b' && edge > 10) {
    const speck = (5 * edge + 5) * 4
    pixels[speck] = 255
    pixels[speck + 1] = 255
    pixels[speck + 2] = 255
    pixels[speck + 3] = 255
  }

  return sharp(pixels, { raw: { width: edge, height: edge, channels: 4 } })
    .png()
    .toBuffer()
}

/** Renders both complementary previews (`a` and `b`) for one synthetic calibration scenario. */
export async function calibrationPreviewPair(
  box: Box,
  options: CalibrationPreviewOptions = {},
): Promise<{ a: Buffer; b: Buffer }> {
  const [a, b] = await Promise.all([
    calibrationPreviewPng(box, 'a', options),
    calibrationPreviewPng(box, 'b', options),
  ])
  return { a, b }
}

const GARMENT_RGB = [200, 200, 200] as const
