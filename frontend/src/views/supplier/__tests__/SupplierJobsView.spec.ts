import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, reactive } from 'vue'
import ShipJobDialog from '@/components/shared/ShipJobDialog.vue'
import { JobAlreadyShippedError, JobPdfUnavailableError } from '@/lib/fulfillment'
import type { SupplierJob } from '@/stores/supplier/jobs'
import SupplierJobsView from '../SupplierJobsView.vue'

const routeState = reactive<{ query: Record<string, string> }>({ query: {} })
const routerMock = { replace: vi.fn(), push: vi.fn() }
const toastMock = vi.fn()
const saveBlobAsMock = vi.fn()

const storeState = reactive({
  identity: null as unknown,
  jobs: [] as SupplierJob[],
  loadedStatus: null as string | null,
  isLoading: false,
  error: null as Error | null,
  shippingJobId: null as number | null,
  downloadingJobId: null as number | null,
  fetchIdentity: vi.fn(),
  fetchJobs: vi.fn(),
  ship: vi.fn(),
  downloadPdf: vi.fn(),
})

vi.mock('vue-router', () => ({
  useRoute: () => routeState,
  useRouter: () => routerMock,
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast: toastMock }),
}))

vi.mock('@/lib/download', () => ({
  saveBlobAs: (...args: unknown[]) => saveBlobAsMock(...args),
}))

vi.mock('@/stores/supplier/jobs', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/stores/supplier/jobs')>()
  return { ...actual, useSupplierJobsStore: () => storeState }
})

const openJob: SupplierJob = {
  jobId: 5,
  orderId: 42,
  orderDate: '2026-08-13',
  customerFirstName: 'Ada',
  customerLastName: 'Lovelace',
  shippingStreet: 'Hauptstrasse',
  shippingHouseNumber: '7',
  shippingPostalCode: '10115',
  shippingCity: 'Berlin',
  shippingCountry: 'Germany',
  items: [{ articleName: 'Mug', variantName: 'White', supplierArticleNumber: 'M-1', quantity: 2 }],
  pdfAvailable: true,
  shippedAt: null,
  shippingCarrier: null,
  trackingNumber: null,
}

function mountView() {
  return mount(SupplierJobsView, { attachTo: document.body })
}

beforeEach(() => {
  routeState.query = {}
  routerMock.replace.mockReset()
  toastMock.mockReset()
  saveBlobAsMock.mockReset()
  storeState.jobs = [openJob]
  storeState.isLoading = false
  storeState.error = null
  storeState.shippingJobId = null
  storeState.downloadingJobId = null
  storeState.fetchJobs.mockReset().mockResolvedValue([openJob])
  storeState.ship.mockReset().mockResolvedValue(openJob)
  storeState.downloadPdf.mockReset()
})

describe('SupplierJobsView', () => {
  it('loads the open jobs and shows order, customer, address, and items', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(storeState.fetchJobs).toHaveBeenCalledWith('OPEN')
    const text = wrapper.text()
    expect(text).toContain('ORD-42')
    expect(text).toContain('2026-08-13')
    expect(text).toContain('Ada Lovelace')
    expect(text).toContain('Hauptstrasse 7')
    expect(text).toContain('10115 Berlin')
    expect(text).toContain('Mug')
  })

  it('loads the shipped jobs when the URL asks for them', async () => {
    routeState.query = { status: 'SHIPPED' }

    mountView()
    await flushPromises()

    expect(storeState.fetchJobs).toHaveBeenCalledWith('SHIPPED')
  })

  it('writes the selected tab into the URL instead of fetching behind the URL', async () => {
    const wrapper = mountView()
    await flushPromises()
    storeState.fetchJobs.mockClear()

    const shippedTab = wrapper.findAll('button')[1]!
    await shippedTab.trigger('mousedown', { button: 0 })
    await shippedTab.trigger('click')

    expect(routerMock.replace).toHaveBeenCalledWith({ query: { status: 'SHIPPED' } })
    expect(storeState.fetchJobs).not.toHaveBeenCalled()

    routeState.query = { status: 'SHIPPED' }
    await flushPromises()

    expect(storeState.fetchJobs).toHaveBeenCalledWith('SHIPPED')
  })

  it('disables the download of a job whose document is still being generated and says why', async () => {
    storeState.jobs = [{ ...openJob, pdfAvailable: false, items: [] }]

    const wrapper = mountView()
    await flushPromises()

    const downloadButton = wrapper
      .findAll('button')
      .find((button) => button.text().includes('Download PDF'))
    expect(downloadButton?.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('has not been generated yet')
  })

  it('saves a downloaded document under the name the store chose', async () => {
    const blob = new Blob(['%PDF'])
    storeState.downloadPdf.mockResolvedValue({ blob, fileName: 'ORD-42.pdf' })

    const wrapper = mountView()
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Download PDF'))!
      .trigger('click')
    await flushPromises()

    expect(saveBlobAsMock).toHaveBeenCalledWith(blob, 'ORD-42.pdf')
  })

  it('words an unavailable document by its conflict code', async () => {
    storeState.downloadPdf.mockRejectedValue(
      new JobPdfUnavailableError('No document', 'ARTIFACT_DIGEST_MISMATCH'),
    )

    const wrapper = mountView()
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Download PDF'))!
      .trigger('click')
    await flushPromises()

    expect(toastMock).toHaveBeenCalledWith(
      expect.objectContaining({
        variant: 'destructive',
        description: expect.stringContaining('report this job'),
      }),
    )
  })

  it('ships through the confirmation dialog and reloads the tab afterwards', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Mark as shipped'))!
      .trigger('click')
    await nextTick()

    const dialog = wrapper.findComponent(ShipJobDialog)
    expect(dialog.props('open')).toBe(true)
    expect(dialog.props('job')).toMatchObject({ jobId: 5 })

    storeState.fetchJobs.mockClear()
    dialog.vm.$emit('confirm', { carrier: 'DHL', trackingNumber: '1234' })
    await flushPromises()

    expect(storeState.ship).toHaveBeenCalledWith(5, { carrier: 'DHL', trackingNumber: '1234' })
    expect(storeState.fetchJobs).toHaveBeenCalledWith('OPEN')
    expect(wrapper.findComponent(ShipJobDialog).props('open')).toBe(false)
  })

  it('names the conflicting state and reloads the tab when the job was already shipped', async () => {
    storeState.ship.mockRejectedValue(new JobAlreadyShippedError('Already shipped'))

    const wrapper = mountView()
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('Mark as shipped'))!
      .trigger('click')
    await nextTick()

    storeState.fetchJobs.mockClear()
    wrapper.findComponent(ShipJobDialog).vm.$emit('confirm', {})
    await flushPromises()

    expect(wrapper.text()).toContain('already been shipped')
    expect(storeState.fetchJobs).toHaveBeenCalledWith('OPEN')
    expect(wrapper.findComponent(ShipJobDialog).props('open')).toBe(false)
  })
})
