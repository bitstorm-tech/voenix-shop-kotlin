import { beforeEach, describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { createSpodClient, type SpodCatalogArticle } from '@/modules/spod'
import { testDatabase } from '@test-support/database'
import { jsonOf, signedInHeaders, testApp, testAuth } from '@test-support/http'
import { pngBytes } from '@test-support/values'
import type { SpodCatalogSource, SpodSyncReport } from '../index'
import {
  articleTestModule,
  grossPrice,
  insertCategory,
  insertSpodArticle,
  insertSpodDestination,
  insertSupplier,
  insertVat,
  magentaBoxPng,
  resetArticles,
} from '../test-fixtures'

/**
 * Whole sync runs against PostgreSQL and a stubbed partner behind the real `SpodClient`: the
 * listing, the calibration calls (staging-like views, hotspots, and previews whose magenta box the
 * test draws), and the image CDN. Every request the partner received is recorded in `hits`.
 */

const database = testDatabase()
let supplier: number
let destination: number
beforeEach(async () => {
  await resetArticles(database.db)
  supplier = await insertSupplier(database.db, 'Shirt supplier')
  destination = await insertSpodDestination(database.db, supplier)
})

const CDN = 'https://cdn.example.test'
const TEE = 812
const TEE_FRAME = { leftPct: 28.5, topPct: 18.8, widthPct: 42.5, heightPct: 42.5 }

interface Partner {
  /** The listing pages, by offset; a function so a test can change the catalog between runs. */
  listing: (offset: number) => Response
  holed?: boolean
  viewsRefused?: boolean
  failingDownloads?: Set<string>
  sizeChartUrl?: string
  /** Resolves before the listing answers, so a test can hold a run open. */
  gate?: Promise<void>
}

const json = (body: unknown) =>
  new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } })

function fakePartner(partner: Partner) {
  const hits: string[] = []
  const fetch = async (url: string, init: RequestInit): Promise<Response> => {
    const { hostname, pathname } = new URL(url)
    hits.push(hostname === 'cdn.example.test' ? `cdn:${pathname}` : pathname)
    if (hostname === 'cdn.example.test') {
      if (partner.failingDownloads?.has(pathname)) return new Response('', { status: 404 })
      if (pathname.startsWith('/previews/')) {
        const hotspot = pathname.split('/')[3]?.split(',')[0]
        const box =
          hotspot === 'FULL_FRONT'
            ? { x: 285, y: 188, width: 425, height: 425 }
            : { x: 356, y: 189, width: 284, height: 284 }
        return new Response(await magentaBoxPng(box, { holed: partner.holed ?? false }), {
          headers: { 'Content-Type': 'image/png' },
        })
      }
      return new Response(await pngBytes(), { headers: { 'Content-Type': 'image/png' } })
    }
    if (pathname === '/articles') {
      await partner.gate
      return partner.listing(Number(new URL(url).searchParams.get('offset')))
    }
    if (pathname === '/designs/upload') return json({ designId: 'design-1' })
    const productType = pathname.split('/')[2] ?? ''
    if (pathname.endsWith('/size-chart')) {
      return json({ sizeImageUrl: partner.sizeChartUrl ?? `${CDN}/chart-1.png` })
    }
    if (pathname.endsWith('/views')) {
      if (partner.viewsRefused === true) return new Response('', { status: 500 })
      return json({
        views: [
          {
            id: '1',
            name: 'FRONT',
            hotspots: [{ name: 'FULL_FRONT' }, { name: 'MEDIUM_FRONT' }, { name: 'LEFT_CHEST' }],
            images: [5, 6].map((appearance) => ({
              appearanceId: String(appearance),
              image: `${CDN}/mockups/${productType}/${String(appearance)}.png`,
            })),
          },
          { id: '2', name: 'BACK', hotspots: [{ name: 'FULL_BACK' }], images: [] },
        ],
      })
    }
    if (pathname.includes('/hotspots/design/')) {
      return json({ hotspots: [{ name: 'FULL_FRONT' }, { name: 'MEDIUM_FRONT' }] })
    }
    if (pathname.endsWith('/previews')) {
      const body = JSON.parse(typeof init.body === 'string' ? init.body : '{}') as {
        configurations: { hotspot: string }[]
      }
      const hotspot = body.configurations[0]?.hotspot ?? ''
      return json({
        images: [
          { url: `${CDN}/previews/${productType}/${hotspot}`, viewId: '1', viewName: 'FRONT' },
          { url: `${CDN}/previews/${productType}/back`, viewId: '2', viewName: 'BACK' },
        ],
      })
    }
    return new Response('', { status: 404 })
  }
  return { client: createSpodClient({ fetch, sleep: () => Promise.resolve() }), hits }
}

