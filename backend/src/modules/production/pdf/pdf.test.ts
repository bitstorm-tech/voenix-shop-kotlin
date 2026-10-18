import { beforeAll, describe, expect, test } from 'bun:test'
import { mkdtemp, readdir, readFile, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { PDFDict, PDFDocument, PDFName, PDFStream } from 'pdf-lib'
import { getDocument } from 'pdfjs-dist/legacy/build/pdf.mjs'
import sharp from 'sharp'
import { rejectionOf } from '@test-support/values'
import { line, productionData, writeImage } from '../test-fixtures'
import type { ProductionItem, ProductionLine, ProductionOrder } from '../orders'
import { printImagePng } from '../spod/print-image'
import { createArtifactStore } from './artifact-store'
import { createProductionPdfGenerator } from './generator'
import { renderProductionPdf, sha256Hex } from './renderer'

const MM_PER_PT = 25.4 / 72
const IMAGE = PDFName.of('Image')
let directory = ''
let png = ''
let webp = ''

beforeAll(async () => {
  directory = await mkdtemp(join(tmpdir(), 'production-pdf-test-'))
  png = await writeImage(directory, 'print.png')
  webp = await writeImage(directory, 'print.webp', 'webp', 300, 100)
})

/** A line routed to `supplierId` today. */
const at = (supplierId: number | null, overrides: Partial<ProductionLine>) => ({
  ...line(overrides),
  supplierId,
})

/** An order whose lines all belong to supplier 1 unless a line says otherwise. */
function order(
  lines: (ProductionLine & { supplierId?: number | null })[],
  orderId = 42,
): ProductionOrder {
  return {
    ...productionData(orderId, []),
    items: lines.map(
      (item): ProductionItem => ({
        ...item,
        supplierId: item.supplierId === undefined ? 1 : item.supplierId,
        spodProduct: null,
        catalogVariantName: item.variantName,
      }),
    ),
  }
}

async function rendered(source: ProductionOrder, supplierId = 1) {
  const result = await renderProductionPdf(source, supplierId)
  if (result.kind !== 'rendered') throw new Error(`Rendering failed with ${result.error}`)
  return result.pdf
}

/** The text of one page grouped by its direction in degrees: 0 across, 90 up, -90 down. */
async function textByDirection(bytes: Uint8Array, pageNumber: number) {
  const document = await getDocument({ data: new Uint8Array(bytes) }).promise
  const content = await (await document.getPage(pageNumber)).getTextContent()
  const byDirection = new Map<number, string>()
  for (const item of content.items) {
    if (!('str' in item) || item.str === '') continue
    const [a = 0, b = 0] = item.transform as number[]
    const direction = Math.round((Math.atan2(b, a) * 180) / Math.PI)
    byDirection.set(direction, `${byDirection.get(direction) ?? ''}${item.str}`)
  }
  return new Map([...byDirection].map(([direction, text]) => [direction, text.trim()]))
}

/** Page sizes in millimetres and the number of embedded images per page. */
async function pages(bytes: Uint8Array) {
  const document = await PDFDocument.load(bytes)
  return document.getPages().map((page) => {
    const xObjects = page.node.Resources()?.lookupMaybe(PDFName.of('XObject'), PDFDict)
    const images = (xObjects?.keys() ?? []).filter((key) => {
      const object = xObjects?.lookup(key)
      return object instanceof PDFStream && object.dict.get(PDFName.of('Subtype')) === IMAGE
    })
    return {
      widthMm: Math.round(page.getWidth() * MM_PER_PT),
      heightMm: Math.round(page.getHeight() * MM_PER_PT),
      images: images.length,
    }
  })
}

describe('renderProductionPdf', () => {
  test('an address page, then one page per physical unit with the stable name and digest', async () => {
    const pdf = await rendered(
      order([
        line({ imagePath: png, quantity: 2 }),
        line({ imagePath: webp, articleName: 'Becher', variantName: 'Schwarz' }),
        at(2, { imagePath: png }),
      ]),
    )
    expect(pdf.fileName).toBe('ORD-42.pdf')
    expect(pdf.sha256).toBe(sha256Hex(pdf.bytes))
    expect(new TextDecoder().decode(pdf.bytes.slice(0, 5))).toBe('%PDF-')
    expect(await pages(pdf.bytes)).toEqual([
      { widthMm: 239, heightMm: 99, images: 0 },
      { widthMm: 239, heightMm: 99, images: 1 },
      { widthMm: 239, heightMm: 99, images: 1 },
      { widthMm: 239, heightMm: 99, images: 1 },
    ])
  })

  test('the address page shows the address and the rotated order label; Unicode survives', async () => {
    const pdf = await rendered(order([line({ imagePath: png })]))
    const text = await textByDirection(pdf.bytes, 1)
    expect(text.get(0)).toContain('Erika Mustermann')
    expect(text.get(0)).toContain('Heidestraße 17')
    expect(text.get(0)).toContain('51147 Köln')
    expect(text.get(90)).toBe('ORD-42')
  })

  test('item pages carry the index label upwards and the article text downwards', async () => {
    const pdf = await rendered(
      order([
        line({ imagePath: png, quantity: 2 }),
        line({ imagePath: png, supplierArticleNumber: ' ', variantName: 'Schwarz' }),
      ]),
    )
    const first = await textByDirection(pdf.bytes, 2)
    expect(first.get(90)).toBe('ORD-42 (1/3)')
    expect(first.get(-90)).toBe('Zaubertasse | SUP-001 | Weiß')
    const third = await textByDirection(pdf.bytes, 4)
    expect(third.get(90)).toBe('ORD-42 (3/3)')
  })

  test('an item overrides the page size with its document format', async () => {
    const pdf = await rendered(
      order([
        line({
          imagePath: png,
          documentFormatWidthMm: 210,
          documentFormatHeightMm: 120,
          printTemplateWidthMm: 180,
          printTemplateHeightMm: 80,
          documentFormatMarginBottomMm: 5,
        }),
      ]),
    )
    expect((await pages(pdf.bytes))[1]).toEqual({ widthMm: 210, heightMm: 120, images: 1 })
  })

  test('bad sources are typed failures, never blank pages', async () => {
    const unreadable = join(directory, 'broken.png')
    await writeFile(unreadable, 'not an image')
    const failure = async (lines: Parameters<typeof order>[0], supplierId = 1) =>
      renderProductionPdf(order(lines), supplierId)
    expect(await failure([line({ imagePath: null })])).toEqual({
      kind: 'failed',
      error: 'MISSING_IMAGE',
    })
    expect(await failure([line({ imagePath: join(directory, 'absent.png') })])).toMatchObject({
      error: 'MISSING_IMAGE',
    })
    expect(await failure([line({ imagePath: unreadable })])).toMatchObject({
      error: 'UNREADABLE_IMAGE',
    })
    expect(await failure([line({ imagePath: png, quantity: 0 })])).toMatchObject({
      error: 'INVALID_SOURCE',
    })
    expect(await failure([line({ imagePath: png, printTemplateWidthMm: -1 })])).toMatchObject({
      error: 'INVALID_SOURCE',
    })
    expect(await failure([line({ imagePath: png })], 9)).toMatchObject({ error: 'INVALID_SOURCE' })
  })
})

describe('productionPdfGenerator', () => {
  const generatorOf = (source: ProductionOrder | null) =>
    createProductionPdfGenerator((orderId) =>
      Promise.resolve(
        source === null || source.orderId !== orderId
          ? { kind: 'failed', code: 'SOURCE_NOT_FOUND' }
          : { kind: 'found', order: source },
      ),
    )

  test('one separate PDF per supplier in first-appearance order', async () => {
    const result = await generatorOf(
      order([
        at(2, { imagePath: png }),
        at(1, { imagePath: png }),
        at(2, { imagePath: png, quantity: 2 }),
      ]),
    ).generate(42)
    if (result.kind !== 'generated') throw new Error('Generation failed')
    expect(result.documents.map((document) => document.supplierId)).toEqual([2, 1])
    expect(result.documents[0]?.mediaType).toBe('application/pdf')
    expect((await pages(result.documents[0]?.bytes ?? new Uint8Array())).length).toBe(4)
  })

  test('an unknown order, a line without supplier, and one failing supplier', async () => {
    expect(await generatorOf(null).generate(42)).toEqual({ kind: 'orderNotFound' })
    expect(await generatorOf(order([at(null, { imagePath: png })])).generate(42)).toEqual({
      kind: 'failed',
      error: 'INVALID_SOURCE',
    })
    expect(
      await generatorOf(order([line({ imagePath: png }), at(2, { imagePath: null })])).generate(42),
    ).toEqual({ kind: 'failed', error: 'MISSING_IMAGE' })
  })
})

describe('the artifact store', () => {
  test('writes under the job-scoped path without leftovers, replaces, and verifies the digest', async () => {
    const root = join(directory, 'artifacts')
    const store = await createArtifactStore(root)
    const bytes = new TextEncoder().encode('%PDF-1.7 first')
    await store.write(7, 'ORD-1.pdf', bytes)
    await store.write(7, 'ORD-1.pdf', new TextEncoder().encode('%PDF-1.7 second'))
    expect(await readdir(join(root, '7'))).toEqual(['ORD-1.pdf'])
    const second = await readFile(join(root, '7', 'ORD-1.pdf'))
    expect(await store.load(7, 'ORD-1.pdf', sha256Hex(second).toUpperCase())).toEqual({
      kind: 'loaded',
      bytes: new Uint8Array(second),
    })
    expect(await store.load(7, 'ORD-1.pdf', sha256Hex(bytes))).toEqual({
      kind: 'digestMismatch',
      actualSha256: sha256Hex(second),
    })
    expect(await store.load(8, 'ORD-1.pdf', sha256Hex(bytes))).toEqual({ kind: 'missing' })
    expect(await rejectionOf(store.write(7, '../escape.pdf', bytes))).not.toBe('resolved')
  })
})

describe('printImagePng', () => {
  test('a WebP original becomes a PNG of the same size', async () => {
    const result = await printImagePng(webp)
    if (result.kind !== 'converted') throw new Error(result.error)
    const metadata = await sharp(result.png).metadata()
    expect([metadata.format, metadata.width, metadata.height]).toEqual(['png', 300, 100])
  })

  test('the longest edge is capped and the budget halves the image a bounded number of times', async () => {
    const huge = await writeImage(directory, 'huge.webp', 'webp', 5000, 1000)
    const capped = await printImagePng(huge)
    if (capped.kind !== 'converted') throw new Error(capped.error)
    expect((await sharp(capped.png).metadata()).width).toBe(4096)

    const noisy = join(directory, 'noise.png')
    const noise = Buffer.from(Array.from({ length: 256 * 256 * 3 }, () => Math.random() * 256))
    await sharp(noise, { raw: { width: 256, height: 256, channels: 3 } })
      .png()
      .toFile(noisy)
    const halved = await printImagePng(noisy, 60_000)
    if (halved.kind !== 'converted') throw new Error(halved.error)
    expect((await sharp(halved.png).metadata()).width).toBe(128)
    expect(await printImagePng(noisy, 10)).toEqual({
      kind: 'failed',
      error: 'PRINT_IMAGE_TOO_LARGE',
    })
  })

  test('a missing image and an unreadable file are bounded codes', async () => {
    const broken = join(directory, 'broken.webp')
    await writeFile(broken, 'RIFF but not really')
    expect(await printImagePng(null)).toEqual({ kind: 'failed', error: 'PRINT_IMAGE_MISSING' })
    expect(await printImagePng(join(directory, 'nothing.webp'))).toMatchObject({
      error: 'PRINT_IMAGE_MISSING',
    })
    expect(await printImagePng(broken)).toMatchObject({ error: 'PRINT_IMAGE_UNREADABLE' })
  })
})
