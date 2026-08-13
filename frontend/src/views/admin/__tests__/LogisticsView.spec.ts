import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, reactive } from 'vue'
import ShipJobDialog from '@/components/shared/ShipJobDialog.vue'
import { Select } from '@/components/ui/select'
import type { AdminJob } from '@/stores/admin/fulfillment'
import { JobAlreadyShippedError, JobPdfUnavailableError } from '@/lib/fulfillment'
import LogisticsView from '../LogisticsView.vue'

const toastMock = vi.fn()
const saveBlobAsMock = vi.fn()

const fulfillmentState = reactive({
  jobs: [] as AdminJob[],
  loadedStatus: null as string | null,
  isLoading: false,
  error: null as Error | null,
  shippingJobId: null as number | null,
  downloadingJobId: null as number | null,
  fetchJobs: vi.fn(),
  ship: vi.fn(),
  downloadPdf: vi.fn(),
})

const suppliersState = reactive({
  suppliers: [{ id: 3, name: 'Acme' }],
  isLoading: false,
  error: null as string | null,
  fetchSuppliers: vi.fn(),
})

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast: toastMock }),
}))

vi.mock('@/lib/download', () => ({
  saveBlobAs: (...args: unknown[]) => saveBlobAsMock(...args),
}))

vi.mock('@/stores/admin/fulfillment', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/stores/admin/fulfillment')>()
  return { ...actual, useAdminFulfillmentStore: () => fulfillmentState }
})

vi.mock('@/stores/admin/suppliers', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/stores/admin/suppliers')>()
  return { ...actual, useAdminSuppliersStore: () => suppliersState }
})

const openJob: AdminJob = {
  jobId: 5,
  orderId: 42,
  orderDate: '2026-08-13',
  supplier: { id: 3, name: 'Acme' },
  customerFirstName: 'Ada',
  customerLastName: 'Lovelace',
  shippingStreet: 'Hauptstrasse',
  shippingHouseNumber: '7',
  shippingPostalCode: '10115',
  shippingCity: 'Berlin',
  shippingCountry: 'Germany',
  items: [{ articleName: 'Mug', variantName: 'White', supplierArticleNumber: 'M-1', quantity: 2 }],
  pdfAvailable: true,
  generationAttemptCount: 1,
  lastGenerationErrorCode: null,
  shippedAt: null,
  shippedByUserId: null,
  shippingCarrier: null,
  trackingNumber: null,
}

function mountView() {
  return mount(LogisticsView, { attachTo: document.body })
}

function findButton(wrapper: ReturnType<typeof mountView>, label: string) {
  return wrapper.findAll('button').find((button) => button.text().includes(label))
}

beforeEach(() => {
  toastMock.mockReset()
  saveBlobAsMock.mockReset()
  fulfillmentState.jobs = [openJob]
  fulfillmentState.isLoading = false
  fulfillmentState.error = null
  fulfillmentState.shippingJobId = null
  fulfillmentState.downloadingJobId = null
  fulfillmentState.fetchJobs.mockReset().mockResolvedValue([openJob])
  fulfillmentState.ship.mockReset().mockResolvedValue(openJob)
  fulfillmentState.downloadPdf.mockReset()
  suppliersState.fetchSuppliers.mockReset().mockResolvedValue(undefined)
})

