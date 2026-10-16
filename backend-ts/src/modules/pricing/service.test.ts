import { beforeEach, describe, expect, test } from 'bun:test'
import { sql, type Transaction } from 'kysely'
import { createPricingModule, type CalculatedPrice, type PriceInput } from '@/modules/pricing'
import { createPricingRepository } from '@/modules/pricing/repository'
import { createPriceService } from '@/modules/pricing/service'
import { write, type Tables } from '@/platform/db/database'
import { testDatabase } from '@test-support/database'

const database = testDatabase()
const repository = () => createPricingRepository(database.db)
const prices = () => createPriceService(repository())
const vats = repository
const catalog = () => createPricingModule({ db: database.db }).priceCatalog

const priceInput = (overrides: Partial<PriceInput> = {}): PriceInput => ({
  purchaseVatId: 1,
  purchaseCalculationMode: 'NET',
  purchaseActiveRow: 'COST',
  purchasePriceInputCents: 0,
  purchaseCostInputCents: 0,
  purchaseCostPercent: 0,
  salesVatId: 1,
  salesCalculationMode: 'GROSS',
  salesActiveRow: 'TOTAL',
  salesMarginInputCents: 0,
  salesMarginPercent: 0,
  salesTotalInputCents: 0,
  discountType: null,
  discountValue: null,
  ...overrides,
})

const reset = async (seedVats: boolean) => {
  await sql`TRUNCATE prices, value_added_taxes RESTART IDENTITY CASCADE`.execute(database.db)
  if (seedVats) {
    await sql`INSERT INTO value_added_taxes (name, percent, description, is_default)
      VALUES ('Standard', 19, NULL, TRUE), ('Reduced', 7, NULL, FALSE)`.execute(database.db)
  }
}
const setVatPercent = (percent: number) =>
  sql`UPDATE value_added_taxes SET percent = ${percent} WHERE id = 1`.execute(database.db)
const priceCount = async () => {
  const { rows } = await sql<{ count: number }>`SELECT count(*)::int AS count FROM prices`.execute(
    database.db,
  )
  return rows[0]?.count
}

/** Prepares `input`, which must succeed, and stores it in its own transaction. */
const store = async (input: PriceInput): Promise<{ id: number; prepared: CalculatedPrice }> => {
  const prepared = await prices().prepare(input)
  if (prepared.kind !== 'ok') throw new Error(`prepare answered ${prepared.kind}`)
  const id = await write(database.db, (trx) => catalog().storeInTransaction(trx, prepared.value))
  return { id, prepared: prepared.value }
}

class RollbackMarker extends Error {}

describe('prepare', () => {
  beforeEach(() => reset(true))

  test('zeroes inactive inputs, calculates with the current VAT, and stores nothing', async () => {
    const result = await prices().prepare(
      priceInput({
        purchasePriceInputCents: 1_000,
        purchaseActiveRow: 'COST_PERCENT',
        purchaseCostInputCents: -500,
        purchaseCostPercent: 12.34,
        salesActiveRow: 'MARGIN_PERCENT',
        salesMarginInputCents: -500,
        salesMarginPercent: 25.12,
        salesTotalInputCents: -9_999,
      }),
    )
    expect(result.kind).toBe('ok')
    if (result.kind !== 'ok') return
    expect(result.value).toMatchObject({
      id: null,
      purchaseCostInputCents: 0,
      purchaseCostPercent: 12.34,
      salesMarginInputCents: 0,
      salesMarginPercent: 25.12,
      salesTotalInputCents: 0,
      purchaseVat: { id: 1, name: 'Standard', percent: 19, description: null, isDefault: true },
    })
    expect(await priceCount()).toBe(0)
  })

  test('reports both unknown VAT entries', async () => {
    expect(await prices().prepare(priceInput({ purchaseVatId: 404, salesVatId: 405 }))).toEqual({
      kind: 'invalid',
      errors: { purchaseVatId: ['Purchase VAT not found'], salesVatId: ['Sales VAT not found'] },
    })
  })

  test('rejects a negative regular total and a discount that does not fit the price', async () => {
    const cases: [Partial<PriceInput>, Record<string, string[]>][] = [
      [
        {
          purchasePriceInputCents: 1_000,
          salesCalculationMode: 'NET',
          salesActiveRow: 'MARGIN',
          salesMarginInputCents: -1_001,
        },
        { salesMarginInputCents: ['Sales total must not be negative'] },
      ],
      [
        { salesTotalInputCents: 1_190, discountType: 'FIXED_AMOUNT', discountValue: 1_191 },
        { discountValue: ['Discount must not exceed the sales total'] },
      ],
      [
        { salesTotalInputCents: 1_990, discountType: 'FIXED_AMOUNT', discountValue: 2_147_483_648 },
        { discountValue: ['Discount must not exceed the sales total'] },
      ],
      [
        { salesTotalInputCents: 499, discountType: 'PERCENTAGE', discountValue: 0.01 },
        { discountValue: ['Discount must reduce the sales total'] },
      ],
    ]
    for (const [input, errors] of cases) {
      expect(await prices().prepare(priceInput(input))).toEqual({ kind: 'invalid', errors })
    }
  })
})

