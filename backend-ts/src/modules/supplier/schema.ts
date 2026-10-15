import type { Generated } from 'kysely'

/** The `suppliers` table (V3): the producers an admin maintains. Names are deliberately not unique. */
export interface SuppliersTable {
  id: Generated<number>
  name: string
  title: string | null
  first_name: string | null
  last_name: string | null
  street: string | null
  house_number: string | null
  city: string | null
  postal_code: string | null
  country_id: number | null
  phone_number1: string | null
  phone_number2: string | null
  phone_number3: string | null
  email: string | null
  website: string | null
}

declare module '@/platform/db/database' {
  interface Tables {
    suppliers: SuppliersTable
  }
}
