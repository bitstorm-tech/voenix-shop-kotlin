import type { Kysely } from 'kysely'
import { read, write, type Tables } from '@/platform/db/database'
import { executePostgresWrite } from '@/platform/db/postgres-write'
import './schema'

/** A stored supplier row: every editable field and the bare `countryId`, without the nested country. */
export interface StoredSupplier {
  id: number
  name: string
  title: string | null
  firstName: string | null
  lastName: string | null
  street: string | null
  houseNumber: string | null
  city: string | null
  postalCode: string | null
  countryId: number | null
  phoneNumber1: string | null
  phoneNumber2: string | null
  phoneNumber3: string | null
  email: string | null
  website: string | null
}

type SupplierFields = Omit<StoredSupplier, 'id'>

/** The part of a supplier another module may read: enough to label a row that references it. */
export interface SupplierSummary {
  id: number
  name: string
}

export type SupplierRepository = ReturnType<typeof createSupplierRepository>

const columns = [
  'id',
  'name',
  'title',
  'first_name as firstName',
  'last_name as lastName',
  'street',
  'house_number as houseNumber',
  'city',
  'postal_code as postalCode',
  'country_id as countryId',
  'phone_number1 as phoneNumber1',
  'phone_number2 as phoneNumber2',
  'phone_number3 as phoneNumber3',
  'email',
  'website',
] as const

function toRow(supplier: SupplierFields) {
  return {
    name: supplier.name,
    title: supplier.title,
    first_name: supplier.firstName,
    last_name: supplier.lastName,
    street: supplier.street,
    house_number: supplier.houseNumber,
    city: supplier.city,
    postal_code: supplier.postalCode,
    country_id: supplier.countryId,
    phone_number1: supplier.phoneNumber1,
    phone_number2: supplier.phoneNumber2,
    phone_number3: supplier.phoneNumber3,
    email: supplier.email,
    website: supplier.website,
  }
}

export function createSupplierRepository(db: Kysely<Tables>) {
  return {
    list: (): Promise<StoredSupplier[]> =>
      read(db, (trx) =>
        trx.selectFrom('suppliers').select(columns).orderBy('name').orderBy('id').execute(),
      ),

    findById: (id: number): Promise<StoredSupplier | undefined> =>
      read(db, (trx) =>
        trx.selectFrom('suppliers').select(columns).where('id', '=', id).executeTakeFirst(),
      ),

    /** The summaries of the suppliers with these ids, keyed by id; unknown ids are absent. */
    find: async (ids: Iterable<number>): Promise<Map<number, SupplierSummary>> => {
      const wanted = [...ids]
      if (wanted.length === 0) return new Map()
      const rows = await read(db, (trx) =>
        trx.selectFrom('suppliers').select(['id', 'name']).where('id', 'in', wanted).execute(),
      )
      return new Map(rows.map((row) => [row.id, row]))
    },

    /** The country reference is the insert's only foreign key, so a violation means an unknown country. */
    insert: (supplier: SupplierFields) =>
      executePostgresWrite({ foreignKeyViolation: 'countryNotFound' }, () =>
        write(db, (trx) =>
          trx
            .insertInto('suppliers')
            .values(toRow(supplier))
            .returning(columns)
            .executeTakeFirstOrThrow(),
        ),
      ),

    update: (id: number, supplier: SupplierFields) =>
      executePostgresWrite({ foreignKeyViolation: 'countryNotFound' }, async () => {
        const stored = await write(db, (trx) =>
          trx
            .updateTable('suppliers')
            .set(toRow(supplier))
            .where('id', '=', id)
            .returning(columns)
            .executeTakeFirst(),
        )
        return stored ?? 'notFound'
      }),

    /**
     * The number of rows deleted, `0` when there was no such supplier, or `'inUse'` while a row of
     * another module still references it (every such foreign key is `ON DELETE RESTRICT`).
     */
    delete: (id: number) =>
      executePostgresWrite({ foreignKeyViolation: 'inUse' }, async () => {
        const result = await write(db, (trx) =>
          trx.deleteFrom('suppliers').where('id', '=', id).executeTakeFirst(),
        )
        return Number(result.numDeletedRows)
      }),
  }
}
