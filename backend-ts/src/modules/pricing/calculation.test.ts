import { describe, expect, test } from 'bun:test'
import {
  calculatePrice,
  type CalculatedPrice,
  type PriceAmount,
  type PriceInput,
} from '@/modules/pricing/calculation'
import type { Vat } from '@/modules/pricing/repository'

const vat = (id: number, percent: number, isDefault = false): Vat => ({
  id,
  name: `VAT ${String(id)}`,
  percent,
  description: null,
  isDefault,
})
const standardVat = vat(1, 19, true)
const halfVat = vat(2, 50)
const zeroVat = vat(3, 0)

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

const calculate = (overrides: Partial<PriceInput>, onVat: Vat = standardVat) =>
  calculatePrice(null, priceInput(overrides), onVat, onVat)

const amount = (net: number, tax: number, gross: number): PriceAmount => ({ net, tax, gross })

/** The saving and the effective total add up to the regular total in every component. */
const expectComponentwiseIdentity = (price: CalculatedPrice) => {
  expect(
    amount(
      price.salesDiscount.net + price.salesTotal.net,
      price.salesDiscount.tax + price.salesTotal.tax,
      price.salesDiscount.gross + price.salesTotal.gross,
    ),
  ).toEqual(price.regularSalesTotal)
}

describe('the purchase side', () => {
  test('a net purchase price derives tax and gross; a gross one derives net and tax', () => {
    expect(calculate({ purchasePriceInputCents: 1_000 }).purchasePrice).toEqual(
      amount(1_000, 190, 1_190),
    )
    expect(
      calculate({ purchaseCalculationMode: 'GROSS', purchasePriceInputCents: 1_190 }).purchasePrice,
    ).toEqual(amount(1_000, 190, 1_190))
  })

  test('a fixed cost derives its percent and a component-wise total', () => {
    const result = calculate({ purchasePriceInputCents: 1_000, purchaseCostInputCents: 250 })
    expect(result.purchaseCost).toEqual(amount(250, 48, 298))
    expect(result.calculatedPurchaseCostPercent).toBe(25)
    expect(result.purchaseTotal).toEqual(amount(1_250, 238, 1_488))
  })

  test('a cost percent derives the fixed cost', () => {
    const result = calculate({
      purchaseActiveRow: 'COST_PERCENT',
      purchasePriceInputCents: 1_000,
      purchaseCostPercent: 25,
    })
    expect(result.purchaseCost).toEqual(amount(250, 48, 298))
    expect(result.purchaseTotal).toEqual(amount(1_250, 238, 1_488))
  })

  test('gross cost modes use gross inputs', () => {
    for (const result of [
      calculate({
        purchaseCalculationMode: 'GROSS',
        purchasePriceInputCents: 1_190,
        purchaseCostInputCents: 119,
      }),
      calculate({
        purchaseCalculationMode: 'GROSS',
        purchaseActiveRow: 'COST_PERCENT',
        purchasePriceInputCents: 1_190,
        purchaseCostPercent: 10,
      }),
    ]) {
      expect(result.purchaseCost).toEqual(amount(100, 19, 119))
      expect(result.calculatedPurchaseCostPercent).toBe(10)
      expect(result.purchaseTotal).toEqual(amount(1_100, 209, 1_309))
    }
  })
})

describe('the sales side', () => {
  test('a fixed net margin derives total and percent', () => {
    const result = calculate({
      purchasePriceInputCents: 1_000,
      salesCalculationMode: 'NET',
      salesActiveRow: 'MARGIN',
      salesMarginInputCents: 500,
    })
    expect(result.salesMargin).toEqual(amount(500, 95, 595))
    expect(result.calculatedSalesMarginPercent).toBe(50)
    expect(result.salesTotal).toEqual(amount(1_500, 285, 1_785))
  })

  test('a net margin percent derives margin and total', () => {
    const result = calculate({
      purchasePriceInputCents: 1_000,
      salesCalculationMode: 'NET',
      salesActiveRow: 'MARGIN_PERCENT',
      salesMarginPercent: 50,
    })
    expect(result.salesMargin).toEqual(amount(500, 95, 595))
    expect(result.salesTotal).toEqual(amount(1_500, 285, 1_785))
  })

  test('gross margin modes use the gross purchase total', () => {
    for (const result of [
      calculate({
        purchasePriceInputCents: 1_000,
        salesActiveRow: 'MARGIN',
        salesMarginInputCents: 119,
      }),
      calculate({
        purchasePriceInputCents: 1_000,
        salesActiveRow: 'MARGIN_PERCENT',
        salesMarginPercent: 10,
      }),
    ]) {
      expect(result.salesMargin).toEqual(amount(100, 19, 119))
      expect(result.calculatedSalesMarginPercent).toBe(10)
      expect(result.salesTotal).toEqual(amount(1_100, 209, 1_309))
    }
  })

  test('a net total derives its margin from the net purchase total', () => {
    const result = calculate({
      purchasePriceInputCents: 1_000,
      salesCalculationMode: 'NET',
      salesTotalInputCents: 1_500,
    })
    expect(result.salesMargin).toEqual(amount(500, 95, 595))
    expect(result.calculatedSalesMarginPercent).toBe(50)
    expect(result.salesTotal).toEqual(amount(1_500, 285, 1_785))
  })

  test('a total below the purchase total derives a negative margin', () => {
    const result = calculate({ purchasePriceInputCents: 1_000, salesTotalInputCents: 1_000 })
    expect(result.salesMargin).toEqual(amount(-160, -30, -190))
    expect(result.calculatedSalesMarginPercent).toBe(-15.97)
    expect(result.salesTotal).toEqual(amount(840, 160, 1_000))
  })

  test('a negative margin is accepted while the total stays non-negative', () => {
    const result = calculate({
      purchasePriceInputCents: 1_000,
      salesCalculationMode: 'NET',
      salesActiveRow: 'MARGIN',
      salesMarginInputCents: -100,
    })
    expect(result.salesMargin).toEqual(amount(-100, -19, -119))
    expect(result.salesTotal).toEqual(amount(900, 171, 1_071))
  })
})

