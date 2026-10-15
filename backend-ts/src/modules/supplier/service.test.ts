import { describe, expect, test } from 'bun:test'
import { createCountryModule } from '@/modules/country'
import { createSupplierRepository } from '@/modules/supplier/repository'
import { createSupplierService, type SupplierInput } from '@/modules/supplier/service'
import { testDatabase } from '@test-support/database'

const database = testDatabase()

const input = (name: string, countryId: number | null = null): SupplierInput => ({
  name,
  title: null,
  firstName: null,
  lastName: null,
  street: null,
  houseNumber: null,
  city: null,
  postalCode: null,
  countryId,
  phoneNumber1: null,
  phoneNumber2: null,
  phoneNumber3: null,
  email: null,
  website: null,
})

describe('country enrichment', () => {
  test('a list resolves every distinct country with one lookup', async () => {
    const countries = createCountryModule({ db: database.db })
    const lookups: number[][] = []
    const service = createSupplierService(createSupplierRepository(database.db), (ids) => {
      lookups.push([...ids])
      return countries.find(ids)
    })
    for (const supplier of [input('First', 1), input('Second', 1), input('Third', 2)])
      expect((await service.create(supplier)).kind).toBe('ok')
    lookups.length = 0

    expect((await service.list()).kind).toBe('ok')
    expect(lookups).toEqual([[1, 2]])
  })
})

describe('find', () => {
  test('answers the summaries of the known ids and leaves unknown ones out', async () => {
    const repository = createSupplierRepository(database.db)
    const acme = await repository.insert(input('Acme', 1))
    const globex = await repository.insert(input('Globex'))
    if (typeof acme === 'string' || typeof globex === 'string') throw new Error('not stored')

    const found = await repository.find([acme.id, globex.id, 999])
    expect([...found.entries()]).toEqual([
      [acme.id, { id: acme.id, name: 'Acme' }],
      [globex.id, { id: globex.id, name: 'Globex' }],
    ])
    expect((await repository.find([])).size).toBe(0)
  })
})
