import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  PromotionCodeConflictError,
  useAdminPromotionsStore,
  type AdminPromotionDto,
  type UpsertAdminPromotionRequest,
} from '@/stores/admin/promotions'
import { resetApiClientForTests } from '@/lib/api'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

const summerPromotion: AdminPromotionDto = {
  id: 7,
  name: 'Summer',
  discountType: 'PERCENTAGE',
  discountValue: 10,
  couponCode: 'SUMMER10',
  startsAt: null,
  endsAt: null,
  usageLimitTotal: 100,
  usageLimitPerUser: 1,
  isActive: true,
  redemptionCount: 0,
  isLocked: false,
}

describe('admin promotions store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    resetApiClientForTests()
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('loads promotions from the admin API', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        expect(input).toBe('/api/admin/promotions')
        return jsonResponse({ items: [summerPromotion] })
      }),
    )
    const store = useAdminPromotionsStore()

    await store.fetchPromotions()

    expect(store.promotions).toEqual([summerPromotion])
    expect(store.error).toBeNull()
  })

  it('creates a promotion with antiforgery and syncs it into the list', async () => {
    const payload: UpsertAdminPromotionRequest = {
      name: 'Summer',
      discountType: 'PERCENTAGE',
      discountValue: 10,
      couponCode: 'SUMMER10',
      startsAt: null,
      endsAt: null,
      usageLimitTotal: 100,
      usageLimitPerUser: 1,
      isActive: true,
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      expect(input).toBe('/api/admin/promotions')
      expect(init?.method).toBe('POST')
      expect(init?.headers).toEqual({
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': 'token-1',
      })
      expect(init?.body).toBe(JSON.stringify(payload))
      return jsonResponse(summerPromotion)
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromotionsStore()

    const result = await store.createPromotion(payload)

    expect(result).toEqual(summerPromotion)
    expect(store.promotions).toEqual([summerPromotion])
  })

  it('maps duplicate coupon codes to a PromotionCodeConflictError', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        if (input === '/api/antiforgery/token') {
          return jsonResponse({ requestToken: 'token-1' })
        }

        return jsonResponse(
          { detail: 'Promotion code already exists' },
          { status: 409, statusText: 'Conflict' },
        )
      }),
    )
    const store = useAdminPromotionsStore()

    const act = () =>
      store.createPromotion({
        name: 'Summer',
        discountType: 'PERCENTAGE',
        discountValue: 10,
        couponCode: 'summer10',
        isActive: true,
      })

    await expect(act).rejects.toThrow(PromotionCodeConflictError)
  })

  it('updates and removes promotions in local state', async () => {
    const updatedPromotion: AdminPromotionDto = {
      ...summerPromotion,
      name: 'Winter',
      couponCode: 'WINTER10',
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      if (input === '/api/admin/promotions/7' && init?.method === 'PUT') {
        return jsonResponse(updatedPromotion)
      }

      if (input === '/api/admin/promotions/7' && init?.method === 'DELETE') {
        return new Response(null, { status: 204 })
      }

      throw new Error(`Unexpected request ${String(input)}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromotionsStore()
    store.promotions = [summerPromotion]

    await store.updatePromotion(7, {
      name: 'Winter',
      discountType: 'PERCENTAGE',
      discountValue: 10,
      couponCode: 'WINTER10',
      isActive: true,
    })
    expect(store.promotions).toEqual([updatedPromotion])

    await store.deletePromotion(7)
    expect(store.promotions).toEqual([])
  })
})
