import { effectScope, type EffectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAdminPriceForm } from '../useAdminPriceForm'
import type { AdminPriceDto, AdminPriceInputDto } from '@/stores/admin/prices'
import { ApiError } from '@/lib/api'

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
    regularSalesMargin: { net: 0, tax: 0, gross: 0 },
    calculatedRegularSalesMarginPercent: 0,
    regularSalesTotal: { net: 0, tax: 0, gross: 0 },
    discount: null,
    salesDiscount: { net: 0, tax: 0, gross: 0 },
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
      discountType: null,
      discountValue: null,
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

    expect(controller.inputError.value).toBe('Purchase costs must be a valid decimal number.')
    expect(controller.validateForSave()).toBe(false)
    expect(controller.getSavePayload()).toBeUndefined()
    expect(mocks.calculatePrice).not.toHaveBeenCalled()
    stop(scope)
  })

  it('rejects a purchase cost percentage with more than two decimal places', async () => {
    vi.useFakeTimers()
    const { controller, scope } = createPriceForm('optional')
    await controller.initialize(null)
    controller.setPurchaseActiveRow('COST_PERCENT')
    mocks.calculatePrice.mockClear()

    controller.setPurchaseCostPercent('12,345')
    await vi.runAllTimersAsync()

    expect(controller.inputError.value).toBe(
      'Purchase costs % must not have more than two decimal places.',
    )
    expect(controller.fields.purchaseCostPercent).toBe('12,345')
    expect(controller.form.purchaseCostPercent).toBe(0)
    expect(mocks.calculatePrice).not.toHaveBeenCalled()
    expect(controller.validateForSave()).toBe(false)
    stop(scope)
  })

  it('accepts a percentage whose trailing zero adds no precision', async () => {
    vi.useFakeTimers()
    const { controller, scope } = createPriceForm('optional')
    await controller.initialize(null)
    controller.setPurchaseActiveRow('COST_PERCENT')

    controller.setPurchaseCostPercent('12,340')
    await vi.runAllTimersAsync()

    expect(controller.inputError.value).toBeNull()
    expect(mocks.calculatePrice).toHaveBeenCalledWith(
      expect.objectContaining({ purchaseCostPercent: 12.34 }),
    )
    stop(scope)
  })

  it('rejects a purchase cost percentage outside the backend range', async () => {
    vi.useFakeTimers()
    const { controller, scope } = createPriceForm('optional')
    await controller.initialize(null)
    controller.setPurchaseActiveRow('COST_PERCENT')
    mocks.calculatePrice.mockClear()

    controller.setPurchaseCostPercent('10000')
    await vi.runAllTimersAsync()
    expect(controller.inputError.value).toBe('Purchase costs % must be between 0 and 9.999,99.')

    controller.setPurchaseCostPercent('-1')
    await vi.runAllTimersAsync()
    expect(controller.inputError.value).toBe('Purchase costs % must be between 0 and 9.999,99.')

    expect(mocks.calculatePrice).not.toHaveBeenCalled()
    stop(scope)
  })

  it('allows a negative sales margin percentage down to the backend minimum', async () => {
    vi.useFakeTimers()
    const { controller, scope } = createPriceForm('optional')
    await controller.initialize(null)
    controller.setSalesActiveRow('MARGIN_PERCENT')

    controller.setSalesMarginPercent('-9999,99')
    await vi.runAllTimersAsync()
    expect(controller.inputError.value).toBeNull()
    expect(mocks.calculatePrice).toHaveBeenCalledWith(
      expect.objectContaining({ salesMarginPercent: -9999.99 }),
    )

    mocks.calculatePrice.mockClear()
    controller.setSalesMarginPercent('-10000')
    await vi.runAllTimersAsync()
    expect(controller.inputError.value).toBe('Margin % must be between -9.999,99 and 9.999,99.')
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

  it('sends the discount pair for each discount state', async () => {
    vi.useFakeTimers()
    const { controller, scope } = createPriceForm('required')
    await controller.initialize(null)

    controller.setDiscountType('PERCENTAGE')
    controller.setDiscountValue('20')
    await vi.runAllTimersAsync()
    expect(mocks.calculatePrice).toHaveBeenCalledWith(
      expect.objectContaining({ discountType: 'PERCENTAGE', discountValue: 20 }),
    )

    controller.setDiscountType('FIXED_AMOUNT')
    controller.setDiscountValue('3,98')
    await vi.runAllTimersAsync()
    expect(mocks.calculatePrice).toHaveBeenCalledWith(
      expect.objectContaining({ discountType: 'FIXED_AMOUNT', discountValue: 398 }),
    )

    controller.setDiscountType(null)
    await vi.runAllTimersAsync()
    expect(controller.inputError.value).toBeNull()
    expect(controller.getSavePayload()).toEqual(
      expect.objectContaining({ discountType: null, discountValue: null }),
    )
    stop(scope)
  })

  it('blocks a discount kind without a value until one is typed', async () => {
    vi.useFakeTimers()
    const { controller, scope } = createPriceForm('required')
    await controller.initialize(null)
    mocks.calculatePrice.mockClear()

    controller.setDiscountType('PERCENTAGE')
    await vi.runAllTimersAsync()

    expect(controller.inputError.value).toBe('Discount value is required.')
    expect(mocks.calculatePrice).not.toHaveBeenCalled()
    expect(controller.getSavePayload()).toBeUndefined()
    stop(scope)
  })

  it('rejects a percentage discount above the backend maximum or with three decimals', async () => {
    vi.useFakeTimers()
    const { controller, scope } = createPriceForm('required')
    await controller.initialize(null)
    controller.setDiscountType('PERCENTAGE')
    mocks.calculatePrice.mockClear()

    controller.setDiscountValue('100,01')
    await vi.runAllTimersAsync()
    expect(controller.inputError.value).toBe('Discount must be greater than 0 and at most 100.')

    controller.setDiscountValue('12,345')
    await vi.runAllTimersAsync()
    expect(controller.inputError.value).toBe('Discount must not have more than two decimal places.')

    controller.setDiscountValue('100')
    await vi.runAllTimersAsync()
    expect(controller.inputError.value).toBeNull()
    expect(mocks.calculatePrice).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ discountValue: 100 }),
    )
    stop(scope)
  })

  it('rejects an empty or non-positive discount of either kind before it is sent', async () => {
    vi.useFakeTimers()
    const { controller, scope } = createPriceForm('required')
    await controller.initialize(null)
    controller.setDiscountType('PERCENTAGE')
    mocks.calculatePrice.mockClear()

    controller.setDiscountValue('')
    await vi.runAllTimersAsync()
    expect(controller.inputError.value).toBe('Discount value is required.')

    controller.setDiscountValue('0')
    await vi.runAllTimersAsync()
    expect(controller.inputError.value).toBe('Discount must be greater than 0.')

    controller.setDiscountType('FIXED_AMOUNT')
    controller.setDiscountValue('0,00')
    await vi.runAllTimersAsync()
    expect(controller.inputError.value).toBe('Discount must be greater than 0.')

    controller.setDiscountValue('-1,00')
    await vi.runAllTimersAsync()
    expect(controller.inputError.value).toBe('Discount must be greater than 0.')

    expect(mocks.calculatePrice).not.toHaveBeenCalled()
    expect(controller.getSavePayload()).toBeUndefined()
    stop(scope)
  })

  it('shows a rejected discount from the backend on the discount field', async () => {
    vi.useFakeTimers()
    mocks.calculatePrice.mockRejectedValueOnce(
      new ApiError('Validation failed', 400, {
        errors: { discountValue: ['Discount must not exceed the sales total'] },
      }),
    )
    const { controller, scope } = createPriceForm('required')
    await controller.initialize(null)

    controller.setDiscountType('FIXED_AMOUNT')
    controller.setDiscountValue('99,00')
    await vi.runAllTimersAsync()

    expect(controller.inputError.value).toBe('Discount must not exceed the sales total')
    expect(controller.error.value).toBeNull()
    expect(controller.getSavePayload()).toBeUndefined()
    stop(scope)
  })

  it('seeds a Netto/Brutto toggle from the regular sales total, not the discounted one', async () => {
    vi.useFakeTimers()
    const discountedPrice = priceDto({
      salesCalculationMode: 'GROSS',
      salesActiveRow: 'TOTAL',
      salesTotalInputCents: 1990,
      regularSalesTotal: { net: 1672, tax: 318, gross: 1990 },
      calculatedRegularSalesMarginPercent: 30,
      discount: { discountType: 'PERCENTAGE', discountValue: 20 },
      salesDiscount: { net: 334, tax: 64, gross: 398 },
      salesTotal: { net: 1338, tax: 254, gross: 1592 },
      calculatedSalesMarginPercent: 6.5,
    })
    const { controller, scope } = createPriceForm('required')
    await controller.initialize(discountedPrice)
    mocks.calculatePrice.mockResolvedValue(discountedPrice)

    controller.setSalesCalculationMode('NET')

    expect(controller.form.salesTotalInputCents).toBe(1672)

    await vi.runAllTimersAsync()
    expect(mocks.calculatePrice).toHaveBeenCalledWith(
      expect.objectContaining({ salesTotalInputCents: 1672 }),
    )

    controller.setSalesActiveRow('MARGIN_PERCENT')

    expect(controller.form.salesMarginPercent).toBe(30)
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
