import type { Kysely } from 'kysely'
import type { CountryModule } from '@/modules/country'
import type { Tables } from '@/platform/db/database'
import { createSupplierRepository } from './repository'
import { supplierRoutes } from './routes'
import { createSupplierService } from './service'

export type { SupplierSummary } from './repository'

/**
 * The supplier module: its admin routes, and `find`, which labels the rows of other modules
 * (article, production) with supplier names without their touching the `suppliers` table.
 */
export function createSupplierModule({
  db,
  countries,
}: {
  db: Kysely<Tables>
  countries: Pick<CountryModule, 'find'>
}) {
  const repository = createSupplierRepository(db)
  return {
    routes: supplierRoutes(createSupplierService(repository, countries.find)),
    find: repository.find,
  }
}

export type SupplierModule = ReturnType<typeof createSupplierModule>
