import type { Kysely } from 'kysely'
import type { EmailModule } from '@/modules/email'
import type { Tables } from '@/platform/db/database'
import { createLogger } from '@/platform/logging'
import type { Settings } from '@/platform/settings'
import { createFindingRepository } from './repository'
import { operationsRoutes } from './routes'
import { createOperationsWorker, type FindingSource } from './worker'

/**
 * The operations module: the one place that looks across the whole pipeline. Each owning module
 * reports its own stalled rows through a `findings(now)` capability (order: payments, production:
 * requests, jobs, deliveries, SPOD orders, email: the outbox); the worker reconciles the payments
 * under way first (`orders.reconcilePayments`) and then records the findings in
 * `operations_findings`, the only table this module owns, mails the new ones to
 * `settings.alertEmail` through the outbox, and the admin route lists them. Nothing
 * imports this module. The composition root creates it after order and production, mounts
 * `routes`, and starts and stops `worker` next to the other two workers.
 */
export function createOperationsModule({
  db,
  orders,
  production,
  outbox,
  settings,
  frontendBaseUrl,
}: {
  db: Kysely<Tables>
  orders: FindingSource & { reconcilePayments: (now: Date) => Promise<void> }
  production: FindingSource
  outbox: FindingSource & Pick<EmailModule['outbox'], 'enqueue'>
  settings: Settings['operations']
  frontendBaseUrl: string
}) {
  const repository = createFindingRepository(db)
  const worker = createOperationsWorker({
    reconcilePayments: orders.reconcilePayments,
    sources: [orders, production, outbox],
    repository,
    outbox,
    alertEmail: settings.alertEmail,
    frontendBaseUrl,
    logger: createLogger('operations-worker'),
  })
  return {
    routes: operationsRoutes(repository),
    worker: { start: worker.start, stop: worker.stop },
  }
}

export type OperationsModule = ReturnType<typeof createOperationsModule>
