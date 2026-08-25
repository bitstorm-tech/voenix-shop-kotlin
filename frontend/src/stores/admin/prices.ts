import { fetchJson } from '@/lib/api'

export type PriceCalculationMode = 'NET' | 'GROSS'
export type PurchaseActiveRow = 'COST' | 'COST_PERCENT'
export type SalesActiveRow = 'MARGIN' | 'MARGIN_PERCENT' | 'TOTAL'
export type PriceDiscountType = 'PERCENTAGE' | 'FIXED_AMOUNT'

/**
 * The discount of a calculated price: a percentage of the regular gross sales total, or a fixed
 * number of whole cents. The request carries the same pair flat as `discountType` and
 * `discountValue`, where both `null` means "no discount".
 */
export interface PriceDiscountDto {
  discountType: PriceDiscountType
  discountValue: number
}

export interface PriceAmountDto {
  net: number
  tax: number
  gross: number
}

export interface PriceVatDto {
  id: number
  name: string
  percent: number
}

export interface AdminPriceInputDto {
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

/**
 * A calculated price. On the sales side an unqualified name is the **effective** value, what the
 * customer pays, and a `regular*` name is the value before the discount. The response nests the
 * discount in `discount`, while the request carries it flat, so the two flat request fields are
 * omitted here.
 */
export interface AdminPriceDto extends Omit<AdminPriceInputDto, 'discountType' | 'discountValue'> {
  id: number | null
  purchaseVat: PriceVatDto
  purchasePrice: PriceAmountDto
  purchaseCost: PriceAmountDto
  calculatedPurchaseCostPercent: number
  purchaseTotal: PriceAmountDto
  salesVat: PriceVatDto
  regularSalesMargin: PriceAmountDto
  calculatedRegularSalesMarginPercent: number
  regularSalesTotal: PriceAmountDto
  discount: PriceDiscountDto | null
  salesDiscount: PriceAmountDto
  salesMargin: PriceAmountDto
  calculatedSalesMarginPercent: number
  salesTotal: PriceAmountDto
}

export async function fetchDefaultPrice(): Promise<AdminPriceDto> {
  return fetchJson<AdminPriceDto>('/api/admin/prices/default')
}

export async function calculatePrice(payload: AdminPriceInputDto): Promise<AdminPriceDto> {
  return fetchJson<AdminPriceDto>('/api/admin/prices/calculate', {
    method: 'POST',
    body: payload,
  })
}