describe('find and the default price', () => {
  beforeEach(() => reset(true))

  test('find recalculates with the current VAT, keeps a stored discount, and omits unknown ids', async () => {
    const discounted = await store(
      priceInput({ salesTotalInputCents: 1_990, discountType: 'PERCENTAGE', discountValue: 20 }),
    )
    const plain = await store(
      priceInput({ purchaseVatId: 2, salesVatId: 2, salesTotalInputCents: 1_070 }),
    )
    expect((await catalog().find([discounted.id, plain.id, 999])).get(discounted.id)).toEqual({
      ...discounted.prepared,
      id: discounted.id,
    })

    await setVatPercent(7)
    const found = await catalog().find([discounted.id, plain.id, 999])
    expect([...found.keys()]).toEqual([discounted.id, plain.id])
    expect(found.get(discounted.id)?.discount).toEqual({
      discountType: 'PERCENTAGE',
      discountValue: 20,
    })
    expect(found.get(discounted.id)?.salesTotal).toEqual({ net: 1_488, tax: 104, gross: 1_592 })
    expect(found.get(plain.id)?.salesTotal).toEqual({ net: 1_000, tax: 70, gross: 1_070 })
    expect((await catalog().find([])).size).toBe(0)
  })

  test('a VAT change that shrinks the price below a stored fixed discount sells it for zero', async () => {
    const { id, prepared } = await store(
      priceInput({
        salesCalculationMode: 'NET',
        salesTotalInputCents: 1_500,
        discountType: 'FIXED_AMOUNT',
        discountValue: 1_700,
      }),
    )
    expect(prepared.regularSalesTotal.gross).toBe(1_785)
    expect(prepared.salesTotal.gross).toBe(85)
    await setVatPercent(7)
    const read = (await catalog().find([id])).get(id)
    expect(read?.regularSalesTotal.gross).toBe(1_605)
    expect(read?.salesTotal).toEqual({ net: 0, tax: 0, gross: 0 })
  })

  test('the default price uses the default VAT, then the smallest id, and needs a VAT', async () => {
    await sql`UPDATE value_added_taxes SET is_default = (id = 2)`.execute(database.db)
    expect((await prices().defaultPrice())?.purchaseVatId).toBe(2)
    await sql`UPDATE value_added_taxes SET is_default = FALSE`.execute(database.db)
    const fallback = await prices().defaultPrice()
    expect(fallback).toMatchObject({ id: null, purchaseVatId: 1, salesVatId: 1, discount: null })
    expect(await priceCount()).toBe(0)
    await sql`DELETE FROM value_added_taxes`.execute(database.db)
    expect(await prices().defaultPrice()).toBeUndefined()
  })
})

