import { readFile } from 'node:fs/promises'
import * as fontkit from '@pdf-lib/fontkit'
import { PDFDocument, rgb, type PDFFont, type PDFPage } from 'pdf-lib'
import { roundHalfUp } from '@/platform/money'
import type { InvoiceOrder } from './repository'

/** The seller every invoice names (§ 14 Abs. 4 UStG); the same data the imprint page shows. */
const SELLER = {
  name: 'JoTo AI GbR',
  representatives: 'Josef Bauer & Thomas Pränger',
  street: 'Wallbergstr. 18',
  city: '83607 Holzkirchen',
  country: 'Deutschland',
  email: 'write-us@voenix.shop',
  vatId: 'DE455554144',
}

/** One VAT rate of an invoice: the gross amount charged at it and the net and tax it contains. */
export interface VatBreakdown {
  percent: number
  grossCents: number
  netCents: number
  taxCents: number
}

/**
 * The tax of an invoice, one entry per rate, highest rate first. Prices are gross. Shipping and
 * the discount follow the goods they belong to, so both are split over the rates in proportion to
 * the goods' gross amounts, the last rate taking the rounding remainder; the entries therefore add
 * up to the order's total exactly.
 */
export function vatBreakdown(
  order: Pick<InvoiceOrder, 'lines' | 'shippingCostCents' | 'discountCents'>,
): VatBreakdown[] {
  const goods = new Map<number, number>()
  for (const line of order.lines) {
    const gross = (line.priceCents + line.promptPriceCents) * line.quantity
    goods.set(line.vatPercent, (goods.get(line.vatPercent) ?? 0) + gross)
  }
  const rates = [...goods].sort(([a], [b]) => b - a)
  const goodsTotal = rates.reduce((sum, [, gross]) => sum + gross, 0)
  let shippingLeft = order.shippingCostCents
  let discountLeft = order.discountCents
  return rates.map(([percent, gross], index) => {
    const last = index === rates.length - 1
    const share = (amount: number) =>
      goodsTotal === 0 ? 0 : roundHalfUp((amount * gross) / goodsTotal)
    const shipping = last ? shippingLeft : share(order.shippingCostCents)
    const discount = last ? discountLeft : share(order.discountCents)
    shippingLeft -= shipping
    discountLeft -= discount
    const grossCents = gross + shipping - discount
    const netCents = roundHalfUp((grossCents * 100) / (100 + percent))
    return { percent, grossCents, netCents, taxCents: grossCents - netCents }
  })
}

// The production documents embed the same font: extended Latin, so every name a customer typed prints.
const FONT_URL = new URL('../production/pdf/fonts/LiberationSans-Regular.ttf', import.meta.url)
let fontBytes: Promise<Uint8Array> | undefined
const loadFontBytes = () => (fontBytes ??= readFile(FONT_URL))

const EURO = new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR' })
const euro = (cents: number) => EURO.format(cents / 100)
/** `YYYY-MM-DD` to the German `DD.MM.YYYY`. */
const germanDate = (day: string) => day.split('-').reverse().join('.')

const PAGE: [number, number] = [595.28, 841.89]
const LEFT = 57
const RIGHT = PAGE[0] - 57
const BOTTOM = 90
const GREY = rgb(0.4, 0.4, 0.4)
/** The right edges of the amount columns and the left edge of the description. */
const COLUMNS = {
  position: LEFT,
  description: LEFT + 28,
  quantity: 345,
  unit: 420,
  vat: 465,
  total: RIGHT,
}

