import { describe, expect, test } from 'bun:test'
import { priceInput } from '@/modules/pricing/routes'
import { vatInput } from '@/modules/pricing/vat-routes'
import { validationFailed } from '@/platform/http/respond'

const errorsOf = (schema: typeof priceInput | typeof vatInput, input: unknown) => {
  const result = schema.safeParse(input)
  return result.success ? {} : validationFailed(result.error).errors
}
const priceErrors = (input: Record<string, unknown>) =>
  errorsOf(priceInput, { purchaseVatId: 1, salesVatId: 1, ...input })

describe('priceInput', () => {
  test('omitted fields take their defaults and a known discount type is narrowed', () => {
    expect(priceInput.parse({ purchaseVatId: 1, salesVatId: 2 })).toEqual({
      purchaseVatId: 1,
      purchaseCalculationMode: 'NET',
      purchaseActiveRow: 'COST',
      purchasePriceInputCents: 0,
      purchaseCostInputCents: 0,
      purchaseCostPercent: 0,
      salesVatId: 2,
      salesCalculationMode: 'GROSS',
      salesActiveRow: 'TOTAL',
      salesMarginInputCents: 0,
      salesMarginPercent: 0,
      salesTotalInputCents: 0,
      discountType: null,
      discountValue: null,
    })
    expect(
      priceInput.parse({
        purchaseVatId: 1,
        salesVatId: 1,
        discountType: 'PERCENTAGE',
        discountValue: 20,
      }).discountType,
    ).toBe('PERCENTAGE')
  })

  test('the VAT ids and the always-active purchase price, reported together', () => {
    expect(errorsOf(priceInput, { purchasePriceInputCents: -1 })).toEqual({
      purchaseVatId: ['Purchase VAT id is required'],
      salesVatId: ['Sales VAT id is required'],
      purchasePriceInputCents: ['Purchase price input must not be negative'],
    })
    expect(errorsOf(priceInput, { purchaseVatId: 0, salesVatId: null })).toEqual({
      purchaseVatId: ['Purchase VAT id is required'],
      salesVatId: ['Sales VAT id is required'],
    })
  })

  test('only the active row of each side is checked', () => {
    expect(priceErrors({ purchaseCostInputCents: -1, purchaseCostPercent: -1 })).toEqual({
      purchaseCostInputCents: ['Purchase cost input must not be negative'],
    })
    expect(
      priceErrors({
        purchaseActiveRow: 'COST_PERCENT',
        purchaseCostInputCents: -1,
        purchaseCostPercent: -1,
      }),
    ).toEqual({ purchaseCostPercent: ['Purchase cost percent must not be negative'] })
    expect(priceErrors({ salesTotalInputCents: -1 })).toEqual({
      salesTotalInputCents: ['Sales total input must not be negative'],
    })
    for (const salesActiveRow of ['MARGIN', 'MARGIN_PERCENT']) {
      expect(
        priceErrors({
          salesActiveRow,
          salesMarginInputCents: -1,
          salesMarginPercent: -1,
          salesTotalInputCents: -1,
        }),
      ).toEqual({})
    }
    expect(priceErrors({ purchaseCostPercent: 10000.001, salesMarginPercent: 10000.001 })).toEqual(
      {},
    )
  })

  test('active percentages allow two decimals within four integer digits', () => {
    const costPercent = { purchaseActiveRow: 'COST_PERCENT' }
    const marginPercent = { salesActiveRow: 'MARGIN_PERCENT' }
    expect(
      priceErrors({
        ...costPercent,
        purchaseCostPercent: 9999.99,
        ...marginPercent,
        salesMarginPercent: -9999.99,
      }),
    ).toEqual({})
    expect(
      priceErrors({
        ...costPercent,
        purchaseCostPercent: 12.34,
        ...marginPercent,
        salesMarginPercent: 0.07,
      }),
    ).toEqual({})
    expect(priceErrors({ ...costPercent, purchaseCostPercent: 12.345 })).toEqual({
      purchaseCostPercent: ['Purchase cost percent must have at most two decimal places'],
    })
    expect(priceErrors({ ...costPercent, purchaseCostPercent: 10000 })).toEqual({
      purchaseCostPercent: ['Purchase cost percent must not exceed 9999.99'],
    })
    expect(priceErrors({ ...marginPercent, salesMarginPercent: 12.345 })).toEqual({
      salesMarginPercent: ['Sales margin percent must have at most two decimal places'],
    })
    expect(priceErrors({ ...marginPercent, salesMarginPercent: -10000 })).toEqual({
      salesMarginPercent: ['Sales margin percent must be between -9999.99 and 9999.99'],
    })
  })

  test('the discount pair: absent is no discount, otherwise checked against its type', () => {
    expect(priceErrors({ discountType: 'PERCENTAGE', discountValue: 20 })).toEqual({})
    expect(priceErrors({ discountType: 'FIXED_AMOUNT', discountValue: 500 })).toEqual({})
    const discountErrors: [Record<string, unknown>, Record<string, string[]>][] = [
      [{ discountType: 'PERCENTAGE' }, { discountValue: ['Discount value is required'] }],
      [{ discountValue: 20 }, { discountType: ['Discount type is required'] }],
      [
        { discountType: 'percentage', discountValue: 20 },
        { discountType: ['Discount type must be PERCENTAGE or FIXED_AMOUNT'] },
      ],
      [
        { discountType: 'PERCENTAGE', discountValue: 0 },
        { discountValue: ['Discount value must be positive'] },
      ],
      [
        { discountType: 'PERCENTAGE', discountValue: 100.01 },
        { discountValue: ['Discount value must be at most 100 for a percentage discount'] },
      ],
      [
        { discountType: 'PERCENTAGE', discountValue: 12.345 },
        { discountValue: ['Discount value must have at most two decimal places'] },
      ],
      [
        { discountType: 'FIXED_AMOUNT', discountValue: 500.5 },
        { discountValue: ['Discount value must be whole cents for a fixed amount discount'] },
      ],
    ]
    for (const [input, errors] of discountErrors) expect(priceErrors(input)).toEqual(errors)
  })

  test('a wrong JSON type answers under its field, an unknown property under its name', () => {
    expect(
      Object.keys(
        priceErrors({ purchaseCalculationMode: 'INVALID', purchaseCostPercent: '12.34' }),
      ),
    ).toEqual(['purchaseCalculationMode', 'purchaseCostPercent'])
    expect(priceErrors({ priceId: 7 })).toEqual({ priceId: ['Unknown property'] })
  })
})

