import type { Kysely } from 'kysely'
import type { Tables } from '@/platform/db/database'
import { createCountryRepository } from './repository'
import { countryRoutes } from './routes'
import { createCountryService } from './service'

export type { Country } from './repository'

/**
 * The country module: its routes, and the two capabilities other modules consume. `find` resolves
 * the countries a supplier points at; `isShippable` answers whether a checkout may send a parcel
 * to a code.
 */
export function createCountryModule({ db }: { db: Kysely<Tables> }) {
  const repository = createCountryRepository(db)
  return {
    routes: countryRoutes(createCountryService(repository)),
    find: repository.find,
    isShippable: repository.isShippable,
  }
}

export type CountryModule = ReturnType<typeof createCountryModule>