/** The invoice of one paid order as a PDF: number and date, both parties, the lines, the tax. */
export async function renderInvoice(order: InvoiceOrder): Promise<Uint8Array> {
  const document = await PDFDocument.create()
  document.registerFontkit(fontkit)
  const font = await document.embedFont(await loadFontBytes(), { subset: true })
  document.setTitle(`Rechnung ${String(order.orderId)}`)
  document.setAuthor(SELLER.name)

  let page = document.addPage(PAGE)
  let y = PAGE[1] - 60
  const write = (
    text: string,
    x: number,
    size = 10,
    {
      align = 'left',
      color = rgb(0, 0, 0),
    }: { align?: 'left' | 'right'; color?: ReturnType<typeof rgb> } = {},
  ) => {
    const left = align === 'right' ? x - font.widthOfTextAtSize(text, size) : x
    page.drawText(text, { x: left, y, size, font, color })
  }
  /** Starts a new page unless `needed` points still fit; answers whether it did. */
  const newPageIfBelow = (needed: number) => {
    if (y - needed >= BOTTOM) return false
    footer(page, font)
    page = document.addPage(PAGE)
    y = PAGE[1] - 60
    return true
  }

  write(`${SELLER.name} · ${SELLER.street} · ${SELLER.city}`, LEFT, 8, { color: GREY })
  y -= 20
  const billing = order.billingAddress
  for (const line of [
    `${billing.firstName} ${billing.lastName}`,
    `${billing.street} ${billing.houseNumber}`,
    `${billing.postalCode} ${billing.city}`,
    billing.country,
  ]) {
    write(line, LEFT, 11)
    y -= 14
  }

  y -= 40
  write('Rechnung', LEFT, 20)
  y -= 26
  const number = String(order.orderId)
  for (const [label, value] of [
    ['Rechnungsnummer', number],
    ['Bestellnummer', number],
    ['Rechnungsdatum', germanDate(order.paidOn)],
    ['Leistungsdatum', 'entspricht dem Rechnungsdatum'],
  ] as const) {
    write(label, LEFT)
    write(value, LEFT + 110)
    y -= 14
  }

  y -= 20
  const header = () => {
    write('Pos.', COLUMNS.position, 9, { color: GREY })
    write('Artikel', COLUMNS.description, 9, { color: GREY })
    write('Menge', COLUMNS.quantity, 9, { align: 'right', color: GREY })
    write('Einzelpreis', COLUMNS.unit, 9, { align: 'right', color: GREY })
    write('MwSt.', COLUMNS.vat, 9, { align: 'right', color: GREY })
    write('Gesamt', COLUMNS.total, 9, { align: 'right', color: GREY })
    y -= 6
    page.drawLine({ start: { x: LEFT, y }, end: { x: RIGHT, y }, thickness: 0.5, color: GREY })
    y -= 14
  }
  header()
  order.lines.forEach((line, index) => {
    const description = wrap(
      `${line.articleName} – ${line.variantName}`,
      font,
      10,
      COLUMNS.quantity - 40 - COLUMNS.description,
    )
    if (newPageIfBelow(description.length * 13)) header()
    const unit = line.priceCents + line.promptPriceCents
    write(String(index + 1), COLUMNS.position)
    write(String(line.quantity), COLUMNS.quantity, 10, { align: 'right' })
    write(euro(unit), COLUMNS.unit, 10, { align: 'right' })
    write(`${String(line.vatPercent)} %`, COLUMNS.vat, 10, { align: 'right' })
    write(euro(unit * line.quantity), COLUMNS.total, 10, { align: 'right' })
    for (const text of description) {
      write(text, COLUMNS.description)
      y -= 13
    }
    y -= 3
  })

  const breakdown = vatBreakdown(order)
  newPageIfBelow(60 + breakdown.length * 28)
  page.drawLine({
    start: { x: LEFT, y: y + 8 },
    end: { x: RIGHT, y: y + 8 },
    thickness: 0.5,
    color: GREY,
  })
  y -= 8
  const sum = (label: string, cents: number, size = 10) => {
    write(label, COLUMNS.unit - 60, size)
    write(euro(cents), COLUMNS.total, size, { align: 'right' })
    y -= size + 5
  }
  sum('Zwischensumme', order.subtotalCents)
  sum('Versand', order.shippingCostCents)
  if (order.discountCents > 0) sum('Rabatt', -order.discountCents)
  y -= 3
  sum('Gesamtbetrag', order.totalCents, 12)
  y -= 6
  for (const rate of breakdown) {
    sum(`Nettobetrag ${String(rate.percent)} %`, rate.netCents, 9)
    sum(`MwSt. ${String(rate.percent)} %`, rate.taxCents, 9)
  }

  y -= 20
  newPageIfBelow(20)
  write(
    `Der Gesamtbetrag wurde am ${germanDate(order.paidOn)} bezahlt. Vielen Dank für Ihre Bestellung!`,
    LEFT,
  )
  footer(page, font)
  return document.save()
}

function footer(page: PDFPage, font: PDFFont) {
  const lines = [
    `${SELLER.name} · Vertreten durch ${SELLER.representatives}`,
    `${SELLER.street} · ${SELLER.city} · ${SELLER.country}`,
    `${SELLER.email} · USt-IdNr. ${SELLER.vatId}`,
  ]
  lines.forEach((text, index) => {
    page.drawText(text, { x: LEFT, y: 60 - index * 11, size: 8, font, color: GREY })
  })
}

/** `text` broken at spaces into lines no wider than `width`; a single overlong word stays whole. */
function wrap(text: string, font: PDFFont, size: number, width: number): string[] {
  const lines: string[] = []
  let current = ''
  for (const word of text.split(' ')) {
    const candidate = current === '' ? word : `${current} ${word}`
    if (current !== '' && font.widthOfTextAtSize(candidate, size) > width) {
      lines.push(current)
      current = word
    } else {
      current = candidate
    }
  }
  return [...lines, current]
}
