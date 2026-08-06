import type {
  AdminPriceDto,
  AdminPriceInputDto,
  PriceAmountDto,
  PriceCalculationMode,
  PurchaseActiveRow,
  SalesActiveRow,
} from '@/stores/admin/prices'

export interface AdminPriceFormState {
  purchaseVatId: number | null
  purchaseCalculationMode: PriceCalculationMode
  purchaseActiveRow: PurchaseActiveRow
  purchasePriceInputCents: number
  purchaseCostInputCents: number
  purchaseCostPercent: number
  salesVatId: number | null
  salesCalculationMode: PriceCalculationMode
  salesActiveRow: SalesActiveRow
  salesMarginInputCents: number
  salesMarginPercent: number
  salesTotalInputCents: number
}

export interface AdminPriceFieldTexts {
  purchasePrice: string
  purchaseCost: string
  purchaseCostPercent: string
  salesMargin: string
  salesMarginPercent: string
  salesTotal: string
}

const moneyFormatter = new Intl.NumberFormat('de-DE', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

const percentFormatter = new Intl.NumberFormat('de-DE', {
  minimumFractionDigits: 0,
  maximumFractionDigits: 2,
})

export function parseGermanDecimal(value: string): number | null {
  const compactValue = value.trim().replace(/\s/g, '')

  if (compactValue === '') {
    return 0
  }

  const lastComma = compactValue.lastIndexOf(',')
  const lastDot = compactValue.lastIndexOf('.')
  const decimalSeparator = lastComma > lastDot ? ',' : lastDot > -1 ? '.' : null
  const normalized =
    decimalSeparator === ','
      ? compactValue.replace(/\./g, '').replace(',', '.')
      : decimalSeparator === '.'
        ? compactValue.replace(/,/g, '')
        : compactValue

  if (!/^-?\d+(\.\d+)?$/.test(normalized)) {
    return null
  }

  const parsedValue = Number(normalized)
  return Number.isFinite(parsedValue) ? parsedValue : null
}

export function parseGermanMoneyToCents(value: string): number | null {
  const parsedValue = parseGermanDecimal(value)
  return parsedValue === null ? null : Math.round(parsedValue * 100)
}

export function parseGermanPercent(value: string): number | null {
  return parseGermanDecimal(value)
}

/**
 * The backend accepts an active percentage input with at most two relevant decimal places and at
 * most four integer digits: `0` through `9999.99` for the purchase cost percentage and
 * `-9999.99` through `9999.99` for the sales margin percentage, where a negative margin can be
 * valid (`docs/dev/backend/pricing-package.md`).
 */
export const MAX_PERCENT_VALUE = 9999.99
export const PERCENT_DECIMAL_PLACES = 2

export type PercentValidationError = 'scale' | 'range'

export function validatePercentValue(
  value: number,
  { allowNegative }: { allowNegative: boolean },
): PercentValidationError | null {
  if (countDecimalPlaces(value) > PERCENT_DECIMAL_PLACES) {
    return 'scale'
  }

  const minimum = allowNegative ? -MAX_PERCENT_VALUE : 0
  return value < minimum || value > MAX_PERCENT_VALUE ? 'range' : null
}

/** Trailing zeros do not add precision, so `12,340` counts as two decimal places. */
function countDecimalPlaces(value: number) {
  const text = String(value)
  if (text.includes('e') || text.includes('E')) {
    return Number.POSITIVE_INFINITY
  }

  return text.split('.')[1]?.length ?? 0
}

export function formatCents(value: number) {
  return moneyFormatter.format(value / 100)
}

export function formatPercent(value: number) {
  return percentFormatter.format(value)
}

export function getModeAmount(amount: PriceAmountDto, mode: PriceCalculationMode) {
  return mode === 'NET' ? amount.net : amount.gross
}

export function createPriceFormFromDto(price: AdminPriceDto): AdminPriceFormState {
  return {
    purchaseVatId: price.purchaseVatId,
    purchaseCalculationMode: price.purchaseCalculationMode,
    purchaseActiveRow: price.purchaseActiveRow,
    purchasePriceInputCents: price.purchasePriceInputCents,
    purchaseCostInputCents: price.purchaseCostInputCents,
    purchaseCostPercent: price.purchaseCostPercent,
    salesVatId: price.salesVatId,
    salesCalculationMode: price.salesCalculationMode,
    salesActiveRow: price.salesActiveRow,
    salesMarginInputCents: price.salesMarginInputCents,
    salesMarginPercent: price.salesMarginPercent,
    salesTotalInputCents: price.salesTotalInputCents,
  }
}

export function createEmptyPriceForm(): AdminPriceFormState {
  return {
    purchaseVatId: null,
    purchaseCalculationMode: 'NET',
    purchaseActiveRow: 'COST',
    purchasePriceInputCents: 0,
    purchaseCostInputCents: 0,
    purchaseCostPercent: 0,
    salesVatId: null,
    salesCalculationMode: 'GROSS',
    salesActiveRow: 'TOTAL',
    salesMarginInputCents: 0,
    salesMarginPercent: 0,
    salesTotalInputCents: 0,
  }
}

export function createFieldTextsFromForm(form: AdminPriceFormState): AdminPriceFieldTexts {
  return {
    purchasePrice: formatCents(form.purchasePriceInputCents),
    purchaseCost: formatCents(form.purchaseCostInputCents),
    purchaseCostPercent: formatPercent(form.purchaseCostPercent),
    salesMargin: formatCents(form.salesMarginInputCents),
    salesMarginPercent: formatPercent(form.salesMarginPercent),
    salesTotal: formatCents(form.salesTotalInputCents),
  }
}

export function buildPriceInputFromForm(form: AdminPriceFormState): AdminPriceInputDto | null {
  if (form.purchaseVatId === null || form.salesVatId === null) {
    return null
  }

  return {
    purchaseVatId: form.purchaseVatId,
    purchaseCalculationMode: form.purchaseCalculationMode,
    purchaseActiveRow: form.purchaseActiveRow,
    purchasePriceInputCents: form.purchasePriceInputCents,
    purchaseCostInputCents: form.purchaseCostInputCents,
    purchaseCostPercent: form.purchaseCostPercent,
    salesVatId: form.salesVatId,
    salesCalculationMode: form.salesCalculationMode,
    salesActiveRow: form.salesActiveRow,
    salesMarginInputCents: form.salesMarginInputCents,
    salesMarginPercent: form.salesMarginPercent,
    salesTotalInputCents: form.salesTotalInputCents,
  }
}

export function buildPriceInputFromDto(price: AdminPriceDto): AdminPriceInputDto {
  return buildPriceInputFromForm(createPriceFormFromDto(price))!
}
