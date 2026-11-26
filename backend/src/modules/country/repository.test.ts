import { beforeEach, describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { createCountryRepository } from '@/modules/country/repository'
import { testDatabase } from '@test-support/database'

const database = testDatabase()
const repository = () => createCountryRepository(database.db)

const DEFAULT_SHIPPING = { shippingCostCents: 490, freeShippingThresholdCents: 5000 }

const resetCountries = async (seed: boolean) => {
  await sql`TRUNCATE countries RESTART IDENTITY CASCADE`.execute(database.db)
  if (seed) {
    await sql`INSERT INTO countries (name, country_code, shipping_cost_cents, free_shipping_threshold_cents) VALUES
      ('Germany', 'DE', 490, 5000), ('France', 'FR', 490, 5000), ('Italy', 'IT', 490, 5000),
      ('Austria', 'AT', 490, 5000), ('Belgium', 'BE', 490, 5000), ('Netherlands', 'NL', 490, 5000),
      ('Spain', 'ES', 490, 5000), ('Sweden', 'SE', 490, 5000)`.execute(database.db)
  }
}

describe('create and update', () => {
  beforeEach(() => resetCountries(true))

  test('a duplicate name, however it is cased, and a duplicate normalized code conflict', async () => {
    const countries = repository()
    expect(
      await countries.create({ name: 'germany', countryCode: 'GE', ...DEFAULT_SHIPPING }),
    ).toEqual({
      kind: 'conflict',
    })
    expect(
      await countries.create({ name: 'Denmark', countryCode: 'de', ...DEFAULT_SHIPPING }),
    ).toEqual({
      kind: 'conflict',
    })
    expect(
      (await countries.create({ name: 'A%B', countryCode: 'AB', ...DEFAULT_SHIPPING })).kind,
    ).toBe('ok')
    expect(
      (await countries.create({ name: 'A_B', countryCode: 'AC', ...DEFAULT_SHIPPING })).kind,
    ).toBe('ok')
    expect(await countries.create({ name: 'a%b', countryCode: 'AD', ...DEFAULT_SHIPPING })).toEqual(
      { kind: 'conflict' },
    )
    expect(await countries.list()).toHaveLength(10)
  })

  test('a self update passes, a conflicting update leaves the row unchanged, an unknown id is not found', async () => {
    const countries = repository()
    expect(
      await countries.update(1, {
        name: 'Germany',
        countryCode: 'DE',
        shippingCostCents: 590,
        freeShippingThresholdCents: null,
      }),
    ).toEqual({
      kind: 'ok',
      value: {
        id: 1,
        name: 'Germany',
        countryCode: 'DE',
        shippingCostCents: 590,
        freeShippingThresholdCents: null,
      },
    })
    expect(
      await countries.update(2, { name: 'germany', countryCode: 'FR', ...DEFAULT_SHIPPING }),
    ).toEqual({
      kind: 'conflict',
    })
    expect(
      await countries.update(2, { name: 'France', countryCode: 'DE', ...DEFAULT_SHIPPING }),
    ).toEqual({
      kind: 'conflict',
    })
    expect(
      await countries.update(999, { name: 'Denmark', countryCode: 'DK', ...DEFAULT_SHIPPING }),
    ).toEqual({
      kind: 'notFound',
    })
    expect(await countries.get(2)).toEqual({
      kind: 'ok',
      value: { id: 2, name: 'France', countryCode: 'FR', ...DEFAULT_SHIPPING },
    })
  })

  test('concurrent creates with a case-variant name or the same code leave one row and one conflict', async () => {
    await resetCountries(false)
    const countries = repository()
    for (const [first, second] of [
      [
        { name: 'Denmark', countryCode: 'DK', ...DEFAULT_SHIPPING },
        { name: 'denmark', countryCode: 'DN', ...DEFAULT_SHIPPING },
      ],
      [
        { name: 'Norway', countryCode: 'NO', ...DEFAULT_SHIPPING },
        { name: 'Norge', countryCode: 'NO', ...DEFAULT_SHIPPING },
      ],
    ] as const) {
      const results = await Promise.all([countries.create(first), countries.create(second)])
      expect(results.filter((result) => result.kind === 'ok')).toHaveLength(1)
      expect(results.filter((result) => result.kind === 'conflict')).toHaveLength(1)
    }
    expect(await countries.list()).toHaveLength(2)
  })
})

describe('delete and the stored code', () => {
  beforeEach(() => resetCountries(false))

  test('delete removes the row and then reports it missing', async () => {
    await sql`INSERT INTO countries (name, country_code, shipping_cost_cents, free_shipping_threshold_cents)
      VALUES ('Germany', 'DE', 490, 5000)`.execute(database.db)
    const countries = repository()
    expect(await countries.delete(1)).toEqual({ kind: 'ok', value: undefined })
    expect(await countries.delete(1)).toEqual({ kind: 'notFound' })
    expect(await countries.list()).toEqual([])
  })

  test('create and update store the code upper-cased along with the shipping policy', async () => {
    const countries = repository()
    const created = await countries.create({
      name: 'Denmark',
      countryCode: 'dk',
      shippingCostCents: 990,
      freeShippingThresholdCents: null,
    })
    expect(created).toEqual({
      kind: 'ok',
      value: {
        id: 1,
        name: 'Denmark',
        countryCode: 'DK',
        shippingCostCents: 990,
        freeShippingThresholdCents: null,
      },
    })
    expect(
      await countries.update(1, {
        name: 'Danmark',
        countryCode: 'dK',
        shippingCostCents: 890,
        freeShippingThresholdCents: 7500,
      }),
    ).toEqual({
      kind: 'ok',
      value: {
        id: 1,
        name: 'Danmark',
        countryCode: 'DK',
        shippingCostCents: 890,
        freeShippingThresholdCents: 7500,
      },
    })
    expect(await countries.list()).toEqual([
      {
        id: 1,
        name: 'Danmark',
        countryCode: 'DK',
        shippingCostCents: 890,
        freeShippingThresholdCents: 7500,
      },
    ])
  })
})

describe('the exported capabilities', () => {
  beforeEach(() => resetCountries(true))

  test('find answers the known ids and leaves unknown ones out', async () => {
    const found = await repository().find([1, 2, 999])
    expect([...found.entries()]).toEqual([
      [1, { id: 1, name: 'Germany', countryCode: 'DE', ...DEFAULT_SHIPPING }],
      [2, { id: 2, name: 'France', countryCode: 'FR', ...DEFAULT_SHIPPING }],
    ])
    expect((await repository().find([])).size).toBe(0)
  })

  test('shippingPolicy: returns the policy for stored codes however spelled, null otherwise', async () => {
    const countries = repository()
    for (const code of ['DE', 'de', ' de ', 'SE'])
      expect(await countries.shippingPolicy(code)).toEqual(DEFAULT_SHIPPING)
    for (const code of ['XX', '', '   ', 'D', 'DEU'])
      expect(await countries.shippingPolicy(code)).toBeNull()
  })

  test('the country admin opens, updates, and closes a destination', async () => {
    const countries = repository()
    expect(await countries.shippingPolicy('DK')).toBeNull()
    const created = await countries.create({
      name: 'Denmark',
      countryCode: 'dk',
      shippingCostCents: 1190,
      freeShippingThresholdCents: null,
    })
    expect(await countries.shippingPolicy('DK')).toEqual({
      shippingCostCents: 1190,
      freeShippingThresholdCents: null,
    })
    if (created.kind === 'ok') {
      await countries.update(created.value.id, {
        name: 'Denmark',
        countryCode: 'DK',
        shippingCostCents: 990,
        freeShippingThresholdCents: 8000,
      })
      expect(await countries.shippingPolicy('DK')).toEqual({
        shippingCostCents: 990,
        freeShippingThresholdCents: 8000,
      })
      expect((await countries.delete(created.value.id)).kind).toBe('ok')
    }
    expect(await countries.shippingPolicy('DK')).toBeNull()
  })
})