describe('vatInput', () => {
  test('valid input is trimmed, a blank description becomes null, isDefault defaults to false', () => {
    expect(
      vatInput.parse({
        name: ' Standard ',
        percent: 19,
        description: ' German rate ',
        isDefault: true,
      }),
    ).toEqual({
      name: 'Standard',
      percent: 19,
      description: 'German rate',
      isDefault: true,
    })
    expect(vatInput.parse({ name: 'Reduced', percent: 7, description: '   ' })).toEqual({
      name: 'Reduced',
      percent: 7,
      description: null,
      isDefault: false,
    })
  })

  test('invalid input reports every field with the documented message', () => {
    expect(errorsOf(vatInput, {})).toEqual({
      name: ['Name is required'],
      percent: ['Percent is required'],
    })
    expect(errorsOf(vatInput, { name: '   ', percent: 101 })).toEqual({
      name: ['Name is required'],
      percent: ['Percent must be between 0 and 100'],
    })
    expect(errorsOf(vatInput, { name: 'A'.repeat(256), percent: -1 })).toEqual({
      name: ['Name must be at most 255 characters'],
      percent: ['Percent must be between 0 and 100'],
    })
    expect(errorsOf(vatInput, { name: 'A'.repeat(255), percent: 0 })).toEqual({})
    expect(errorsOf(vatInput, { name: 'Zero', percent: 100 })).toEqual({})
  })
})
