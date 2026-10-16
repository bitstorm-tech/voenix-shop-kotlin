import { createHash } from 'node:crypto'
import { readFile, stat } from 'node:fs/promises'
import * as fontkit from '@pdf-lib/fontkit'
import {
  degrees,
  PDFDocument,
  popGraphicsState,
  pushGraphicsState,
  setLineWidth,
  setTextRenderingMode,
  TextRenderingMode,
  type PDFFont,
  type PDFImage,
  type PDFPage,
} from 'pdf-lib'
import sharp from 'sharp'
import { createLogger, errorClass } from '@/platform/logging'
import { orderLabel, pdfFileName, type ProductionItem, type ProductionOrder } from '../orders'

/**
 * The bounded reasons a production PDF cannot be rendered. Every one is retryable: an image
 * appears, a measurement is corrected. They are also the generation error codes of a job.
 */
export type ProductionPdfError =
  /** A line has no print image, or the file does not exist. */
  | 'MISSING_IMAGE'
  /** The image file exists but cannot be decoded. */
  | 'UNREADABLE_IMAGE'
  /** No line for the supplier, a non-positive quantity or measurement, or a line without supplier. */
  | 'INVALID_SOURCE'
  /** The renderer failed unexpectedly; the details go to the log as a class name only. */
  | 'RENDER_FAILURE'

/** One finished PDF: the stable producer-facing name, the bytes, and their lowercase hex SHA-256. */
export interface ProductionPdf {
  fileName: string
  bytes: Uint8Array
  sha256: string
}

export type RenderResult =
  | { kind: 'rendered'; pdf: ProductionPdf }
  | { kind: 'failed'; error: ProductionPdfError }

/** The physical layout of the legacy document. */
const PAGE_WIDTH_MM = 239
const PAGE_HEIGHT_MM = 99
const MM_PER_PT = 25.4 / 72
const SIDE_COLUMN_WIDTH_PT = 20
const SIDE_COLUMN_WIDTH_MM = SIDE_COLUMN_WIDTH_PT * MM_PER_PT
const ORDER_LABEL_FONT_SIZE = 9
const NAME_FONT_SIZE = 12
const ADDRESS_FONT_SIZE = 11
const ITEM_INFO_FONT_SIZE = 8
const LINE_HEIGHT_FACTOR = 1.2
/** The font has no bold face; fill plus a stroke this thick approximates one. */
const BOLD_STROKE_FACTOR = 0.03

const mmToPt = (mm: number) => mm / MM_PER_PT

/**
 * Liberation Sans, the font the Kotlin renderer took from the PDFBox jar: extended Latin plus
 * Cyrillic. It is embedded as a Type0 font, so every name a customer typed prints.
 */
const FONT_URL = new URL('./fonts/LiberationSans-Regular.ttf', import.meta.url)
let fontBytes: Promise<Uint8Array> | undefined
const loadFontBytes = () => (fontBytes ??= readFile(FONT_URL))

const logger = createLogger('production-pdf')

export const sha256Hex = (bytes: Uint8Array): string =>
  createHash('sha256').update(bytes).digest('hex')

/**
 * Renders the PDF of one supplier's share of an order: an address page of 239 × 99 mm, then one
 * page per physical unit with a stable 1-based index inside the share, the print image, and the
 * article text. A missing or unreadable image is a typed failure, never a blank page.
 */
export async function renderProductionPdf(
  order: ProductionOrder,
  supplierId: number,
): Promise<RenderResult> {
  const items = order.items.filter((item) => item.supplierId === supplierId)
  const invalid = items.length === 0 ? 'INVALID_SOURCE' : await firstItemError(items)
  if (invalid !== null) return { kind: 'failed', error: invalid }
  try {
    const pngs = await decodeImages(items)
    if (pngs === null) return { kind: 'failed', error: 'UNREADABLE_IMAGE' }
    const bytes = await renderDocument(order, items, pngs)
    return {
      kind: 'rendered',
      pdf: { fileName: pdfFileName(order.orderId), bytes, sha256: sha256Hex(bytes) },
    }
  } catch (error) {
    logger.error(
      { orderId: order.orderId, errorClass: errorClass(error) },
      'Production PDF rendering failed',
    )
    return { kind: 'failed', error: 'RENDER_FAILURE' }
  }
}

