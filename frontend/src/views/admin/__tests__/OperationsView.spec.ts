import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { OperationsFinding } from '@/stores/admin/operations'
import OperationsView from '../OperationsView.vue'

const operationsState = reactive({
  findings: [] as OperationsFinding[],
  isLoading: false,
  error: null as Error | null,
  fetchFindings: vi.fn(),
  lastScanAt: undefined as string | null | undefined,
  fetchLastScan: vi.fn(),
})

const NOW = new Date('2026-10-16T10:20:00.000Z')

vi.mock('@/stores/admin/operations', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/stores/admin/operations')>()
  return { ...actual, useAdminOperationsStore: () => operationsState }
})

const stalledDelivery: OperationsFinding = {
  kind: 'DELIVERY_STALLED',
  subject: { type: 'DELIVERY', id: 2 },
  orderId: 42,
  since: '2026-10-16T10:00:00.000Z',
  attemptCount: 3,
  lastErrorCode: 'SFTP_CONNECT_FAILED',
  firstSeenAt: '2026-10-16T10:05:00.000Z',
  alertedAt: null,
}

const stuckMail: OperationsFinding = {
  kind: 'EMAIL_STALLED',
  subject: { type: 'EMAIL', id: 5 },
  orderId: null,
  since: '2026-10-16T09:00:00.000Z',
  attemptCount: 0,
  lastErrorCode: null,
  firstSeenAt: '2026-10-16T10:05:00.000Z',
  alertedAt: null,
}

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/operations', component: { template: '<div />' } },
      { path: '/admin/orders/:orderId', name: 'admin-order', component: { template: '<div />' } },
    ],
  })
  await router.push('/admin/operations')
  await router.isReady()
  const wrapper = mount(OperationsView, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  operationsState.findings = [stalledDelivery, stuckMail]
  operationsState.isLoading = false
  operationsState.error = null
  operationsState.fetchFindings.mockReset().mockResolvedValue(undefined)
  operationsState.lastScanAt = '2026-10-16T10:17:00.000Z'
  operationsState.fetchLastScan.mockReset().mockResolvedValue(undefined)
  vi.spyOn(Date, 'now').mockReturnValue(NOW.getTime())
})

describe('OperationsView', () => {
  it('loads the open findings and shows every column of a row', async () => {
    const wrapper = await mountView()

    expect(operationsState.fetchFindings).toHaveBeenCalledWith(false)
    const headers = wrapper.findAll('th').map((header) => header.text())
    expect(headers).toEqual([
      'Kind',
      'Subject',
      'Order',
      'Since',
      'Attempts',
      'Error code',
      'Alerted',
    ])
    const [delivery, mail] = wrapper.findAll('[data-testid="finding-row"]')
    expect(delivery?.text()).toContain('Document not delivered')
    expect(delivery?.text()).toContain('DELIVERY_STALLED')
    expect(delivery?.text()).toContain('DELIVERY')
    expect(delivery?.text()).toContain('#2')
    expect(delivery?.text()).toContain('SFTP_CONNECT_FAILED')
    expect(delivery?.text()).toContain('No')
    expect(mail?.find('[data-testid="finding-order-link"]').exists()).toBe(false)
  })

  it('links the order of a finding to its timeline', async () => {
    const wrapper = await mountView()

    const link = wrapper.find('[data-testid="finding-order-link"]')
    expect(link.text()).toBe('ORD-42')
    expect(link.attributes('href')).toBe('/admin/orders/42')
  })

  it('switches to the resolved list, which shows when a finding resolved', async () => {
    const wrapper = await mountView()
    operationsState.fetchFindings.mockClear()

    const resolvedTab = wrapper
      .findAll('button')
      .find((button) => button.text().includes('Resolved in the last 7 days'))!
    await resolvedTab.trigger('mousedown', { button: 0 })
    await resolvedTab.trigger('click')
    await flushPromises()

    expect(operationsState.fetchFindings).toHaveBeenCalledWith(true)
    expect(wrapper.findAll('th').map((header) => header.text())).toContain('Resolved')
  })

  it('reloads on demand and tells an empty list apart from a failed one', async () => {
    operationsState.findings = []
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('there is no open finding')

    operationsState.fetchFindings.mockClear()
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Reload'))!
      .trigger('click')
    expect(operationsState.fetchFindings).toHaveBeenCalledWith(false)

    operationsState.error = new Error('Boom')
    await flushPromises()
    expect(wrapper.text()).toContain('Boom')
  })

  it('loads and shows the time of the last scan, reloaded with the list', async () => {
    const wrapper = await mountView()

    expect(operationsState.fetchLastScan).toHaveBeenCalledTimes(1)
    const lastScan = wrapper.get('[data-testid="last-scan"]')
    expect(lastScan.element.tagName).toBe('P')
    expect(lastScan.text()).toContain('Last scan:')
    expect(lastScan.text()).toContain('(3 min ago)')

    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Reload'))!
      .trigger('click')
    expect(operationsState.fetchLastScan).toHaveBeenCalledTimes(2)
  })

  it('warns when the last scan is 15 minutes old or no scan finished yet', async () => {
    operationsState.lastScanAt = '2026-10-16T10:05:00.000Z'
    const wrapper = await mountView()
    const stale = wrapper.get('[data-testid="last-scan"]')
    expect(stale.element.tagName).not.toBe('P')
    expect(stale.text()).toContain('(15 min ago)')
    expect(stale.text()).toContain('Operations worker scan failed')

    operationsState.lastScanAt = null
    await flushPromises()
    expect(wrapper.get('[data-testid="last-scan"]').text()).toContain('Last scan: none yet.')
  })

  it('says so when the time of the last scan could not be loaded, without a warning', async () => {
    operationsState.lastScanAt = undefined
    const wrapper = await mountView()

    const unknown = wrapper.get('[data-testid="last-scan"]')
    expect(unknown.element.tagName).toBe('P')
    expect(unknown.text()).toBe('Last scan: unknown, the time could not be loaded.')
  })
})