describe('the priceCatalog writes join the caller transaction', () => {
  beforeEach(() => reset(true))

  test('a failing outer transaction leaves no stored price', async () => {
    const prepared = await prices().prepare(priceInput({ salesTotalInputCents: 1_190 }))
    if (prepared.kind !== 'ok') throw new Error('prepare failed')
    const failure = await write(database.db, async (trx) => {
      await catalog().storeInTransaction(trx, prepared.value)
      throw new RollbackMarker()
    }).catch((error: unknown) => error)
    expect(failure).toBeInstanceOf(RollbackMarker)
    expect(await priceCount()).toBe(0)
  })

  test('a failing outer transaction keeps the replaced and the deleted price as they were', async () => {
    const { id } = await store(priceInput({ salesTotalInputCents: 1_190 }))
    const replacement = await prices().prepare(priceInput({ salesTotalInputCents: 2_380 }))
    if (replacement.kind !== 'ok') throw new Error('prepare failed')
    for (const change of [
      (trx: Transaction<Tables>) => catalog().replaceInTransaction(trx, id, replacement.value),
      (trx: Transaction<Tables>) => catalog().deleteInTransaction(trx, id),
    ]) {
      const failure = await write(database.db, async (trx) => {
        expect(await change(trx)).toBe(true)
        throw new RollbackMarker()
      }).catch((error: unknown) => error)
      expect(failure).toBeInstanceOf(RollbackMarker)
      expect((await catalog().find([id])).get(id)?.salesTotalInputCents).toBe(1_190)
    }
  })

  test('replace and delete report whether the price existed', async () => {
    const { id, prepared } = await store(priceInput({ salesTotalInputCents: 1_190 }))
    const replaced = await write(database.db, (trx) =>
      catalog().replaceInTransaction(trx, id, prepared),
    )
    expect(replaced).toBe(true)
    expect(
      await write(database.db, (trx) => catalog().replaceInTransaction(trx, 999, prepared)),
    ).toBe(false)
    expect(await write(database.db, (trx) => catalog().deleteInTransaction(trx, id))).toBe(true)
    expect(await write(database.db, (trx) => catalog().deleteInTransaction(trx, id))).toBe(false)
  })
})

describe('the VAT entries of a price write', () => {
  beforeEach(() => reset(true))

  /** The owner maps a foreign-key violation of its own; the price write must never raise one. */
  test('a VAT deleted after prepare fails the store and the replace without a foreign-key violation', async () => {
    const { id } = await store(priceInput({ salesTotalInputCents: 1_190 }))
    const prepared = await prices().prepare(priceInput({ salesVatId: 2 }))
    if (prepared.kind !== 'ok') throw new Error('prepare failed')
    expect(await vats().deleteVat(2)).toEqual({ kind: 'ok', value: undefined })

    const changes: ((trx: Transaction<Tables>) => Promise<unknown>)[] = [
      (trx) => catalog().storeInTransaction(trx, prepared.value),
      (trx) => catalog().replaceInTransaction(trx, id, prepared.value),
    ]
    for (const change of changes) {
      const failure = await write(database.db, change).catch((error: unknown) => error)
      expect(failure).toBeInstanceOf(Error)
      expect((failure as { code?: unknown }).code).toBeUndefined()
    }
    expect(await priceCount()).toBe(1)
  })

  test('a VAT delete racing a price store waits for it and then fails as in use', async () => {
    const prepared = await prices().prepare(priceInput({ salesVatId: 2 }))
    if (prepared.kind !== 'ok') throw new Error('prepare failed')

    let deleteWasBlocked = false
    const { racing } = await write(database.db, async (trx) => {
      await catalog().storeInTransaction(trx, prepared.value)
      const racing = vats().deleteVat(2)
      // The poll gives up after five seconds.
      for (let attempt = 0; attempt < 100 && !deleteWasBlocked; attempt++) {
        const { rows } = await sql<{ waiting: number }>`
          SELECT count(*) AS waiting FROM pg_stat_activity
          WHERE datname = current_database() AND wait_event_type = 'Lock'`.execute(database.db)
        deleteWasBlocked = (rows[0]?.waiting ?? 0) > 0
        if (!deleteWasBlocked) await Bun.sleep(50)
      }
      // Wrapped, so the transaction commits (and frees the lock) before the delete is awaited.
      return { racing }
    })

    expect(deleteWasBlocked).toBe(true)
    expect(await racing).toEqual({ kind: 'conflict' })
    expect(await priceCount()).toBe(1)
  })
})

