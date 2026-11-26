import type { Kysely } from 'kysely'
import { read, type Tables } from '@/platform/db/database'
import { executePostgresWrite } from '@/platform/db/postgres-write'
import { conflict, notFound, ok, type OperationResult } from '@/platform/operation'
import './schema'

/** A country's shipping rules: flat cost in cents and an optional free-shipping threshold. */
export interface ShippingPolicy {
  shippingCostCents: number
  /** Pre-coupon subtotal from which shipping is free, or `null` when no free threshold applies. */
  freeShippingThresholdCents: number | null
}

/** A stored country; also the admin response. */
export interface Country extends ShippingPolicy {
  id: number
  name: string
  countryCode: string
}

/** The validated body of a create or update, as `routes.ts`'s schema produces it (trimmed). */
export interface CountryInput extends ShippingPolicy {
  name: string
  countryCode: string
}

export type CountryRepository = ReturnType<typeof createCountryRepository>

const columns = [
  'id',
  'name',
  'country_code as countryCode',
  'shipping_cost_cents as shippingCostCents',
  'free_shipping_threshold_cents as freeShippingThresholdCents',
] as const

export function createCountryRepository(db: Kysely<Tables>) {
  return {
    list: () =>
      read(db, (trx) =>
        trx.selectFrom('countries').select(columns).orderBy('country_code').orderBy('id').execute(),
      ),

    get: async (id: number): Promise<OperationResult<Country>> => {
      const country = await read(db, (trx) =>
        trx.selectFrom('countries').select(columns).where('id', '=', id).executeTakeFirst(),
      )
      return country === undefined ? notFound : ok(country)
    },

    /** The countries with these ids, keyed by id; unknown ids are absent. */
    find: async (ids: Iterable<number>): Promise<Map<number, Country>> => {
      const wanted = [...ids]
      if (wanted.length === 0) return new Map()
      const rows = await read(db, (trx) =>
        trx.selectFrom('countries').select(columns).where('id', 'in', wanted).execute(),
      )
      return new Map(rows.map((row) => [row.id, row]))
    },

    /**
     * The shipping policy of this ISO 3166-1 alpha-2 destination, or `null` when the shop does not
     * ship there (because the table *is* the list of destinations). The code is normalized the way
     * `create` stores it, so `de`, `DE`, and `" de "` ask the same question.
     */
    shippingPolicy: async (countryCode: string): Promise<ShippingPolicy | null> => {
      const code = countryCode.trim().toUpperCase()
      if (code === '') return null
      const row = await read(db, (trx) =>
        trx
          .selectFrom('countries')
          .select([
            'shipping_cost_cents as shippingCostCents',
            'free_shipping_threshold_cents as freeShippingThresholdCents',
          ])
          .where('country_code', '=', code)
          .executeTakeFirst(),
      )
      return row ?? null
    },

    /** Stores the code upper-cased, the one form every read and `shippingPolicy` rely on. */
    create: (input: CountryInput): Promise<OperationResult<Country>> =>
      executePostgresWrite({ uniqueViolation: conflict }, async () => {
        const country = {
          name: input.name,
          countryCode: input.countryCode.toUpperCase(),
          shippingCostCents: input.shippingCostCents,
          freeShippingThresholdCents: input.freeShippingThresholdCents,
        }
        const { id } = await db
          .insertInto('countries')
          .values({
            name: country.name,
            country_code: country.countryCode,
            shipping_cost_cents: country.shippingCostCents,
            free_shipping_threshold_cents: country.freeShippingThresholdCents,
          })
          .returning('id')
          .executeTakeFirstOrThrow()
        return ok({ id, ...country })
      }),
    update: (id: number, input: CountryInput): Promise<OperationResult<Country>> =>
      executePostgresWrite({ uniqueViolation: conflict }, async () => {
        const country = {
          name: input.name,
          countryCode: input.countryCode.toUpperCase(),
          shippingCostCents: input.shippingCostCents,
          freeShippingThresholdCents: input.freeShippingThresholdCents,
        }
        const result = await db
          .updateTable('countries')
          .set({
            name: country.name,
            country_code: country.countryCode,
            shipping_cost_cents: country.shippingCostCents,
            free_shipping_threshold_cents: country.freeShippingThresholdCents,
          })
          .where('id', '=', id)
          .executeTakeFirst()
        return result.numUpdatedRows === 0n ? notFound : ok({ id, ...country })
      }),
    delete: async (id: number): Promise<OperationResult<undefined>> => {
      const result = await db.deleteFrom('countries').where('id', '=', id).executeTakeFirst()
      return result.numDeletedRows === 0n ? notFound : ok(undefined)
    },
  }
}