async function firstItemError(items: ProductionItem[]): Promise<ProductionPdfError | null> {
  for (const item of items) {
    const measurements = [
      item.printTemplateWidthMm,
      item.printTemplateHeightMm,
      item.documentFormatWidthMm,
      item.documentFormatHeightMm,
    ]
    const margin = item.documentFormatMarginBottomMm
    const pageWidthMm = item.documentFormatWidthMm ?? PAGE_WIDTH_MM
    if (
      !Number.isInteger(item.quantity) ||
      item.quantity < 1 ||
      measurements.some((value) => value !== null && value <= 0) ||
      (margin !== null && margin < 0) ||
      pageWidthMm <= 2 * SIDE_COLUMN_WIDTH_MM
    ) {
      return 'INVALID_SOURCE'
    }
    if (item.imagePath === null || !(await isFile(item.imagePath))) return 'MISSING_IMAGE'
  }
  return null
}

const isFile = (path: string) =>
  stat(path).then(
    (stats) => stats.isFile(),
    () => false,
  )

/**
 * Every distinct print image as PNG, or `null` when one of them cannot be decoded. sharp reads
 * WebP, PNG, and JPEG alike, so every source takes the same path into pdf-lib's PNG embedder.
 */
async function decodeImages(items: ProductionItem[]): Promise<Map<string, Buffer> | null> {
  const pngs = new Map<string, Buffer>()
  for (const item of items) {
    const path = item.imagePath
    if (path === null || pngs.has(path)) continue
    try {
      pngs.set(path, await sharp(path).png().toBuffer())
    } catch (error) {
      logger.warn({ errorClass: errorClass(error) }, 'A production image cannot be decoded')
      return null
    }
  }
  return pngs
}

async function renderDocument(
  order: ProductionOrder,
  items: ProductionItem[],
  pngs: Map<string, Buffer>,
): Promise<Uint8Array> {
  const document = await PDFDocument.create()
  document.registerFontkit(fontkit)
  const bytes = await loadFontBytes()
  const font = await document.embedFont(bytes, { subset: true })
  const metrics = fontkit.create(bytes)
  const text = createTextWriter(
    font,
    metrics.ascent / metrics.unitsPerEm,
    -metrics.descent / metrics.unitsPerEm,
  )
  const images = new Map<string, PDFImage>()
  for (const [path, png] of pngs) images.set(path, await document.embedPng(png))

  const address = document.addPage([mmToPt(PAGE_WIDTH_MM), mmToPt(PAGE_HEIGHT_MM)])
  text.leftColumn(address, orderLabel(order.orderId), ORDER_LABEL_FONT_SIZE)
  text.centeredBlock(address, [
    {
      text: `${order.shippingFirstName} ${order.shippingLastName}`,
      size: NAME_FONT_SIZE,
      bold: true,
    },
    {
      text: `${order.shippingStreet} ${order.shippingHouseNumber}`,
      size: ADDRESS_FONT_SIZE,
      bold: false,
    },
    {
      text: `${order.shippingPostalCode} ${order.shippingCity}`,
      size: ADDRESS_FONT_SIZE,
      bold: false,
    },
    { text: order.shippingCountry, size: ADDRESS_FONT_SIZE, bold: false },
  ])

  const units = items.flatMap((item) => Array.from({ length: item.quantity }, () => item))
  units.forEach((item, index) => {
    const widthMm = item.documentFormatWidthMm ?? PAGE_WIDTH_MM
    const heightMm = item.documentFormatHeightMm ?? PAGE_HEIGHT_MM
    const page = document.addPage([mmToPt(widthMm), mmToPt(heightMm)])
    const label = `${orderLabel(order.orderId)} (${String(index + 1)}/${String(units.length)})`
    text.leftColumn(page, label, ORDER_LABEL_FONT_SIZE)
    const image = item.imagePath === null ? undefined : images.get(item.imagePath)
    if (image === undefined) throw new Error('A validated line lost its image')
    drawItemImage(page, image, item, widthMm, heightMm)
    const number = item.supplierArticleNumber ?? ''
    const parts = [item.articleName, ...(number === '' ? [] : [number]), item.variantName]
    text.rightColumn(page, parts.join(' | '), ITEM_INFO_FONT_SIZE)
  })
  return document.save()
}

