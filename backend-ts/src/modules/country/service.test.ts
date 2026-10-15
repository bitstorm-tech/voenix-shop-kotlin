import { beforeEach, describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { createCountryRepository } from '@/modules/country/repository'
import { createCountryService } from '@/modules/country/service'
import { testDatabase } from '@test-support/database'

const database = testDatabase()
const repository = () => createCountryRepository(database.db)
const service = () => createCountryService(repository())

const resetCountries = async (seed: boolean) => {
  await sql`TRUNCATE countries RESTART IDENTITY CASCADE`.execute(database.db)
  if (seed) {
    await sql`INSERT INTO countries (name, country_code) VALUES
      ('Germany', 'DE'), ('France', 'FR'), ('Italy', 'IT'), ('Austria', 'AT'),
      ('Belgium', 'BE'), ('Netherlands', 'NL'), ('Spain', 'ES'), ('Sweden', 'SE')`.execute(
      database.db,
    )
  }
}

describe('create and update', () => {
  beforeEach(() => resetCountries(true))

  test('a duplicate name, however it is cased, and a duplicate normalized code conflict', async () => {
    const countries = service()
    expect(await countries.create({ name: 'germany', countryCode: 'GE' })).toEqual({
      kind: 'conflict',
    })
    expect(await countries.create({ name: 'Denmark', countryCode: ' de ' })).toEqual({
      kind: 'conflict',
    })
    expect((await countries.create({ name: 'A%B', countryCode: 'AB' })).kind).toBe('ok')
    expect((await countries.create({ name: 'A_B', countryCode: 'AC' })).kind).toBe('ok')
    expect(await countries.create({ name: 'a%b', countryCode: 'AD' })).toEqual({ kind: 'conflict' })
    const listed = await countries.listAdmin()
    expect(listed.kind === 'ok' && listed.value.length).toBe(10)
  })

  test('a self update passes, a conflicting update leaves the row unchanged, an unknown id is not found', async () => {
    const countries = service()
    expect(await countries.update(1, { name: 'Germany', countryCode: 'DE' })).toEqual({
      kind: 'ok',
      value: { id: 1, name: 'Germany', countryCode: 'DE' },
    })
    expect(await countries.update(2, { name: 'germany', countryCode: 'FR' })).toEqual({
      kind: 'conflict',
    })
    expect(await countries.update(2, { name: 'France', countryCode: 'DE' })).toEqual({
      kind: 'conflict',
    })
    expect(await countries.update(999, { name: 'Denmark', countryCode: 'DK' })).toEqual({
      kind: 'notFound',
    })
    expect(await countries.get(2)).toEqual({
      kind: 'ok',
      value: { id: 2, name: 'France', countryCode: 'FR' },
    })
  })

  test('concurrent creates with a case-variant name or the same code leave one row and one conflict', async () => {
    await resetCountries(false)
    const countries = service()
    for (const [first, second] of [
      [
        { name: 'Denmark', countryCode: 'DK' },
        { name: 'denmark', countryCode: 'DN' },
      ],
      [
        { name: 'Norway', countryCode: 'NO' },
        { name: 'Norge', countryCode: 'NO' },
      ],
    ] as const) {
      const results = await Promise.all([countries.create(first), countries.create(second)])
      expect(results.filter((result) => result.kind === 'ok')).toHaveLength(1)
      expect(results.filter((result) => result.kind === 'conflict')).toHaveLength(1)
    }
    const listed = await countries.listAdmin()
    expect(listed.kind === 'ok' && listed.value.length).toBe(2)
  })
})

describe('delete and the public mapping', () => {
  beforeEach(() => resetCountries(false))

  test('delete removes the row and then reports it missing', async () => {
    await sql`INSERT INTO countries (name, country_code) VALUES ('Germany', 'DE')`.execute(
      database.db,
    )
    const countries = service()
    expect(await countries.delete(1)).toEqual({ kind: 'ok', value: undefined })
    expect(await countries.delete(1)).toEqual({ kind: 'notFound' })
    expect(await countries.listAdmin()).toEqual({ kind: 'ok', value: [] })
  })

  test('the public list upper-cases a stored code and has no dial code for an unknown region', async () => {
    await sql`INSERT INTO countries (name, country_code) VALUES ('Unknown', 'zz')`.execute(
      database.db,
    )
    expect(await service().listPublic()).toEqual({
      kind: 'ok',
      value: [{ name: 'Unknown', countryCode: 'ZZ', dialCode: null }],
    })
    expect(await service().listAdmin()).toEqual({
      kind: 'ok',
      value: [{ id: 1, name: 'Unknown', countryCode: 'zz' }],
    })
  })
})

describe('the exported capabilities', () => {
  beforeEach(() => resetCountries(true))

  test('find answers the known ids and leaves unknown ones out', async () => {
    const found = await repository().find([1, 2, 999])
    expect([...found.entries()]).toEqual([
      [1, { id: 1, name: 'Germany', countryCode: 'DE' }],
      [2, { id: 2, name: 'France', countryCode: 'FR' }],
    ])
    expect((await repository().find([])).size).toBe(0)
  })

  test('isShippable: a stored code however the client spells it, nothing else', async () => {
    const countries = repository()
    for (const code of ['DE', 'de', ' de ', 'SE'])
      expect(await countries.isShippable(code)).toBe(true)
    for (const code of ['XX', '', '   ', 'D', 'DEU'])
      expect(await countries.isShippable(code)).toBe(false)
  })

  test('the country admin opens and closes a destination', async () => {
    const countries = service()
    const shippable = repository()
    expect(await shippable.isShippable('DK')).toBe(false)
    const created = await countries.create({ name: 'Denmark', countryCode: 'dk' })
    expect(await shippable.isShippable('DK')).toBe(true)
    if (created.kind === 'ok') expect((await countries.delete(created.value.id)).kind).toBe('ok')
    expect(await shippable.isShippable('DK')).toBe(false)
  })
})