/** Two colours, Black in M and L with two listing images, White in M with one. */
const shirt = (overrides: Partial<SpodCatalogArticle> = {}): SpodCatalogArticle => ({
  id: 'a-1',
  title: 'Classic Shirt',
  description: 'A shirt',
  variants: [
    variant('v-1', 5, 'Black', '#000000', 1, 'M'),
    variant('v-2', 5, 'Black', '#000000', 2, 'L'),
    variant('v-3', 6, 'White', '#ffffff', 1, 'M'),
  ],
  images: [image('img-1', 5), image('img-2', 5), image('img-3', 6)],
  ...overrides,
})

function variant(
  id: string,
  appearanceId: number,
  appearanceName: string,
  color: string | null,
  sizeId: number,
  sizeName: string,
) {
  return {
    id,
    productTypeId: TEE,
    productTypeName: "Men's T-Shirt",
    appearanceId,
    appearanceName,
    appearanceColorValue: color,
    sizeId,
    sizeName,
    sku: `sku-${id}`,
  }
}

const image = (id: string, appearanceId: number) => ({
  id,
  productId: 0,
  appearanceId,
  perspective: 'FRONT',
  imageUrl: `${CDN}/listing/${id}.png`,
})

const page =
  (articles: SpodCatalogArticle[], count = articles.length) =>
  () =>
    json({ items: articles, count })

const source = (): SpodCatalogSource => ({
  supplierId: supplier,
  access: {
    destinationId: destination,
    environment: 'PRODUCTION',
    accessToken: 't',
    timeoutSeconds: 30,
  },
})

async function syncWith(partner: Partner) {
  const { client, hits } = fakePartner(partner)
  const { article } = await articleTestModule(database.db, testAuth.guestTokens, client)
  const run = async (): Promise<SpodSyncReport> => {
    const result = await article.spodCatalogSync.sync(source())
    if (result.kind !== 'reported') throw new Error('The run was refused as busy')
    return result.report
  }
  return { sync: article.spodCatalogSync, run, hits, routes: article.routes }
}

const storedArticle = () =>
  database.db.selectFrom('article_spod_articles').selectAll().executeTakeFirstOrThrow()
const downloads = (hits: string[]) =>
  hits.filter((hit) => hit.startsWith('cdn:') && !hit.startsWith('cdn:/previews/'))

describe('a first run', () => {
  test('creates the article with its colours, pictures, measured frame, and inactive', async () => {
    const { run, hits } = await syncWith({ listing: page([shirt()]) })
    const report = await run()
    expect(report).toMatchObject({
      status: 'COMPLETED',
      failure: null,
      fetchedArticles: 1,
      warnings: [],
    })
    expect(report.created).toEqual([
      {
        articleId: (await storedArticle()).id,
        spodArticleId: 'a-1',
        name: 'Classic Shirt',
        variantsCreated: 3,
        variantsUpdated: 0,
        variantsDeactivated: 0,
        hotspot: 'FULL_FRONT',
        printFrame: TEE_FRAME,
      },
    ])
    const stored = await storedArticle()
    expect(stored).toMatchObject({
      active: false,
      category_id: null,
      price_id: null,
      spod_hotspot: 'FULL_FRONT',
      print_frame_left_pct: '28.50',
      print_frame_width_pct: '42.50',
      spod_product_type_name: "Men's T-Shirt",
      spod_size_chart_url: `${CDN}/chart-1.png`,
    })
    const { rows } = await sql<{ color: string; role: string; n: number }>`
      SELECT ap.color_name AS color, i.role, count(*)::int AS n
      FROM article_spod_images i JOIN article_spod_appearances ap ON ap.id = i.appearance_id
      GROUP BY ap.color_name, ap.position, i.role ORDER BY ap.position, i.role`.execute(database.db)
    expect(rows).toEqual([
      { color: 'Black', role: 'LISTING', n: 2 },
      { color: 'Black', role: 'MOCKUP', n: 1 },
      { color: 'White', role: 'LISTING', n: 1 },
      { color: 'White', role: 'MOCKUP', n: 1 },
    ])
    const variants = await database.db
      .selectFrom('article_spod_variants')
      .select(['size_label', 'is_default', 'active'])
      .orderBy('id')
      .execute()
    expect(variants).toEqual([
      { size_label: 'M', is_default: true, active: true },
      { size_label: 'L', is_default: false, active: true },
      { size_label: 'M', is_default: false, active: true },
    ])
    expect(hits.filter((hit) => hit === '/designs/upload')).toHaveLength(1)
    expect(hits.filter((hit) => hit.endsWith('/size-chart'))).toHaveLength(1)
    expect(downloads(hits)).toHaveLength(6)
  })
})

