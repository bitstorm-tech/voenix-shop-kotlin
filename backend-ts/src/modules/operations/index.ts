import type { Kysely } from 'kysely'
import type { Tables } from '@/platform/db/database'
import { createFindingRepository } from './repository'
import { operationsRoutes } from './routes'
import { createOperationsWorker, type FindingSource } from './worker'

/**
 * The operations module: the one place that looks across the whole pipeline. Each owning module
 * reports its own stalled rows through a `findings(now)` capability (order: payments, production:
 * requests, jobs, deliveries, SPOD orders, email: the outbox); the worker records them in
 * `operations_findings`, the only table this module owns, and the admin route lists them. Nothing
 * imports this module. The composition root creates it after order and production, mounts
 * `routes`, and starts and stops `worker` next to the other two workers.
 */
export function createOperationsModule({
  db,
  orders,
  production,
  outbox,
}: {
  db: Kysely<Tables>
  orders: FindingSource
  production: FindingSource
  outbox: FindingSource
}) {
  const repository = createFindingRepository(db)
  const worker = createOperationsWorker({ sources: [orders, production, outbox], repository })
  return {
    routes: operationsRoutes(repository),
    worker: { start: worker.start, stop: worker.stop },
  }
}

export type OperationsModule = ReturnType<typeof createOperationsModule>
