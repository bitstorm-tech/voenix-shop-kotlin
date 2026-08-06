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

export async function readErrorMessage(response: Response) {
  const errorData = await response.json().catch(() => null)
  return errorData?.detail || errorData?.message || `HTTP error ${response.status}`
}

export async function fetchDefaultPrice(): Promise<AdminPriceDto> {
  const response = await fetch('/api/admin/prices/default')

  if (!response.ok) {
    throw new Error(await readErrorMessage(response))
  }

  return response.json()
}

export async function calculatePrice(payload: AdminPriceInputDto): Promise<AdminPriceDto> {
  try {
    return await fetchJson<AdminPriceDto>('/api/admin/prices/calculate', {
      method: 'POST',
      body: payload,
    })
  } catch (err) {
    throw err instanceof Error ? err : new Error('Unknown error')
  }
}