/**
 * The text drawing of one document: the rotated side-column labels and the centered address block.
 * `ascent` and `descent` are the font's metrics per point of size, the descent positive.
 */
function createTextWriter(font: PDFFont, ascent: number, descent: number) {
  const width = (text: string, size: number) => font.widthOfTextAtSize(text, size)
  const halfGlyph = (size: number) => ((ascent - descent) * size) / 2
  return {
    /** Reads bottom-to-top, centered in the left side column. */
    leftColumn: (page: PDFPage, text: string, size: number) => {
      page.drawText(text, {
        x: SIDE_COLUMN_WIDTH_PT / 2 + halfGlyph(size),
        y: (page.getHeight() - width(text, size)) / 2,
        size,
        font,
        rotate: degrees(90),
      })
    },
    /** Reads top-to-bottom, centered in the right side column. */
    rightColumn: (page: PDFPage, text: string, size: number) => {
      page.drawText(text, {
        x: page.getWidth() - SIDE_COLUMN_WIDTH_PT / 2 - halfGlyph(size),
        y: (page.getHeight() + width(text, size)) / 2,
        size,
        font,
        rotate: degrees(-90),
      })
    },
    /** One block centered between the left column and the right page edge. */
    centeredBlock: (page: PDFPage, lines: { text: string; size: number; bold: boolean }[]) => {
      const centerX = SIDE_COLUMN_WIDTH_PT + (page.getWidth() - SIDE_COLUMN_WIDTH_PT) / 2
      const total = lines.reduce((sum, line) => sum + line.size * LINE_HEIGHT_FACTOR, 0)
      let lineTop = (page.getHeight() + total) / 2
      for (const line of lines) {
        const lineHeight = line.size * LINE_HEIGHT_FACTOR
        const gap = (lineHeight - (ascent + descent) * line.size) / 2
        if (line.bold) {
          page.pushOperators(
            pushGraphicsState(),
            setTextRenderingMode(TextRenderingMode.FillAndOutline),
            setLineWidth(line.size * BOLD_STROKE_FACTOR),
          )
        }
        page.drawText(line.text, {
          x: centerX - width(line.text, line.size) / 2,
          y: lineTop - gap - ascent * line.size,
          size: line.size,
          font,
        })
        if (line.bold) page.pushOperators(popGraphicsState())
        lineTop -= lineHeight
      }
    },
  }
}

/**
 * Places the image like the legacy layout: with a print template it is confined to the template
 * width, sits on the bottom margin, and is centered horizontally; without one it is centered in the
 * area between the side columns. Either way it scales to fit, keeping its aspect ratio.
 */
function drawItemImage(
  page: PDFPage,
  image: PDFImage,
  item: ProductionItem,
  pageWidthMm: number,
  pageHeightMm: number,
) {
  const alignBottom = item.printTemplateWidthMm !== null && item.printTemplateHeightMm !== null
  let boxLeft = SIDE_COLUMN_WIDTH_PT
  let boxRight = mmToPt(pageWidthMm) - SIDE_COLUMN_WIDTH_PT
  let boxBottom = 0
  if (alignBottom && item.printTemplateWidthMm !== null) {
    const available = pageWidthMm - 2 * SIDE_COLUMN_WIDTH_MM
    const padding = Math.max(0, (available - item.printTemplateWidthMm) / 2)
    boxLeft = mmToPt(SIDE_COLUMN_WIDTH_MM + padding)
    boxRight = mmToPt(pageWidthMm - SIDE_COLUMN_WIDTH_MM - padding)
    boxBottom = mmToPt(item.documentFormatMarginBottomMm ?? 0)
  }
  const boxWidth = boxRight - boxLeft
  const boxHeight = mmToPt(pageHeightMm) - boxBottom
  const scale = Math.min(boxWidth / image.width, boxHeight / image.height)
  const width = image.width * scale
  const height = image.height * scale
  page.drawImage(image, {
    x: boxLeft + (boxWidth - width) / 2,
    y: alignBottom ? boxBottom : boxBottom + (boxHeight - height) / 2,
    width,
    height,
  })
}
