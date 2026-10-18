import type { Generated } from 'kysely'

/** The `countries` table (V1): the shipping countries an admin maintains. */
export interface CountriesTable {
  id: Generated<number>
  name: string
  country_code: string
}

declare module '@/platform/db/database' {
  interface Tables {
    countries: CountriesTable
  }
}
