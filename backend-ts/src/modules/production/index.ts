import { Hono } from 'hono'
import type { Kysely } from 'kysely'
import type { ArticleCatalog, SpodCatalogSync } from '@/modules/article'
import type { EmailModule } from '@/modules/email'
import type { SupplierModule } from '@/modules/supplier'
import type { SupplierAccounts } from '@/platform/auth/auth'
import type { Tables } from '@/platform/db/database'
import type { Settings } from '@/platform/settings'
import { requestProduction } from './delivery/requests'
import { createProductionWorker } from './delivery/worker'
import { productionFindings } from './findings'
import { createFulfillmentRepository } from './fulfillment/repository'
import { fulfillmentRoutes } from './fulfillment/routes'
import { createFulfillmentService } from './fulfillment/service'
import { createOrderLoader, type ProductionOrderReads } from './orders'
import { createArtifactStore } from './pdf/artifact-store'
import { createProductionPdfGenerator } from './pdf/generator'
import { createDestinationRepository } from './repository'
import { destinationRoutes } from './routes'
import { createDestinationService } from './service'
import { createSpodOrderRepository } from './spod/repository'
import type { SubmissionClient } from './spod/submitter'

export type { ProductionFinding } from './findings'
export type {
  FulfillmentOrder,
  ProductionData,
  ProductionLine,
  ProductionOrderReads,
  ShippingNotificationOrder,
} from './orders'
export type {
  ProductionPdfDocument,
  ProductionPdfGenerator,
  ProductionPdfResult,
} from './pdf/generator'
export type { ProductionPdfError } from './pdf/renderer'
export { requestProduction }

/**
 * The production module: destinations, the durable pipeline (split, PDF generation, SFTP delivery,
 * SPOD submission), fulfillment, and the partner's webhook. It reads paid orders through
 * `orders`, the order module's implementation of the read contracts declared here, so the edge runs
 * one way, `order → production` (#248). The composition root mounts `routes`, calls
 * `worker.start()` once and `worker.stop()` at shutdown, hands `requestProduction` to the paid
 * transition and `productionPdfGenerator` to the admin order downloads, and `findings` (the stalled
 * rows of the pipeline) to the operations worker.
 *
 * It refuses to start when a SPOD destination exists while `settings.spod` is missing: a channel
 * whose shipments arrive by webhook could not report one, and the worker must not submit a paid
 * order during a startup that is about to fail. It also creates the artifact root, so an unusable
 * root fails the startup instead of the first generation.
 */
export async function createProductionModule({
  db,
  settings,
  email,
  suppliers,
  articleCatalog,
  spodCatalogSync,
  spod,
  accounts,
  orders,
}: {
  db: Kysely<Tables>
  settings: Settings['production']
  email: Pick<EmailModule, 'outbox'>
  suppliers: Pick<SupplierModule, 'find'>
  articleCatalog: Pick<ArticleCatalog, 'find'>
  spodCatalogSync: Pick<SpodCatalogSync, 'sync'>
  spod: SubmissionClient
  accounts: SupplierAccounts
  orders: ProductionOrderReads
}) {
  const destinations = createDestinationRepository(db)
  if (settings.spod === null && (await destinations.hasSpodDestination())) {
    throw new Error(
      'A print-on-demand destination exists, so production.spod.webhookSecret and ' +
        'production.spod.alertEmail are required',
    )
  }
  const artifacts = await createArtifactStore(settings.artifactRoot)
  const loadOrder = createOrderLoader(orders, articleCatalog)
  const spodOrders = createSpodOrderRepository({
    db,
    outbox: email.outbox,
    alertEmail: settings.spod?.alertEmail ?? null,
  })
  const worker = createProductionWorker({
    db,
    loadOrder,
    artifacts,
    outbox: email.outbox,
    orders,
    spodOrders,
    spod,
  })

  const routes = new Hono()
  routes.route(
    '/',
    destinationRoutes(
      createDestinationService(destinations, settings.spod !== null, spodCatalogSync),
    ),
  )
  routes.route(
    '/',
    fulfillmentRoutes(
      createFulfillmentService({
        repository: createFulfillmentRepository(db, email.outbox),
        orders,
        suppliers,
        artifacts,
        spodOrders,
      }),
      accounts,
      settings.spod?.webhookSecret ?? null,
    ),
  )
  return {
    routes,
    productionPdfGenerator: createProductionPdfGenerator(loadOrder),
    requestProduction,
    findings: (now: Date) => productionFindings(db, now),
    worker: { start: worker.start, stop: worker.stop },
  }
}