describe('an identical second run', () => {
  test('lands every article in unchanged and downloads no picture, while it measures again', async () => {
    const { run, hits } = await syncWith({ listing: page([shirt()]) })
    await run()
    hits.length = 0
    const report = await run()
    expect(report.created).toEqual([])
    expect(report.updated).toEqual([])
    expect(report.unchanged.map((line) => line.spodArticleId)).toEqual(['a-1'])
    expect(report.warnings).toEqual([])
    expect(downloads(hits)).toEqual([])
    expect(hits.filter((hit) => hit === '/designs/upload')).toHaveLength(1)
  })

  test('overwrites the product and the frame, and keeps the shop-owned half', async () => {
    const { run, routes } = await syncWith({ listing: page([shirt()]) })
    await run()
    const vat = await insertVat(database.db)
    const category = await insertCategory(database.db, 'Shirts')
    const { id } = await storedArticle()
    const variant = await database.db
      .selectFrom('article_spod_variants')
      .select('id')
      .orderBy('id')
      .executeTakeFirstOrThrow()
    const activated = await testApp(routes).request(`/api/admin/articles/spod/${String(id)}`, {
      method: 'PUT',
      headers: { ...(await signedInHeaders()), 'content-type': 'application/json' },
      body: JSON.stringify({
        active: true,
        categoryId: category,
        defaultVariantId: variant.id,
        price: grossPrice(vat, 1990),
      }),
    })
    expect(((await jsonOf(activated)) as { active: boolean }).active).toBe(true)
    await database.db
      .updateTable('article_spod_articles')
      .set({ print_frame_left_pct: 11, spod_hotspot: 'MEDIUM_FRONT' })
      .where('id', '=', id)
      .execute()
    const report = await (
      await syncWith({ listing: page([shirt({ title: 'Renamed Shirt' })]) })
    ).run()
    expect(report.updated.map((line) => line.name)).toEqual(['Renamed Shirt'])
    expect(await storedArticle()).toMatchObject({
      name: 'Renamed Shirt',
      active: true,
      category_id: category,
      spod_hotspot: 'FULL_FRONT',
      print_frame_left_pct: '28.50',
    })
  })
})

describe('the sweep', () => {
  test('marks a dropped article once, and a reappearing one stays inactive with a warning', async () => {
    let catalog = [shirt()]
    const { run } = await syncWith({ listing: () => page(catalog)() })
    await run()
    catalog = []
    const dropped = await run()
    expect(dropped.deactivated).toMatchObject([{ spodArticleId: 'a-1', variantsDeactivated: 3 }])
    expect((await storedArticle()).spod_missing_since).not.toBeNull()
    expect((await run()).deactivated).toEqual([])
    catalog = [shirt()]
    const back = await run()
    expect(back.updated.map((line) => line.spodArticleId)).toEqual(['a-1'])
    expect(back.warnings.map((warning) => warning.code)).toContain('ARTICLE_REAPPEARED')
    expect(await storedArticle()).toMatchObject({ spod_missing_since: null, active: false })
  })

  test('an article of the other environment counts as missing even when its id is listed', async () => {
    await insertSpodArticle(database.db, {
      destinationId: destination,
      supplierId: supplier,
      environment: 'STAGING',
    })
    const report = await (await syncWith({ listing: page([shirt()]) })).run()
    expect(report.created.map((line) => line.spodArticleId)).toEqual(['a-1'])
    expect(report.deactivated.map((line) => line.spodArticleId)).toEqual(['a-1'])
  })
})

describe('a listing that cannot be read to the end', () => {
  test('fails the run and writes and sweeps nothing', async () => {
    await insertSpodArticle(database.db, {
      destinationId: destination,
      supplierId: supplier,
      spodArticleId: 'old',
    })
    const failures = [
      () => json({ items: [shirt()] }),
      () => json({ items: [shirt(), shirt()], count: 3 }),
      (offset: number) =>
        offset === 0
          ? json({ items: [shirt()], count: 3 })
          : json({ items: [shirt({ id: 'a-2' })], count: 4 }),
      (offset: number) =>
        offset === 0 ? json({ items: [shirt()], count: 2 }) : json({ items: [], count: 2 }),
    ]
    for (const listing of failures) {
      const report = await (await syncWith({ listing })).run()
      expect(report).toMatchObject({
        status: 'FAILED',
        failure: 'PROVIDER_ANSWER_UNREADABLE',
        deactivated: [],
      })
    }
    const refused = await (
      await syncWith({ listing: () => new Response('', { status: 503 }) })
    ).run()
    expect(refused).toMatchObject({ status: 'FAILED', failure: 'PROVIDER_UNAVAILABLE' })
    const rows = await database.db
      .selectFrom('article_spod_articles')
      .select(['spod_article_id', 'spod_missing_since'])
      .execute()
    expect(rows).toEqual([{ spod_article_id: 'old', spod_missing_since: null }])
  })
})

