import { Hono } from 'hono'
import type { Kysely } from 'kysely'
import type { ArticleCatalog } from '@/modules/article'
import type { EmailModule } from '@/modules/email'
import type { ImageModule } from '@/modules/image'
import type { ProductionOrderReads } from '@/modules/production'
import type { PromotionCodes } from '@/modules/promotion'
import type { Auth } from '@/platform/auth/auth'
import type { Tables } from '@/platform/db/database'
import type { Settings } from '@/platform/settings'
import { createMolliePayments, type MolliePayments } from './payment/mollie'
import { createPaymentRepository } from './payment/repository'
import { paymentRoutes } from './payment/routes'
import { createPaymentService } from './payment/service'
import { createOrderRepository } from './repository'
import { adminPdfRoutes, orderRoutes } from './routes'
import { createOrderService, type PrintImageReads } from './service'
import { createOrderTransitions } from './transitions'

export type { MolliePayment, MolliePayments } from './payment/mollie'
export type { OrderFinding } from './payment/repository'
export type { OrderAddress, OrderItemReference, PayableOrder, PlaceOrderInput } from './repository'
export type { PayableResult, PlaceOrderResult, PrintImageReads } from './service'
export type { CancelResult, PaidResult } from './transitions'

/**
 * The order module with its payment: the customer's order reads, the mail-link lookup, the Mollie
 * webhook, and the `orders` object the other modules use. It is created *before* production,
 * which receives `orders` as its `ProductionOrderReads`; the two admin PDF routes need
 * production's generator and are therefore a function the composition root calls afterwards:
 * `app.route('/', order.adminPdfRoutes(production.productionPdfGenerator))`. The paid transition
 * imports `requestProduction`, a plain function, so no instance of production is needed for it.
 * `printImages` is the cart module's `createPrintImageReads(db)`, a plain read the composition root
 * creates before this module (the cart itself is created after it, with `orders`). `mollie`
 * defaults to the Mollie adapter on the global `fetch`; tests hand in a stub.
 */
export function createOrderModule({
  db,
  settings,
  articleCatalog,
  promotionCodes,
  email,
  images,
  printImages,
  guestTokens,
  mollie = createMolliePayments(settings.mollie),
}: {
  db: Kysely<Tables>
  settings: { mollie: Settings['mollie']; frontendBaseUrl: string }
  articleCatalog: Pick<ArticleCatalog, 'find'>
  promotionCodes: Pick<PromotionCodes, 'redeem' | 'release'>
  email: { outbox: Pick<EmailModule['outbox'], 'enqueue'> }
  images: Pick<ImageModule, 'privateStorage'>
  printImages: PrintImageReads
  guestTokens: Auth['guestTokens']
  mollie?: MolliePayments
}) {
  const repository = createOrderRepository(db)
  const paymentRepository = createPaymentRepository(db)
  const transitions = createOrderTransitions({ db, promotionCodes })
  const payments = createPaymentService({
    db,
    repository: paymentRepository,
    mollie,
    transitions,
  })
  const service = createOrderService({
    repository,
    articleCatalog,
    email,
    images,
    printImages,
    payments,
    frontendBaseUrl: settings.frontendBaseUrl,
  })

  const productionReads: ProductionOrderReads = {
    productionData: service.productionData,
    fulfillmentOrders: repository.fulfillmentOrders,
    shippingNotificationOrder: service.shippingNotificationOrder,
  }

  const routes = new Hono()
  routes.route('/', orderRoutes(service, guestTokens))
  routes.route('/', paymentRoutes(payments, settings.mollie.webhookSecret))
  return {
    routes,
    adminPdfRoutes,
    orders: {
      /** Checkout TX1: places the order and enqueues its confirmation mail in `trx`. */
      place: service.place,
      /** The retry journey's read: the order a payment is started for again. */
      payable: service.payable,
      /** Checkout TX1 for a free order: the paid transition in `trx`. */
      confirmFree: transitions.markPaid,
      /** Gives up a pending order and releases its reservation, in `trx`; `startPayment` calls it itself. */
      cancel: transitions.cancel,
      /** Starts (or re-answers) the payment of an order; its own transaction, outside the caller's. */
      startPayment: payments.startPayment,
      /** The reorder references of one ordered line the caller owns. */
      orderItem: repository.orderItem,
      /** The stalled payments, for the operations worker. */
      findings: paymentRepository.findings,
      /** Asks Mollie about every payment under way for over 15 minutes; the operations worker's first step. */
      reconcilePayments: payments.reconcile,
      /** The three reads production is created with. */
      ...productionReads,
    },
  }
}

export type OrderModule = ReturnType<typeof createOrderModule>
export type Orders = OrderModule['orders']
