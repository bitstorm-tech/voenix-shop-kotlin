import { effectScope, type EffectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAdminPriceForm } from '../useAdminPriceForm'
import type { AdminPriceDto, AdminPriceInputDto } from '@/stores/admin/prices'

const mocks = vi.hoisted(() => ({
  fetchDefaultPrice: vi.fn(),
  calculatePrice: vi.fn(),
}))

vi.mock('@/stores/admin/prices', () => ({
  fetchDefaultPrice: mocks.fetchDefaultPrice,
  calculatePrice: mocks.calculatePrice,
}))

const standardVat = { id: 1, name: 'Standard', percent: 19 }

function priceDto(overrides: Partial<AdminPriceDto> = {}): AdminPriceDto {
  return {
    id: null,
    purchaseVatId: standardVat.id,
    purchaseCalculationMode: 'NET',
    purchaseActiveRow: 'COST',
    purchasePriceInputCents: 0,
    purchaseCostInputCents: 0,
    purchaseCostPercent: 0,
    salesVatId: standardVat.id,
    salesCalculationMode: 'GROSS',
    salesActiveRow: 'TOTAL',
    salesMarginInputCents: 0,
    salesMarginPercent: 0,
    salesTotalInputCents: 0,
    purchaseVat: standardVat,
    purchasePrice: { net: 0, tax: 0, gross: 0 },
    purchaseCost: { net: 0, tax: 0, gross: 0 },
    calculatedPurchaseCostPercent: 0,
    purchaseTotal: { net: 0, tax: 0, gross: 0 },
    salesVat: standardVat,
    salesMargin: { net: 0, tax: 0, gross: 0 },
    calculatedSalesMarginPercent: 0,
    salesTotal: { net: 0, tax: 0, gross: 0 },
    ...overrides,
  }
}

function createPriceForm(persistence: 'optional' | 'required') {
  const scope = effectScope()
  const controller = scope.run(() => useAdminPriceForm({ persistence }))

  if (!controller) {
    throw new Error('Price form scope did not initialize.')
  }

  return { controller, scope }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((promiseResolve) => {
    resolve = promiseResolve
  })
  return { promise, resolve }
}

function stop(scope: EffectScope) {
  scope.stop()
}

describe('useAdminPriceForm', () => {
  beforeEach(() => {
    mocks.fetchDefaultPrice.mockReset()
    mocks.calculatePrice.mockReset()
    mocks.fetchDefaultPrice.mockResolvedValue(priceDto())
    mocks.calculatePrice.mockImplementation(async (payload: AdminPriceInputDto) =>
      priceDto({
        ...payload,
        purchasePrice: {
          net: payload.purchasePriceInputCents,
          tax: 0,
          gross: payload.purchasePriceInputCents,
        },
      }),
    )
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('omits an untouched initialized Price when persistence is optional', async () => {
    const { controller, scope } = createPriceForm('optional')

    await controller.initialize(null)

    expect(controller.isDirty.value).toBe(false)
    expect(controller.shouldPersistPrice.value).toBe(false)
    expect(controller.validateForSave()).toBe(true)
    expect(controller.getSavePayload()).toBeUndefined()
    stop(scope)
  })

  it('persists an untouched zero-valued default Price when persistence is required', async () => {
    const { controller, scope } = createPriceForm('required')

    await controller.initialize(null)

    expect(controller.isDirty.value).toBe(false)
    expect(controller.shouldPersistPrice.value).toBe(true)
    expect(controller.validateForSave()).toBe(true)
    expect(controller.getSavePayload()).toEqual({
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
    })
    stop(scope)
  })

  it('hydrates and persists an existing Price without loading the default', async () => {
    const existingPrice = priceDto({ id: 8, purchasePriceInputCents: 700 })
    const { controller, scope } = createPriceForm('optional')

    await controller.initialize(existingPrice)

    expect(mocks.fetchDefaultPrice).not.toHaveBeenCalled()
    expect(controller.fields.purchasePrice).toBe('7,00')
    expect(controller.shouldPersistPrice.value).toBe(true)
    expect(controller.getSavePayload()?.purchasePriceInputCents).toBe(700)
    stop(scope)
  })

  it('parses German-formatted input and debounces authoritative calculation', async () => {
    vi.useFakeTimers()
    const { controller, scope } = createPriceForm('optional')
    await controller.initialize(null)

    controller.setPurchasePrice('1.234,56')

    expect(controller.isDirty.value).toBe(true)
    expect(controller.form.purchasePriceInputCents).toBe(123456)
    expect(controller.isCalculationPending.value).toBe(true)
    expect(controller.validateForSave()).toBe(false)
    expect(controller.getSavePayload()).toBeUndefined()
    expect(mocks.calculatePrice).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(349)
    expect(mocks.calculatePrice).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1)
    expect(mocks.calculatePrice).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ purchasePriceInputCents: 123456 }),
    )
    expect(controller.lastCalculatedPrice.value?.purchasePrice.net).toBe(123456)
    expect(controller.isCalculationPending.value).toBe(false)
    expect(controller.validateForSave()).toBe(true)
    stop(scope)
  })

  it('reports active input validation errors through its public save contract', async () => {
    const { controller, scope } = createPriceForm('optional')
    await controller.initialize(null)

    controller.setPurchaseCost('invalid')

    expect(controller.inputError.value).toBe('Einkaufskosten muss eine gültige Dezimalzahl sein.')
    expect(controller.validateForSave()).toBe(false)
    expect(controller.getSavePayload()).toBeUndefined()
    expect(mocks.calculatePrice).not.toHaveBeenCalled()
    stop(scope)
  })

  it('ignores an obsolete calculation when the input changes during the request', async () => {
    vi.useFakeTimers()
    const firstCalculation = deferred<AdminPriceDto>()
    mocks.calculatePrice
      .mockReturnValueOnce(firstCalculation.promise)
      .mockImplementationOnce(async (payload: AdminPriceInputDto) => priceDto(payload))
    const { controller, scope } = createPriceForm('required')
    await controller.initialize(null)

    controller.setPurchasePrice('1,00')
    await vi.advanceTimersByTimeAsync(350)
    controller.setPurchasePrice('2,00')
    firstCalculation.resolve(priceDto({ purchasePriceInputCents: 100 }))
    await Promise.resolve()

    expect(controller.isCalculationPending.value).toBe(true)
    expect(controller.form.purchasePriceInputCents).toBe(200)

    await vi.advanceTimersByTimeAsync(350)
    expect(controller.isCalculationPending.value).toBe(false)
    expect(controller.form.purchasePriceInputCents).toBe(200)
    stop(scope)
  })

  it('blocks required persistence when the default Price cannot be initialized', async () => {
    mocks.fetchDefaultPrice.mockRejectedValueOnce(new Error('No VAT is configured'))
    const { controller, scope } = createPriceForm('required')

    await controller.initialize(null)

    expect(controller.setupError.value).toBe('No VAT is configured')
    expect(controller.validateForSave()).toBe(false)
    expect(controller.getSavePayload()).toBeUndefined()
    stop(scope)
  })

  it('keeps server-side non-negative validation authoritative', async () => {
    vi.useFakeTimers()
    mocks.calculatePrice.mockRejectedValueOnce(new Error('Sales total must not be negative'))
    const { controller, scope } = createPriceForm('required')
    await controller.initialize(null)

    controller.setSalesTotal('-1,00')
    await vi.runAllTimersAsync()

    expect(controller.error.value).toBe('Sales total must not be negative')
    expect(controller.validateForSave()).toBe(false)
    expect(controller.getSavePayload()).toBeUndefined()
    stop(scope)
  })
})