describe('LogisticsView', () => {
  it('loads the open jobs of every supplier and names the supplier of each row', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(fulfillmentState.fetchJobs).toHaveBeenCalledWith('OPEN', null)
    expect(suppliersState.fetchSuppliers).toHaveBeenCalled()
    const text = wrapper.text()
    expect(text).toContain('ORD-42')
    expect(text).toContain('Acme')
    expect(text).toContain('Ada Lovelace')
  })

  it('reloads the list for the shipped tab', async () => {
    const wrapper = mountView()
    await flushPromises()
    fulfillmentState.fetchJobs.mockClear()

    const shippedTab = findButton(wrapper, 'Shipped')!
    await shippedTab.trigger('mousedown', { button: 0 })
    await shippedTab.trigger('click')
    await flushPromises()

    expect(fulfillmentState.fetchJobs).toHaveBeenCalledWith('SHIPPED', null)
  })

  it('narrows the list to the selected supplier and drops the filter again', async () => {
    const wrapper = mountView()
    await flushPromises()
    fulfillmentState.fetchJobs.mockClear()

    wrapper.findComponent(Select).vm.$emit('update:modelValue', '3')
    await flushPromises()
    expect(fulfillmentState.fetchJobs).toHaveBeenCalledWith('OPEN', 3)

    wrapper.findComponent(Select).vm.$emit('update:modelValue', 'all')
    await flushPromises()
    expect(fulfillmentState.fetchJobs).toHaveBeenLastCalledWith('OPEN', null)
  })

  it('shows the generation state of a job whose document does not exist yet', async () => {
    fulfillmentState.jobs = [
      {
        ...openJob,
        pdfAvailable: false,
        items: [],
        generationAttemptCount: 3,
        lastGenerationErrorCode: 'MISSING_IMAGE',
      },
    ]

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('PDF in preparation')
    expect(wrapper.text()).toContain('3 attempts')
    expect(wrapper.text()).toContain('MISSING_IMAGE')
    expect(findButton(wrapper, 'PDF')?.attributes('disabled')).toBeDefined()
  })

  it('saves a downloaded document under the name the store chose', async () => {
    const blob = new Blob(['%PDF'])
    fulfillmentState.downloadPdf.mockResolvedValue({ blob, fileName: 'ORD-42.pdf' })

    const wrapper = mountView()
    await flushPromises()
    await findButton(wrapper, 'PDF')!.trigger('click')
    await flushPromises()

    expect(saveBlobAsMock).toHaveBeenCalledWith(blob, 'ORD-42.pdf')
  })

  it('words an unavailable document by its conflict code', async () => {
    fulfillmentState.downloadPdf.mockRejectedValue(
      new JobPdfUnavailableError('No document', 'ARTIFACT_DIGEST_MISMATCH'),
    )

    const wrapper = mountView()
    await flushPromises()
    await findButton(wrapper, 'PDF')!.trigger('click')
    await flushPromises()

    expect(toastMock).toHaveBeenCalledWith(
      expect.objectContaining({
        variant: 'destructive',
        description: expect.stringContaining('checksum'),
      }),
    )
  })

  it('ships on behalf of the supplier through the shared dialog and reloads the tab', async () => {
    const wrapper = mountView()
    await flushPromises()

    await findButton(wrapper, 'Mark as shipped')!.trigger('click')
    await nextTick()

    const dialog = wrapper.findComponent(ShipJobDialog)
    expect(dialog.props('open')).toBe(true)
    expect(dialog.props('job')).toMatchObject({ jobId: 5 })
    expect(dialog.props('supplierName')).toBe('Acme')

    fulfillmentState.fetchJobs.mockClear()
    dialog.vm.$emit('confirm', { carrier: 'DHL', trackingNumber: '1234' })
    await flushPromises()

    expect(fulfillmentState.ship).toHaveBeenCalledWith(5, {
      carrier: 'DHL',
      trackingNumber: '1234',
    })
    expect(fulfillmentState.fetchJobs).toHaveBeenCalledWith('OPEN', null)
    expect(wrapper.findComponent(ShipJobDialog).props('open')).toBe(false)
  })

  it('names the conflicting state and reloads the tab when the job was already shipped', async () => {
    fulfillmentState.ship.mockRejectedValue(new JobAlreadyShippedError('Already shipped'))

    const wrapper = mountView()
    await flushPromises()
    await findButton(wrapper, 'Mark as shipped')!.trigger('click')
    await nextTick()

    fulfillmentState.fetchJobs.mockClear()
    wrapper.findComponent(ShipJobDialog).vm.$emit('confirm', {})
    await flushPromises()

    expect(wrapper.text()).toContain('already been shipped')
    expect(fulfillmentState.fetchJobs).toHaveBeenCalledWith('OPEN', null)
    expect(wrapper.findComponent(ShipJobDialog).props('open')).toBe(false)
  })
})
