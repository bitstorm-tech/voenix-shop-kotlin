import type { Country, CountryModule } from '@/modules/country'
import { ok, type OperationResult } from '@/platform/operation'
import type { StoredSupplier, SupplierRepository } from './repository'

/**
 * The validated, normalized body of a create or a full replacement, as `routes.ts`'s schema
 * produces it: text trimmed, a blank or omitted optional field `null`.
 */
export type SupplierInput = Omit<StoredSupplier, 'id'>

/** The admin response of every route: the stored row plus its country, when that still exists. */
export interface Supplier extends StoredSupplier {
  country: Country | null
}

export type SupplierService = ReturnType<typeof createSupplierService>

export function createSupplierService(
  repository: SupplierRepository,
  findCountries: CountryModule['find'],
) {
  /** One batched country lookup for every supplier of a result, however many there are. */
  const countriesOf = (suppliers: StoredSupplier[]) =>
    findCountries(new Set(suppliers.flatMap((supplier) => supplier.countryId ?? [])))
  const withCountry =
    (countries: Map<number, Country>) =>
    (supplier: StoredSupplier): Supplier => ({
      ...supplier,
      country: supplier.countryId === null ? null : (countries.get(supplier.countryId) ?? null),
    })
  const answer = async (
    result: OperationResult<StoredSupplier>,
  ): Promise<OperationResult<Supplier>> =>
    result.kind === 'ok' ? ok(withCountry(await countriesOf([result.value]))(result.value)) : result

  return {
    list: async (): Promise<Supplier[]> => {
      const suppliers = await repository.list()
      return suppliers.map(withCountry(await countriesOf(suppliers)))
    },

    get: async (id: number) => answer(await repository.get(id)),

    create: async (input: SupplierInput) => answer(await repository.insert(input)),

    update: async (id: number, input: SupplierInput) => answer(await repository.update(id, input)),

    delete: repository.delete,
  }
}
