import { getCountryCallingCode, isSupportedCountry } from 'libphonenumber-js'
import { conflict, notFound, ok, type OperationResult } from '@/platform/operation'
import type { Country, CountryRepository } from './repository'

/** The validated body of a create or update, as `routes.ts`'s schema produces it. */
export interface CountryInput {
  name: string
  countryCode: string
}

/** The public response: no id, the code upper-cased, and the dial code when the region is known. */
export interface PublicCountry {
  name: string
  countryCode: string
  dialCode: string | null
}

export type CountryService = ReturnType<typeof createCountryService>

export function createCountryService(repository: CountryRepository) {
  return {
    listPublic: async (): Promise<OperationResult<PublicCountry[]>> =>
      ok((await repository.list()).map(toPublicCountry)),

    listAdmin: async (): Promise<OperationResult<Country[]>> => ok(await repository.list()),

    get: async (id: number): Promise<OperationResult<Country>> => {
      const country = await repository.findById(id)
      return country === undefined ? notFound : ok(country)
    },

    create: async (input: CountryInput): Promise<OperationResult<Country>> => {
      const stored = await repository.insert(normalize(input))
      return stored === 'conflict' ? conflict : ok(stored)
    },

    update: async (id: number, input: CountryInput): Promise<OperationResult<Country>> => {
      const stored = await repository.update(id, normalize(input))
      if (stored === 'conflict') return conflict
      if (stored === 'notFound') return notFound
      return ok(stored)
    },

    delete: async (id: number): Promise<OperationResult<undefined>> =>
      (await repository.delete(id)) === 0 ? notFound : ok(undefined),
  }
}

/** The single place that normalizes validated input before it is stored. */
function normalize(input: CountryInput): Omit<Country, 'id'> {
  return { name: input.name.trim(), countryCode: input.countryCode.trim().toUpperCase() }
}

function toPublicCountry(country: Country): PublicCountry {
  const countryCode = country.countryCode.trim().toUpperCase()
  return {
    name: country.name,
    countryCode,
    dialCode: isSupportedCountry(countryCode) ? `+${getCountryCallingCode(countryCode)}` : null,
  }
}