describe('rounding and bounds', () => {
  test('zero percentage bases answer zero', () => {
    const result = calculate({ salesTotalInputCents: 1_000 })
    expect(result.calculatedPurchaseCostPercent).toBe(0)
    expect(result.calculatedSalesMarginPercent).toBe(0)
  })

  test('cent and percent midpoints round away from zero', () => {
    expect(calculate({ purchasePriceInputCents: 1 }, halfVat).purchasePrice).toEqual(
      amount(1, 1, 2),
    )
    const percents = calculate(
      {
        purchasePriceInputCents: 32,
        purchaseCostInputCents: 1,
        salesCalculationMode: 'NET',
        salesTotalInputCents: 31,
      },
      zeroVat,
    )
    expect(percents.calculatedPurchaseCostPercent).toBe(3.13)
    expect(percents.calculatedSalesMarginPercent).toBe(-6.06)
    const negativeMargin = {
      salesCalculationMode: 'NET',
      salesActiveRow: 'MARGIN',
      salesMarginInputCents: -1,
    } as const
    expect(
      calculate({ purchasePriceInputCents: 1, ...negativeMargin }, halfVat).salesMargin,
    ).toEqual(amount(-1, -1, -2))
    expect(
      calculate({ purchasePriceInputCents: 32, ...negativeMargin }, zeroVat)
        .calculatedSalesMarginPercent,
    ).toBe(-3.13)
  })

  test('cent arithmetic beyond the 32-bit range throws instead of answering', () => {
    expect(() =>
      calculatePrice(
        null,
        priceInput({ purchasePriceInputCents: 2_147_483_647 }),
        vat(4, 100),
        standardVat,
      ),
    ).toThrow(RangeError)
  })
})

describe('the discount', () => {
  test('without a discount the effective values are the regular ones', () => {
    const result = calculate({ purchasePriceInputCents: 1_000, salesTotalInputCents: 1_990 })
    expect(result.discount).toBeNull()
    expect(result.salesDiscount).toEqual(amount(0, 0, 0))
    expect(result.salesTotal).toEqual(result.regularSalesTotal)
    expect(result.salesMargin).toEqual(result.regularSalesMargin)
    expect(result.calculatedSalesMarginPercent).toBe(result.calculatedRegularSalesMarginPercent)
  })

  test('a percentage reduces a gross total and its margin', () => {
    const result = calculate({
      purchasePriceInputCents: 1_000,
      salesTotalInputCents: 1_990,
      discountType: 'PERCENTAGE',
      discountValue: 20,
    })
    expect(result.discount).toEqual({ discountType: 'PERCENTAGE', discountValue: 20 })
    expect(result.regularSalesTotal).toEqual(amount(1_672, 318, 1_990))
    expect(result.salesTotal).toEqual(amount(1_338, 254, 1_592))
    expect(result.salesDiscount).toEqual(amount(334, 64, 398))
    expect(result.regularSalesMargin).toEqual(amount(672, 128, 800))
    expect(result.salesMargin).toEqual(amount(338, 64, 402))
    expect(result.calculatedRegularSalesMarginPercent).toBe(67.23)
    expect(result.calculatedSalesMarginPercent).toBe(33.78)
    expectComponentwiseIdentity(result)
  })

  test('a fixed amount reduces a net total', () => {
    const result = calculate({
      purchasePriceInputCents: 1_000,
      salesCalculationMode: 'NET',
      salesTotalInputCents: 1_500,
      discountType: 'FIXED_AMOUNT',
      discountValue: 285,
    })
    expect(result.regularSalesTotal).toEqual(amount(1_500, 285, 1_785))
    expect(result.salesTotal).toEqual(amount(1_261, 239, 1_500))
    expect(result.salesDiscount).toEqual(amount(239, 46, 285))
    expect(result.salesMargin).toEqual(amount(261, 50, 311))
    expectComponentwiseIdentity(result)
  })

  test('a half-cent saving rounds away from zero', () => {
    const result = calculate({
      salesTotalInputCents: 1_000,
      discountType: 'PERCENTAGE',
      discountValue: 0.05,
    })
    expect(result.salesDiscount.gross).toBe(1)
    expect(result.salesTotal.gross).toBe(999)
  })

  test('a hundred percent makes the price free', () => {
    const result = calculate({
      salesTotalInputCents: 1_990,
      discountType: 'PERCENTAGE',
      discountValue: 100,
    })
    expect(result.regularSalesTotal).toEqual(amount(1_672, 318, 1_990))
    expect(result.salesTotal).toEqual(amount(0, 0, 0))
    expectComponentwiseIdentity(result)
  })

  test('a fixed amount larger than the gross is capped at it', () => {
    const result = calculate({
      salesTotalInputCents: 1_990,
      discountType: 'FIXED_AMOUNT',
      discountValue: 2_147_483_648,
    })
    expect(result.salesTotal).toEqual(amount(0, 0, 0))
    expect(result.salesDiscount).toEqual(result.regularSalesTotal)
    expectComponentwiseIdentity(result)
  })
})
