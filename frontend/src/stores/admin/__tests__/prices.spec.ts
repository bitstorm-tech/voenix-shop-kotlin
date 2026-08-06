import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  calculatePrice,
  fetchDefaultPrice,
  type AdminPriceDto,
  type AdminPriceInputDto,
} from '@/stores/admin/prices'

const standardVat = { id: 1, name: 'Standard', percent: 19 }

const price: AdminPriceDto = {
  id: null,
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
  salesTotalInputCents: 1190,
  purchaseVat: standardVat,
  purchasePrice: { net: 0, tax: 0, gross: 0 },
  purchaseCost: { net: 0, tax: 0, gross: 0 },
  calculatedPurchaseCostPercent: 0,
  purchaseTotal: { net: 0, tax: 0, gross: 0 },
  salesVat: standardVat,
  salesMargin: { net: 0, tax: 0, gross: 0 },
  calculatedSalesMarginPercent: 0,
  salesTotal: { net: 1000, tax: 190, gross: 1190 },
}

const payload: AdminPriceInputDto = {
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
  salesTotalInputCents: 1190,
}

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

describe('admin prices API', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('fetches the default price from the admin API', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(price))
    vi.stubGlobal('fetch', fetchMock)

    const result = await fetchDefaultPrice()

    expect(fetchMock).toHaveBeenCalledWith('/api/admin/prices/default')
    expect(result.salesTotal.gross).toBe(1190)
  })

  it('sends antiforgery token and JSON payload when calculating a price', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse(price)
    })
    vi.stubGlobal('fetch', fetchMock)

    await calculatePrice(payload)

    expect(fetchMock).toHaveBeenCalledWith('/api/antiforgery/token')
    expect(fetchMock).toHaveBeenCalledWith('/api/admin/prices/calculate', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': 'token-1',
      },
      body: JSON.stringify(payload),
    })
  })

  it('throws problem detail messages for failed calculation requests', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      return jsonResponse({ detail: 'Sales total must not be negative' }, { status: 400 })
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(calculatePrice(payload)).rejects.toThrow('Sales total must not be negative')
  })
})
