import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MagicCoinsView from '@/views/admin/MagicCoinsView.vue'
import { resetApiClientForTests } from '@/lib/api'

const toast = vi.fn()

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast }),
}))

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const samplePendingRequest = {
  id: 42,
  userId: 14,
  userEmail: 'customer@example.com',
  userName: 'Anna Muster',
  accountCreatedAt: '2026-09-01T10:00:00.000Z',
  currentBalance: 0,
  coinsFirstSpentAt: '2026-11-27T10:00:00.000Z',
  coinsLastUpdatedAt: '2026-11-27T10:15:00.000Z',
  paidOrderCount: 2,
  activeCartItemCount: 1,
  totalRequestCount: 1,
  totalApprovedCoins: 0,
  status: 'PENDING' as const,
  grantedCoins: null,
  blockedUser: false,
  decidedByEmail: null,
  createdAt: '2026-11-27T10:16:00.000Z',
  decidedAt: null,
  isCurrentlyBlocked: false,
}

const dialogStubs = {
  Dialog: {
    props: ['open'],
    emits: ['update:open'],
    template: '<div v-if="open"><slot /></div>',
  },
  DialogContent: { template: '<div v-bind="$attrs"><slot /></div>' },
  DialogHeader: { template: '<div><slot /></div>' },
  DialogTitle: { template: '<h3><slot /></h3>' },
  DialogDescription: { template: '<p><slot /></p>' },
  DialogFooter: { template: '<div><slot /></div>' },
}

describe('Admin MagicCoinsView', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    toast.mockClear()
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('displays pending requests with verification signals and approves with configurable coins', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-1' })
      }
      if (path === '/api/admin/magic-coins/requests?status=PENDING') {
        return jsonResponse([samplePendingRequest])
      }
      if (path === '/api/admin/magic-coins/requests/42/approve' && init?.method === 'POST') {
        expect(JSON.parse(String(init.body))).toEqual({ amount: 15 })
        return jsonResponse({
          id: 42,
          status: 'APPROVED',
          grantedCoins: 15,
          newBalance: 15,
          decidedAt: '2026-11-27T10:20:00.000Z',
        })
      }
      throw new Error(`Unexpected request: ${path}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(MagicCoinsView, { global: { stubs: dialogStubs } })
    await flushPromises()

    const row = wrapper.get('[data-testid="magic-coin-request-row"]')
    expect(row.text()).toContain('customer@example.com')
    expect(row.text()).toContain('Anna Muster')
    expect(row.text()).toContain('2 paid orders')
    expect(row.text()).toContain('1 in cart')
    expect(row.text()).toContain('15 min')

    await wrapper.get('[data-testid="approve-42"]').trigger('click')
    await wrapper.get('#approve-coin-amount').setValue('15')
    await wrapper.get('[data-testid="approve-request-dialog"] form').trigger('submit')
    await flushPromises()

    expect(wrapper.findAll('[data-testid="magic-coin-request-row"]')).toHaveLength(0)
    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Magic Coins granted', variant: 'success' }),
    )
  })

  it('supports rejecting with a permanent block from the reject dialog', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-1' })
      }
      if (path === '/api/admin/magic-coins/requests?status=PENDING') {
        return jsonResponse([samplePendingRequest])
      }
      if (path === '/api/admin/magic-coins/requests/42/reject' && init?.method === 'POST') {
        expect(JSON.parse(String(init.body))).toEqual({ blockUser: true })
        return jsonResponse({
          id: 42,
          status: 'REJECTED',
          blockedUser: true,
          decidedAt: '2026-11-27T10:20:00.000Z',
        })
      }
      throw new Error(`Unexpected request: ${path}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(MagicCoinsView, { global: { stubs: dialogStubs } })
    await flushPromises()

    await wrapper.get('[data-testid="reject-42"]').trigger('click')
    await wrapper.get('[data-testid="confirm-block-reject-button"]').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('[data-testid="magic-coin-request-row"]')).toHaveLength(0)
    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({
        title: 'Request rejected and account blocked',
        variant: 'success',
      }),
    )
  })

  it('grants coins manually by customer email', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      if (path === '/api/antiforgery/token') {
        return jsonResponse({ requestToken: 'csrf-1' })
      }
      if (path === '/api/admin/magic-coins/requests?status=PENDING') {
        return jsonResponse([])
      }
      if (path === '/api/admin/magic-coins/grants' && init?.method === 'POST') {
        expect(JSON.parse(String(init.body))).toEqual({
          email: 'vip@example.com',
          amount: 20,
        })
        return jsonResponse({
          userId: 9,
          userEmail: 'vip@example.com',
          grantedCoins: 20,
          newBalance: 30,
        })
      }
      throw new Error(`Unexpected request: ${path}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(MagicCoinsView, { global: { stubs: dialogStubs } })
    await flushPromises()

    await wrapper.get('#manual-grant-email').setValue('vip@example.com')
    await wrapper.get('#manual-grant-amount').setValue('20')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Magic Coins granted', variant: 'success' }),
    )
  })
})
