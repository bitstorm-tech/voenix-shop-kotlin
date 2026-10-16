import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, reactive } from 'vue'
import ReleaseSpodJobDialog from '@/components/admin/logistics/ReleaseSpodJobDialog.vue'
import ShipJobDialog from '@/components/shared/ShipJobDialog.vue'
import { Select } from '@/components/ui/select'
import type { AdminJob } from '@/stores/admin/fulfillment'
import { ApiError } from '@/lib/api'
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
  releaseSpodJob: vi.fn(),
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
  fulfillmentChannel: 'SFTP',
  pdfAvailable: true,
  generationAttemptCount: 1,
  lastGenerationErrorCode: null,
  externalReference: null,
  remoteState: null,
  createState: null,
  shippedAt: null,
  shippedByUserId: null,
  shippedByChannel: null,
  shippingCarrier: null,
  shippingCarrierReported: null,
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
  fulfillmentState.releaseSpodJob.mockReset().mockResolvedValue(openJob)
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

  it('names the channel of a row and what the partner reported for a SPOD job', async () => {
    fulfillmentState.jobs = [
      {
        ...openJob,
        fulfillmentChannel: 'SPOD',
        pdfAvailable: false,
        externalReference: 'SPOD-9911',
        remoteState: 'CONFIRMED',
      },
    ]

    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('SPOD')
    expect(text).toContain('SPOD-9911')
    expect(text).toContain('CONFIRMED')
    // A SPOD job produces no document, so its missing PDF is not a warning and offers no download.
    expect(text).not.toContain('PDF in preparation')
    expect(findButton(wrapper, 'PDF')).toBeUndefined()
    expect(findButton(wrapper, 'Mark as shipped')?.attributes('disabled')).toBeUndefined()
  })

  it('says who reported a shipment and keeps the carrier name the channel sent', async () => {
    fulfillmentState.jobs = [
      {
        ...openJob,
        fulfillmentChannel: 'SPOD',
        pdfAvailable: false,
        shippedAt: '2026-08-20T09:00:00Z',
        shippedByChannel: 'SPOD',
        shippingCarrier: 'DHL',
        shippingCarrierReported: 'DHL Paket International',
        trackingNumber: '1234',
      },
    ]

    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('reported by SPOD')
    expect(text).toContain('reported as DHL Paket International')
    expect(text).toContain('1234')
  })

  it('marks a manually reported shipment as such', async () => {
    fulfillmentState.jobs = [
      { ...openJob, shippedAt: '2026-08-20T09:00:00Z', shippedByUserId: 7, shippingCarrier: 'DHL' },
    ]

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('reported manually')
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

  describe('releasing a quarantined SPOD job', () => {
    const spodJob = (createState: string | null): AdminJob => ({
      ...openJob,
      jobId: 7,
      fulfillmentChannel: 'SPOD',
      pdfAvailable: false,
      createState,
    })

    it('offers the release only on a SPOD job in OUTCOME_UNKNOWN', async () => {
      fulfillmentState.jobs = [
        openJob,
        spodJob('PENDING'),
        { ...spodJob('OUTCOME_UNKNOWN'), jobId: 8 },
      ]
      const wrapper = mountView()
      await flushPromises()

      expect(wrapper.find('[data-testid="release-job-5"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="release-job-7"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="release-job-8"]').exists()).toBe(true)
    })

    it('releases through the dialog and reloads the tab', async () => {
      fulfillmentState.jobs = [spodJob('OUTCOME_UNKNOWN')]
      const wrapper = mountView()
      await flushPromises()

      await wrapper.find('[data-testid="release-job-7"]').trigger('click')
      await nextTick()
      const dialog = wrapper.findComponent(ReleaseSpodJobDialog)
      expect(dialog.props('open')).toBe(true)
      expect(dialog.props('job')).toMatchObject({ jobId: 7 })

      fulfillmentState.fetchJobs.mockClear()
      const body = { decision: 'NOT_CREATED', confirmation: 'ORD-42-JOB-7' }
      dialog.vm.$emit('confirm', body)
      await flushPromises()

      expect(fulfillmentState.releaseSpodJob).toHaveBeenCalledWith(7, body)
      expect(fulfillmentState.fetchJobs).toHaveBeenCalledWith('OPEN', null)
      expect(wrapper.findComponent(ReleaseSpodJobDialog).props('open')).toBe(false)
    })

    it('closes on a conflict and names it; keeps any other refusal in the dialog', async () => {
      fulfillmentState.jobs = [spodJob('OUTCOME_UNKNOWN')]
      fulfillmentState.releaseSpodJob
        .mockRejectedValueOnce(new ApiError('Validation failed', 400, null))
        .mockRejectedValueOnce(new ApiError('The job is not quarantined', 409, null))
      const wrapper = mountView()
      await flushPromises()
      await wrapper.find('[data-testid="release-job-7"]').trigger('click')
      await nextTick()
      const body = { decision: 'NOT_CREATED', confirmation: 'ORD-42-JOB-7' }

      wrapper.findComponent(ReleaseSpodJobDialog).vm.$emit('confirm', body)
      await flushPromises()
      expect(wrapper.findComponent(ReleaseSpodJobDialog).props('error')).toBe('Validation failed')
      expect(wrapper.findComponent(ReleaseSpodJobDialog).props('open')).toBe(true)

      wrapper.findComponent(ReleaseSpodJobDialog).vm.$emit('confirm', body)
      await flushPromises()
      expect(wrapper.findComponent(ReleaseSpodJobDialog).props('open')).toBe(false)
      expect(wrapper.text()).toContain('The job is not quarantined. The list has been reloaded.')
    })
  })
})
