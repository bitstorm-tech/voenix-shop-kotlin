import { fetchJson } from '@/lib/api'

export type PriceCalculationMode = 'NET' | 'GROSS'
export type PurchaseActiveRow = 'COST' | 'COST_PERCENT'
export type SalesActiveRow = 'MARGIN' | 'MARGIN_PERCENT' | 'TOTAL'

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
}

export interface AdminPriceDto extends AdminPriceInputDto {
  id: number | null
  purchaseVat: PriceVatDto
  purchasePrice: PriceAmountDto
  purchaseCost: PriceAmountDto
  calculatedPurchaseCostPercent: number
  purchaseTotal: PriceAmountDto
  salesVat: PriceVatDto
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