describe('the calibration', () => {
  test('a box with a hole writes the article without frame and hotspot under CALIBRATION_FAILED', async () => {
    const report = await (await syncWith({ listing: page([shirt()]), holed: true })).run()
    expect(report.created).toMatchObject([{ hotspot: null, printFrame: null }])
    expect(report.warnings).toContainEqual({
      code: 'CALIBRATION_FAILED',
      spodArticleId: 'a-1',
      detail: `The print frame of product type ${String(TEE)} could not be measured (FULL_FRONT: LOW_FILL, MEDIUM_FRONT: LOW_FILL)`,
    })
    expect(await storedArticle()).toMatchObject({ spod_hotspot: null, print_frame_left_pct: null })
  })

  test('refused views skip the article under CALIBRATION_UNAVAILABLE without a download', async () => {
    const { run, hits } = await syncWith({ listing: page([shirt()]), viewsRefused: true })
    const report = await run()
    expect(report.failed).toMatchObject([{ articleId: null, spodArticleId: 'a-1' }])
    expect(report.warnings).toEqual([
      {
        code: 'CALIBRATION_UNAVAILABLE',
        spodArticleId: 'a-1',
        detail: 'views: PROVIDER_UNAVAILABLE',
      },
    ])
    expect(downloads(hits)).toEqual([])
    expect(await database.db.selectFrom('article_spod_articles').select('id').execute()).toEqual([])
  })
})

describe('degraded partner data', () => {
  test('an unreadable colour and a failed image deactivate only what they concern', async () => {
    const article = shirt({
      variants: [
        variant('v-1', 5, 'Black', '#000000', 1, 'M'),
        variant('v-3', 6, 'White', 'not a colour', 1, 'M'),
      ],
    })
    const report = await (
      await syncWith({
        listing: page([article]),
        failingDownloads: new Set(['/listing/img-2.png']),
      })
    ).run()
    expect(report.warnings.map((warning) => warning.code)).toEqual([
      'IMAGE_DOWNLOAD_FAILED',
      'COLOR_VALUE_UNREADABLE',
    ])
    const { rows } = await sql<{ color_hex: string; active: boolean }>`
      SELECT ap.color_hex, v.active FROM article_spod_variants v
      JOIN article_spod_appearances ap ON ap.id = v.appearance_id ORDER BY v.id`.execute(
      database.db,
    )
    expect(rows).toEqual([
      { color_hex: '#000000', active: true },
      { color_hex: '#cccccc', active: false },
    ])
  })

  test('an article of two product types or without a printable variant is skipped, the rest written', async () => {
    const mixed = shirt({
      id: 'mixed',
      variants: [
        variant('v-1', 5, 'Black', '#000000', 1, 'M'),
        { ...variant('v-9', 5, 'Black', '#000000', 2, 'L'), productTypeId: 999 },
      ],
    })
    const empty = shirt({
      id: 'empty',
      variants: [{ ...variant('v-1', 5, 'Black', '#000000', 1, 'M'), sizeId: 0 }],
    })
    const report = await (await syncWith({ listing: page([mixed, empty, shirt()]) })).run()
    expect(report.failed.map((line) => line.spodArticleId)).toEqual(['mixed', 'empty'])
    expect(report.warnings.map((warning) => warning.code)).toEqual([
      'MIXED_PRODUCT_TYPES',
      'ARTICLE_WITHOUT_VARIANTS',
    ])
    expect(report.created.map((line) => line.spodArticleId)).toEqual(['a-1'])
  })
})

describe('one destination at a time', () => {
  test('a second run of a syncing destination is refused as busy', async () => {
    let open: () => void = () => undefined
    const gate = new Promise<void>((resolve) => {
      open = resolve
    })
    const { sync } = await syncWith({ listing: page([]), gate })
    const first = sync.sync(source())
    expect(await sync.sync(source())).toEqual({ kind: 'busy' })
    open()
    expect((await first).kind).toBe('reported')
    expect((await sync.sync(source())).kind).toBe('reported')
  })
})