describe('VAT entries', () => {
  beforeEach(() => reset(false))

  test('the list is ordered by name, then id', async () => {
    for (const name of ['Standard', 'Reduced', 'Zero'])
      await vats().createVat({ name, percent: 7, description: null, isDefault: false })
    expect((await vats().listVats()).map((vat) => vat.name)).toEqual([
      'Reduced',
      'Standard',
      'Zero',
    ])
  })

  test('create and update move the default; false and delete may leave none', async () => {
    const service = vats()
    const standard = await service.createVat({
      name: 'Standard',
      percent: 19,
      description: null,
      isDefault: true,
    })
    const reduced = await service.createVat({
      name: 'Reduced',
      percent: 7,
      description: null,
      isDefault: false,
    })
    if (standard.kind !== 'ok' || reduced.kind !== 'ok') throw new Error('create failed')
    const defaults = async () =>
      (await service.listVats()).filter((vat) => vat.isDefault).map((vat) => vat.id)

    expect(
      await service.updateVat(reduced.value.id, {
        name: 'Reduced rate',
        percent: 7,
        description: 'Lower',
        isDefault: true,
      }),
    ).toEqual({
      kind: 'ok',
      value: {
        id: reduced.value.id,
        name: 'Reduced rate',
        percent: 7,
        description: 'Lower',
        isDefault: true,
      },
    })
    expect(await defaults()).toEqual([reduced.value.id])
    await service.updateVat(reduced.value.id, {
      name: 'Reduced rate',
      percent: 7,
      description: null,
      isDefault: false,
    })
    expect(await defaults()).toEqual([])
    await service.updateVat(standard.value.id, {
      name: 'Standard',
      percent: 19,
      description: null,
      isDefault: true,
    })
    expect(
      await service.updateVat(999, {
        name: 'Missing',
        percent: 19,
        description: null,
        isDefault: true,
      }),
    ).toEqual({
      kind: 'notFound',
    })
    expect(await defaults()).toEqual([standard.value.id])
    expect(await service.deleteVat(standard.value.id)).toEqual({ kind: 'ok', value: undefined })
    expect(await service.deleteVat(standard.value.id)).toEqual({ kind: 'notFound' })
    expect(await defaults()).toEqual([])
  })

  test('a duplicate name conflicts and rolls back the demotion; a case variant is another name', async () => {
    const service = vats()
    await service.createVat({ name: 'Standard', percent: 19, description: null, isDefault: true })
    const reduced = await service.createVat({
      name: 'Reduced',
      percent: 7,
      description: null,
      isDefault: false,
    })
    if (reduced.kind !== 'ok') throw new Error('create failed')
    expect(
      await service.createVat({
        name: 'Standard',
        percent: 20,
        description: null,
        isDefault: true,
      }),
    ).toEqual({
      kind: 'conflict',
    })
    expect(
      await service.updateVat(reduced.value.id, {
        name: 'Standard',
        percent: 7,
        description: null,
        isDefault: true,
      }),
    ).toEqual({
      kind: 'conflict',
    })
    expect(
      (
        await service.createVat({
          name: 'standard',
          percent: 7,
          description: null,
          isDefault: false,
        })
      ).kind,
    ).toBe('ok')
    expect((await service.listVats()).map((vat) => [vat.name, vat.isDefault])).toEqual([
      ['Reduced', false],
      ['Standard', true],
      ['standard', false],
    ])
  })

  test('concurrent creates with the same name leave one row and one conflict', async () => {
    const service = vats()
    const results = await Promise.all([
      service.createVat({ name: 'Standard', percent: 19, description: null, isDefault: false }),
      service.createVat({ name: 'Standard', percent: 7, description: null, isDefault: false }),
    ])
    expect(results.filter((result) => result.kind === 'ok')).toHaveLength(1)
    expect(results.filter((result) => result.kind === 'conflict')).toHaveLength(1)
    expect(await service.listVats()).toHaveLength(1)
  })

  test('concurrent default writes never leave more than one default', async () => {
    const service = vats()
    const results = await Promise.all([
      service.createVat({ name: 'Standard', percent: 19, description: null, isDefault: true }),
      service.createVat({ name: 'Reduced', percent: 7, description: null, isDefault: true }),
    ])
    expect(results.some((result) => result.kind === 'ok')).toBe(true)
    expect(results.every((result) => result.kind === 'ok' || result.kind === 'conflict')).toBe(true)
    expect((await service.listVats()).filter((vat) => vat.isDefault)).toHaveLength(1)
  })

  test('an entry a price references cannot be deleted until the price is gone', async () => {
    await reset(true)
    const { id } = await store(priceInput({ purchaseVatId: 1, salesVatId: 2 }))
    expect(await vats().deleteVat(1)).toEqual({ kind: 'conflict' })
    expect(await vats().deleteVat(2)).toEqual({ kind: 'conflict' })
    await write(database.db, (trx) => catalog().deleteInTransaction(trx, id))
    expect(await vats().deleteVat(1)).toEqual({ kind: 'ok', value: undefined })
  })
})
