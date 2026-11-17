import type { Kysely } from 'kysely'
import type { CountryModule } from '@/modules/country'
import type { Tables } from '@/platform/db/database'
import { createSupplierRepository } from './repository'
import { supplierRoutes } from './routes'
import { createSupplierService } from './service'

export type { SupplierSummary } from './repository'

/**
 * The supplier module: its admin routes, `find`, which labels the rows of other modules
 * (article, production) with supplier names without their touching the `suppliers` table, and
 * `ids`, which production's findings check for an enabled destination each.
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
    ids: repository.ids,
  }
}

export type SupplierModule = ReturnType<typeof createSupplierModule>
