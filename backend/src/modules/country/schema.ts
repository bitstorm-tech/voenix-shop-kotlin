import type { Generated } from 'kysely'

/** The `countries` table (V1, V42): shipping destinations and their shipping conditions. */
export interface CountriesTable {
  id: Generated<number>
  name: string
  country_code: string
  shipping_cost_cents: number
  free_shipping_threshold_cents: number | null
}

declare module '@/platform/db/database' {
  interface Tables {
    countries: CountriesTable
  }
}
