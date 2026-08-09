import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  PromotionCodeConflictError,
  PromotionInUseError,
  PromotionLockedError,
  useAdminPromotionsStore,
  type AdminPromotionDto,
  type UpsertAdminPromotionRequest,
} from '@/stores/admin/promotions'
import { ApiError, resetApiClientForTests } from '@/lib/api'

function jsonResponse(body: unknown, init: ResponseInit = {}) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
}

// Copied from the response body in `docs/dev/backend/promotion-package.md`.
const summerPromotion: AdminPromotionDto = {
  id: 42,
  name: 'Summer sale',
  couponCode: 'Summer10',
  discount: { discountType: 'PERCENTAGE', discountValue: 10.0 },
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
        return jsonResponse([summerPromotion])
      }),
    )
    const store = useAdminPromotionsStore()

    await store.fetchPromotions()

    expect(store.promotions).toEqual([summerPromotion])
    expect(store.error).toBeNull()
  })

  it('creates a promotion with antiforgery and syncs it into the list', async () => {
    // Copied from the request body in `docs/dev/backend/promotion-package.md`: flat on the way in.
    const payload: UpsertAdminPromotionRequest = {
      name: 'Summer sale',
      discountType: 'PERCENTAGE',
      discountValue: 10,
      couponCode: 'Summer10',
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
          { message: 'Coupon code is already in use' },
          { status: 409, statusText: 'Conflict' },
        )
      }),
    )
    const store = useAdminPromotionsStore()

    const act = () =>
      store.createPromotion({
        name: 'Summer sale',
        discountType: 'PERCENTAGE',
        discountValue: 10,
        couponCode: 'summer10',
        isActive: true,
      })

    await expect(act).rejects.toThrow(PromotionCodeConflictError)
  })

  // The `PUT` conflict body names both causes at once, so the message can never discriminate. The
  // client decides from the `isLocked` it already read (`docs/dev/backend/promotion-package.md`).
  const updateConflictBody = { message: 'Coupon code is already in use or the promotion is locked' }

  function stubConflict(body: unknown) {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        if (input === '/api/antiforgery/token') {
          return jsonResponse({ requestToken: 'token-1' })
        }

        return jsonResponse(body, { status: 409, statusText: 'Conflict' })
      }),
    )
  }

  const updatePayload: UpsertAdminPromotionRequest = {
    name: 'Summer sale',
    discountType: 'PERCENTAGE',
    discountValue: 10,
    couponCode: 'Winter10',
    isActive: true,
  }

  it('reads an update conflict on an unlocked promotion as a coupon code conflict', async () => {
    stubConflict(updateConflictBody)
    const store = useAdminPromotionsStore()
    store.promotions = [{ ...summerPromotion, isLocked: false }]

    await expect(() => store.updatePromotion(42, updatePayload)).rejects.toThrow(
      PromotionCodeConflictError,
    )
  })

  it('reads an update conflict on a locked promotion as a lock refusal', async () => {
    stubConflict(updateConflictBody)
    const store = useAdminPromotionsStore()
    store.promotions = [{ ...summerPromotion, redemptionCount: 3, isLocked: true }]

    await expect(() => store.updatePromotion(42, updatePayload)).rejects.toThrow(
      PromotionLockedError,
    )
  })

  it('maps a delete conflict to a PromotionInUseError even though its message says nothing', async () => {
    stubConflict({ message: 'Promotion is still in use and cannot be deleted' })
    const store = useAdminPromotionsStore()
    store.promotions = [{ ...summerPromotion, redemptionCount: 3, isLocked: true }]

    await expect(() => store.deletePromotion(42)).rejects.toThrow(PromotionInUseError)
    expect(store.promotions).toHaveLength(1)
  })

  it('updates and removes promotions in local state', async () => {
    const updatedPromotion: AdminPromotionDto = {
      ...summerPromotion,
      name: 'Winter sale',
      couponCode: 'Winter10',
    }
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (input === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'token-1' })
      }

      if (input === '/api/admin/promotions/42' && init?.method === 'PUT') {
        return jsonResponse(updatedPromotion)
      }

      if (input === '/api/admin/promotions/42' && init?.method === 'DELETE') {
        return new Response(null, { status: 204 })
      }

      throw new Error(`Unexpected request ${String(input)}`)
    })
    vi.stubGlobal('fetch', fetchMock)
    const store = useAdminPromotionsStore()
    store.promotions = [summerPromotion]

    await store.updatePromotion(42, {
      name: 'Winter sale',
      discountType: 'PERCENTAGE',
      discountValue: 10,
      couponCode: 'Winter10',
      isActive: true,
    })
    expect(store.promotions).toEqual([updatedPromotion])

    await store.deletePromotion(42)
    expect(store.promotions).toEqual([])
  })

  it('keeps the validation error keys flat while the response nests the discount', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        if (input === '/api/antiforgery/token') {
          return jsonResponse({ requestToken: 'token-1' })
        }

        return jsonResponse(
          {
            message: 'Validation failed',
            errors: { discountValue: ['DiscountValue must be positive'] },
          },
          { status: 400 },
        )
      }),
    )
    const store = useAdminPromotionsStore()

    const error = await store
      .createPromotion({
        name: 'Summer sale',
        discountType: 'PERCENTAGE',
        discountValue: -1,
        couponCode: 'Summer10',
        isActive: true,
      })
      .catch((err: unknown) => err)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).fieldErrors).toEqual({
      discountValue: ['DiscountValue must be positive'],
    })
  })

  it('reports a failed list load through the store error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => jsonResponse({ message: 'Internal server error' }, { status: 500 })),
    )
    const store = useAdminPromotionsStore()

    await store.fetchPromotions()

    expect(store.promotions).toEqual([])
    expect(store.error).toBe('Internal server error')
  })
})
