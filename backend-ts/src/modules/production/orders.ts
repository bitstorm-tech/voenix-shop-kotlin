import {
  catalogKey,
  type ArticleCatalog,
  type ArticleVariantReference,
  type SpodProductRef,
} from '@/modules/article'
import { createLogger, errorClass } from '@/platform/logging'

/**
 * The three reads production needs from the order module, declared here as types and implemented
 * by the order module (#248): the dependency runs one way, `order → production`, and the
 * composition root hands production the order module's object. Production never imports order.
 */
export interface ProductionOrderReads {
  /** The production view of one order, or `null` when the order does not exist. */
  productionData(orderId: number): Promise<ProductionData | null>
  /** The order headers a fulfillment page shows, in one batch; unknown ids are absent. */
  fulfillmentOrders(orderIds: Iterable<number>): Promise<Map<number, FulfillmentOrder>>
  /** Whom a shipping notification goes to, or `null` when the order does not exist. */
  shippingNotificationOrder(orderId: number): Promise<ShippingNotificationOrder | null>
}

/**
 * The production view of one order: the shipping address of the address page, the customer's
 * contact data the print-on-demand partner requires (never handed to a supplier), and the lines in
 * their order. `orderDate` is the `Europe/Berlin` calendar date, `YYYY-MM-DD`.
 */
export interface ProductionData {
  orderId: number
  orderDate: string
  customerEmail: string
  /** `null` on a mug-only order; the SPOD channel refuses the submission without one. */
  customerPhone: string | null
  shippingFirstName: string
  shippingLastName: string
  shippingStreet: string
  shippingHouseNumber: string
  shippingPostalCode: string
  shippingCity: string
  shippingCountry: string
  lines: ProductionLine[]
}

/**
 * One order line as the order stored it. Everything here is a snapshot of the moment of placement;
 * the article and variant ids are the reference production resolves *live* through the article
 * catalog for the two things that must follow today's master data: the supplier a line is routed
 * to and the partner's three product ids (SPOD fulfillment decision 8).
 */
export interface ProductionLine extends ArticleVariantReference {
  articleName: string
  supplierArticleNumber: string | null
  variantName: string
  quantity: number
  /** The absolute path of the generated print image, `null` when none was generated. */
  imagePath: string | null
  /** The five mug-layout overrides in millimetres; `null` on a SPOD line. */
  printTemplateWidthMm: number | null
  printTemplateHeightMm: number | null
  documentFormatWidthMm: number | null
  documentFormatHeightMm: number | null
  documentFormatMarginBottomMm: number | null
  /** The partner's placement the customer previewed, snapshotted at placement; `null` for a mug. */
  spodHotspot: string | null
}

/**
 * The order header a supplier sees, and nothing else: no e-mail address, no phone, no price, no
 * token. The type is the data minimization. `orderDate` is `YYYY-MM-DD` in `Europe/Berlin`.
 */
export interface FulfillmentOrder {
  orderId: number
  orderDate: string
  customerFirstName: string
  customerLastName: string
  shippingStreet: string
  shippingHouseNumber: string
  shippingPostalCode: string
  shippingCity: string
  shippingCountry: string
}

/**
 * What the shipping mail needs from the order: the recipient, the greeting, and the ready-built
 * link to the order page. The access token itself never crosses the boundary.
 */
export interface ShippingNotificationOrder {
  recipientEmail: string
  customerFirstName: string
  /** A bearer link; never log it. */
  orderUrl: string
}

/** One line after the live catalog lookup. */
export interface ProductionItem extends ProductionLine {
  /** Today's supplier of the article; `null` when none is assigned or the variant is gone. */
  supplierId: number | null
  /** Today's partner ids; `null` for a mug or a variant the catalog no longer knows. */
  spodProduct: SpodProductRef | null
  /** Today's variant name, the other side of the SPOD name tripwire. */
  catalogVariantName: string | null
}

export interface ProductionOrder extends Omit<ProductionData, 'lines'> {
  items: ProductionItem[]
}

/** The bounded order-resolution codes every worker stage records. */
export type SourceError = 'SOURCE_NOT_FOUND' | 'SOURCE_INVALID' | 'SOURCE_UNAVAILABLE'

const logger = createLogger('production')

/**
 * Loads one order and resolves its lines against today's catalog. `null` from the order module is
 * `SOURCE_NOT_FOUND`, an order without lines `SOURCE_INVALID`, and any exception of the order read
 * or the catalog lookup `SOURCE_UNAVAILABLE`: a worker must never mistake "we could not look" for "there is nothing".
 * Only the error's class is logged.
 */
export function createOrderLoader(
  orders: Pick<ProductionOrderReads, 'productionData'>,
  articleCatalog: Pick<ArticleCatalog, 'find'>,
) {
  return async (
    orderId: number,
  ): Promise<{ kind: 'found'; order: ProductionOrder } | { kind: 'failed'; code: SourceError }> => {
    const unavailable = (error: unknown, what: string) => {
      logger.error({ orderId, errorClass: errorClass(error) }, `Production could not read ${what}`)
      return { kind: 'failed', code: 'SOURCE_UNAVAILABLE' } as const
    }
    let data: ProductionData | null
    try {
      data = await orders.productionData(orderId)
    } catch (error) {
      return unavailable(error, 'the order')
    }
    if (data === null) return { kind: 'failed', code: 'SOURCE_NOT_FOUND' }
    if (data.lines.length === 0) return { kind: 'failed', code: 'SOURCE_INVALID' }
    let catalog: Awaited<ReturnType<ArticleCatalog['find']>>
    try {
      catalog = await articleCatalog.find(data.lines)
    } catch (error) {
      return unavailable(error, 'the article catalog')
    }
    const { lines, ...header } = data
    return {
      kind: 'found',
      order: {
        ...header,
        items: lines.map((line) => {
          const current = catalog.get(catalogKey(line))
          return {
            ...line,
            supplierId: current?.supplierId ?? null,
            spodProduct: current?.spodProduct ?? null,
            catalogVariantName: current?.variantName ?? null,
          }
        }),
      },
    }
  }
}

export type OrderLoader = ReturnType<typeof createOrderLoader>

/** `ORD-{orderId}`: the order label every PDF page prints. */
export const orderLabel = (orderId: number): string => `ORD-${String(orderId)}`

/** `ORD-{orderId}.pdf`: the producer-facing file name of the download, the artifact, and the upload. */
export const pdfFileName = (orderId: number): string => `${orderLabel(orderId)}.pdf`
