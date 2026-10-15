import { percentOfCents, roundHalfUp } from '@/platform/money'
import type { Vat } from './repository'

export const PRICE_CALCULATION_MODES = ['NET', 'GROSS'] as const
export const PURCHASE_ACTIVE_ROWS = ['COST', 'COST_PERCENT'] as const
export const SALES_ACTIVE_ROWS = ['MARGIN', 'MARGIN_PERCENT', 'TOTAL'] as const
export const PRICE_DISCOUNT_TYPES = ['PERCENTAGE', 'FIXED_AMOUNT'] as const

/** `NET`: the submitted cents exclude VAT; `GROSS`: they include it. */
export type PriceCalculationMode = (typeof PRICE_CALCULATION_MODES)[number]
/** Which purchase input drives the cost: fixed cents or a percentage of the purchase price. */
export type PurchaseActiveRow = (typeof PURCHASE_ACTIVE_ROWS)[number]
/** Which sales input drives the total: a fixed margin, a margin percentage, or the total itself. */
export type SalesActiveRow = (typeof SALES_ACTIVE_ROWS)[number]
export type PriceDiscountType = (typeof PRICE_DISCOUNT_TYPES)[number]

/**
 * Everything a price is calculated from, and exactly what the `prices` table stores. Cents are
 * integers; percentages have at most two decimals. The discount is the pair `discountType` and
 * `discountValue`: both `null` means no discount.
 */
export interface PriceInput {
  purchaseVatId: number
  purchaseCalculationMode: PriceCalculationMode
  purchaseActiveRow: PurchaseActiveRow
  purchasePriceInputCents: number
  purchaseCostInputCents: number
  purchaseCostPercent: number
  salesVatId: number
  salesCalculationMode: PriceCalculationMode
  salesActiveRow: SalesActiveRow
  salesMarginInputCents: number
  salesMarginPercent: number
  salesTotalInputCents: number
  discountType: PriceDiscountType | null
  discountValue: number | null
}

/** A monetary amount in cents; `net + tax == gross` always holds. */
export interface PriceAmount {
  net: number
  tax: number
  gross: number
}

/** The reduction configured on a price: a percentage of the regular gross, or fixed cents. */
export interface PriceDiscount {
  discountType: PriceDiscountType
  discountValue: number
}

/**
 * One price with its calculation inputs and every value derived from them. `id` is `null` until
 * the price is stored. On the sales side an unqualified name is the **effective** value, what the
 * customer pays, and a `regular*` name is the value before the discount.
 */
export interface CalculatedPrice {
  id: number | null
  purchaseVatId: number
  purchaseCalculationMode: PriceCalculationMode
  purchaseActiveRow: PurchaseActiveRow
  purchasePriceInputCents: number
  purchaseCostInputCents: number
  purchaseCostPercent: number
  salesVatId: number
  salesCalculationMode: PriceCalculationMode
  salesActiveRow: SalesActiveRow
  salesMarginInputCents: number
  salesMarginPercent: number
  salesTotalInputCents: number
  purchaseVat: Vat
  purchasePrice: PriceAmount
  purchaseCost: PriceAmount
  calculatedPurchaseCostPercent: number
  purchaseTotal: PriceAmount
  salesVat: Vat
  regularSalesMargin: PriceAmount
  calculatedRegularSalesMarginPercent: number
  regularSalesTotal: PriceAmount
  discount: PriceDiscount | null
  salesDiscount: PriceAmount
  salesMargin: PriceAmount
  calculatedSalesMarginPercent: number
  salesTotal: PriceAmount
}

interface SalesCalculation {
  margin: PriceAmount
  total: PriceAmount
  marginPercent: number
}

/**
 * The pure calculation: purchase price, purchase cost, purchase total, sales, then the discount.
 * Every cent value rounds half up; a result outside the 32-bit cent range of the `*_cents` columns
 * throws instead of answering a price no column can hold.
 */
export function calculatePrice(
  id: number | null,
  input: PriceInput,
  purchaseVat: Vat,
  salesVat: Vat,
): CalculatedPrice {
  const purchaseMode = input.purchaseCalculationMode
  const purchasePrice = fromInput(input.purchasePriceInputCents, purchaseMode, purchaseVat.percent)
  const purchaseCost =
    input.purchaseActiveRow === 'COST'
      ? fromInput(input.purchaseCostInputCents, purchaseMode, purchaseVat.percent)
      : fromInput(
          cents(percentOfCents(modeAmount(purchasePrice, purchaseMode), input.purchaseCostPercent)),
          purchaseMode,
          purchaseVat.percent,
        )
  const calculatedPurchaseCostPercent =
    input.purchaseActiveRow === 'COST_PERCENT'
      ? input.purchaseCostPercent
      : percentOf(modeAmount(purchaseCost, purchaseMode), modeAmount(purchasePrice, purchaseMode))
  const purchaseTotal = add(purchasePrice, purchaseCost)

  const baseAmount = modeAmount(purchaseTotal, input.salesCalculationMode)
  const sales = calculateSales(input, salesVat.percent, baseAmount)
  const discount =
    input.discountType === null || input.discountValue === null
      ? null
      : { discountType: input.discountType, discountValue: input.discountValue }
  const effective = applyDiscount(sales, discount, input, salesVat.percent, baseAmount)

  return {
    id,
    purchaseVatId: input.purchaseVatId,
    purchaseCalculationMode: input.purchaseCalculationMode,
    purchaseActiveRow: input.purchaseActiveRow,
    purchasePriceInputCents: input.purchasePriceInputCents,
    purchaseCostInputCents: input.purchaseCostInputCents,
    purchaseCostPercent: input.purchaseCostPercent,
    salesVatId: input.salesVatId,
    salesCalculationMode: input.salesCalculationMode,
    salesActiveRow: input.salesActiveRow,
    salesMarginInputCents: input.salesMarginInputCents,
    salesMarginPercent: input.salesMarginPercent,
    salesTotalInputCents: input.salesTotalInputCents,
    purchaseVat,
    purchasePrice,
    purchaseCost,
    calculatedPurchaseCostPercent,
    purchaseTotal,
    salesVat,
    regularSalesMargin: sales.margin,
    calculatedRegularSalesMarginPercent: sales.marginPercent,
    regularSalesTotal: sales.total,
    discount,
    salesDiscount: subtract(sales.total, effective.total),
    salesMargin: effective.margin,
    calculatedSalesMarginPercent: effective.marginPercent,
    salesTotal: effective.total,
  }
}

