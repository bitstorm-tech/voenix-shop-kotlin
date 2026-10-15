import type { Kysely } from 'kysely'
import { read, write, type Tables } from '@/platform/db/database'
import { executePostgresWrite } from '@/platform/db/postgres-write'
import './schema'

/** A stored country; also the admin response. */
export interface Country {
  id: number
  name: string
  countryCode: string
}

export type CountryRepository = ReturnType<typeof createCountryRepository>

const columns = ['id', 'name', 'country_code as countryCode'] as const

export function createCountryRepository(db: Kysely<Tables>) {
  return {
    list: () =>
      read(db, (trx) =>
        trx.selectFrom('countries').select(columns).orderBy('country_code').orderBy('id').execute(),
      ),

    findById: (id: number) =>
      read(db, (trx) =>
        trx.selectFrom('countries').select(columns).where('id', '=', id).executeTakeFirst(),
      ),

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
     * Whether the shop ships to this ISO 3166-1 alpha-2 code: exactly when a row with it exists,
     * because the table *is* the list of destinations. The code is normalized the way `create`
     * stores it, so `de`, `DE`, and `" de "` ask the same question.
     */
    isShippable: async (countryCode: string): Promise<boolean> => {
      const code = countryCode.trim().toUpperCase()
      if (code === '') return false
      const row = await read(db, (trx) =>
        trx
          .selectFrom('countries')
          .select('id')
          .where('country_code', '=', code)
          .executeTakeFirst(),
      )
      return row !== undefined
    },

    insert: (country: Omit<Country, 'id'>) =>
      executePostgresWrite({ uniqueViolation: 'conflict' }, () =>
        write(db, async (trx) => {
          const { id } = await trx
            .insertInto('countries')
            .values({ name: country.name, country_code: country.countryCode })
            .returning('id')
            .executeTakeFirstOrThrow()
          return { id, ...country }
        }),
      ),

    update: (id: number, country: Omit<Country, 'id'>) =>
      executePostgresWrite({ uniqueViolation: 'conflict' }, async () => {
        const result = await write(db, (trx) =>
          trx
            .updateTable('countries')
            .set({ name: country.name, country_code: country.countryCode })
            .where('id', '=', id)
            .executeTakeFirst(),
        )
        return result.numUpdatedRows === 0n ? 'notFound' : { id, ...country }
      }),

    /** The number of rows deleted: `0` when there was no such country. */
    delete: async (id: number): Promise<number> => {
      const result = await write(db, (trx) =>
        trx.deleteFrom('countries').where('id', '=', id).executeTakeFirst(),
      )
      return Number(result.numDeletedRows)
    },
  }
}