function calculateSales(
  input: PriceInput,
  vatPercent: number,
  baseAmount: number,
): SalesCalculation {
  const mode = input.salesCalculationMode
  switch (input.salesActiveRow) {
    case 'MARGIN': {
      const margin = fromInput(input.salesMarginInputCents, mode, vatPercent)
      return {
        margin,
        total: fromInput(cents(baseAmount + modeAmount(margin, mode)), mode, vatPercent),
        marginPercent: percentOf(modeAmount(margin, mode), baseAmount),
      }
    }
    case 'MARGIN_PERCENT': {
      const marginInput = cents(percentOfCents(baseAmount, input.salesMarginPercent))
      return {
        margin: fromInput(marginInput, mode, vatPercent),
        total: fromInput(cents(baseAmount + marginInput), mode, vatPercent),
        marginPercent: input.salesMarginPercent,
      }
    }
    case 'TOTAL':
      return salesFromTotal(
        fromInput(input.salesTotalInputCents, mode, vatPercent),
        mode,
        vatPercent,
        baseAmount,
      )
  }
}

/**
 * Reduces the regular gross by the saving and derives net, tax, and margin from the effective
 * gross with the same arithmetic as every other amount. The saving is capped at the regular gross:
 * a stored fixed amount that a later VAT change made larger than the price sells it for `0`.
 */
function applyDiscount(
  sales: SalesCalculation,
  discount: PriceDiscount | null,
  input: PriceInput,
  vatPercent: number,
  baseAmount: number,
): SalesCalculation {
  if (discount === null) return sales
  const saving =
    discount.discountType === 'PERCENTAGE'
      ? percentOfCents(sales.total.gross, discount.discountValue)
      : Math.min(discount.discountValue, sales.total.gross)
  return salesFromTotal(
    fromInput(cents(sales.total.gross - saving), 'GROSS', vatPercent),
    input.salesCalculationMode,
    vatPercent,
    baseAmount,
  )
}

/** The margin is what is left of a known total above the purchase base, as the `TOTAL` row derives it. */
function salesFromTotal(
  total: PriceAmount,
  mode: PriceCalculationMode,
  vatPercent: number,
  baseAmount: number,
): SalesCalculation {
  const marginInput = cents(modeAmount(total, mode) - baseAmount)
  return {
    margin: fromInput(marginInput, mode, vatPercent),
    total,
    marginPercent: percentOf(marginInput, baseAmount),
  }
}

function fromInput(
  inputCents: number,
  mode: PriceCalculationMode,
  vatPercent: number,
): PriceAmount {
  if (mode === 'NET') {
    const tax = percentOfCents(inputCents, vatPercent)
    return { net: inputCents, tax, gross: cents(inputCents + tax) }
  }
  // gross / (1 + vat / 100), scaled to integers before the one division.
  const net = cents(roundHalfUp((inputCents * 100) / (100 + vatPercent)))
  return { net, tax: cents(inputCents - net), gross: inputCents }
}

const add = (left: PriceAmount, right: PriceAmount): PriceAmount => ({
  net: cents(left.net + right.net),
  tax: cents(left.tax + right.tax),
  gross: cents(left.gross + right.gross),
})

const subtract = (left: PriceAmount, right: PriceAmount): PriceAmount => ({
  net: cents(left.net - right.net),
  tax: cents(left.tax - right.tax),
  gross: cents(left.gross - right.gross),
})

const modeAmount = (amount: PriceAmount, mode: PriceCalculationMode): number =>
  mode === 'NET' ? amount.net : amount.gross

/** `amount` as a percentage of `base`, two decimals, half up; `0` for a zero base. */
function percentOf(amount: number, base: number): number {
  return base === 0 ? 0 : roundHalfUp((amount * 10_000) / base) / 100
}

const MAXIMUM_CENTS = 2_147_483_647
const MINIMUM_CENTS = -2_147_483_648

/** The 32-bit range of the `*_cents` columns, checked where the Kotlin calculator used `Math.addExact`. */
function cents(value: number): number {
  if (value > MAXIMUM_CENTS || value < MINIMUM_CENTS) {
    throw new RangeError('A price amount is outside the 32-bit cent range')
  }
  return value
}
