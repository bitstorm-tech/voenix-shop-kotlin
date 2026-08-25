import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ProductionDestinationsView from '../ProductionDestinationsView.vue'
import { Select } from '@/components/ui/select'
import type {
  AdminProductionDestinationDto,
  TshirtSyncLine,
  TshirtSyncReport,
} from '@/stores/admin/productionDestinations'

const mocks = vi.hoisted(() => {
  class DestinationNotFoundError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'DestinationNotFoundError'
    }
  }

  class DestinationInUseError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'DestinationInUseError'
    }
  }

  class InvalidDestinationRequestError extends Error {
    readonly fieldErrors: Record<string, string[]>

    constructor(message: string, fieldErrors: Record<string, string[]> = {}) {
      super(message)
      this.name = 'InvalidDestinationRequestError'
      this.fieldErrors = fieldErrors
    }

    fieldError(field: string): string | null {
      return this.fieldErrors[field]?.[0] ?? null
    }
  }

  return {
    toast: vi.fn(),
    storeState: {
      destinations: [] as AdminProductionDestinationDto[],
      isLoading: false,
      error: null as string | null,
      fetchDestinations: vi.fn(),
      fetchDestination: vi.fn(),
      createDestination: vi.fn(),
      updateDestination: vi.fn(),
      deleteDestination: vi.fn(),
      isSyncing: vi.fn(),
      syncReport: vi.fn(),
      syncArticles: vi.fn(),
    },
    suppliersState: {
      suppliers: [{ id: 3, name: 'Acme' }],
      isLoading: false,
      error: null as string | null,
      fetchSuppliers: vi.fn(),
    },
    DestinationNotFoundError,
    DestinationInUseError,
    InvalidDestinationRequestError,
  }
})

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast: mocks.toast }),
}))

vi.mock('@/stores/admin/productionDestinations', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/stores/admin/productionDestinations')>()),
  useAdminProductionDestinationsStore: () => mocks.storeState,
  DestinationNotFoundError: mocks.DestinationNotFoundError,
  DestinationInUseError: mocks.DestinationInUseError,
  InvalidDestinationRequestError: mocks.InvalidDestinationRequestError,
}))

vi.mock('@/stores/admin/suppliers', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/stores/admin/suppliers')>()),
  useAdminSuppliersStore: () => mocks.suppliersState,
}))

const spodDestination: AdminProductionDestinationDto = {
  id: 1,
  supplierId: 3,
  channel: 'SPOD',
  label: 'Acme print-on-demand',
  enabled: true,
  notificationEmail: 'ops@acme.test',
  notificationName: 'Acme Ops',
  spod: { environment: 'PRODUCTION', timeoutSeconds: 45 },
}

const sftpDestination: AdminProductionDestinationDto = {
  id: 2,
  supplierId: 3,
  channel: 'SFTP',
  label: 'Acme upload',
  enabled: true,
  notificationEmail: null,
  notificationName: null,
  sftp: {
    host: 'sftp.acme.test',
    port: 22,
    username: 'acme',
    hostKeyFingerprint: 'SHA256:abc',
    remotePath: '/in',
    timeoutSeconds: 30,
  },
}

function resetStoreState() {
  mocks.storeState.destinations = []
  mocks.storeState.isLoading = false
  mocks.storeState.error = null
  mocks.storeState.fetchDestinations.mockReset().mockResolvedValue(undefined)
  mocks.storeState.fetchDestination.mockReset()
  mocks.storeState.createDestination.mockReset()
  mocks.storeState.updateDestination.mockReset()
  mocks.storeState.deleteDestination.mockReset()
  mocks.storeState.isSyncing.mockReset().mockReturnValue(false)
  mocks.storeState.syncReport.mockReset().mockReturnValue(null)
  mocks.storeState.syncArticles.mockReset().mockResolvedValue(undefined)
  mocks.suppliersState.fetchSuppliers.mockReset().mockResolvedValue(undefined)
}

function syncReport(overrides: Partial<TshirtSyncReport> = {}): TshirtSyncReport {
  return {
    destinationId: 1,
    supplierId: 3,
    environment: 'PRODUCTION',
    status: 'COMPLETED',
    failure: null,
    startedAt: '2026-08-24T10:00:00Z',
    finishedAt: '2026-08-24T10:00:12Z',
    fetchedArticles: 9,
    created: [line('spod-1')],
    updated: [line('spod-2'), line('spod-3')],
    unchanged: [line('spod-4'), line('spod-5'), line('spod-6')],
    deactivated: [line('spod-7')],
    failed: [],
    warnings: [],
    ...overrides,
  }
}

function line(spodArticleId: string): TshirtSyncLine {
  return {
    articleId: 11,
    spodArticleId,
    name: 'Classic Tee',
    variantsCreated: 0,
    variantsUpdated: 0,
    variantsDeactivated: 0,
  }
}

async function mountView() {
  const wrapper = mount(ProductionDestinationsView, { attachTo: document.body })
  await flushPromises()
  return wrapper
}

function queryButtonByText(text: string) {
  return [...document.body.querySelectorAll('button')].find((button) =>
    button.textContent?.includes(text),
  ) as HTMLButtonElement | undefined
}

async function clickButtonByText(text: string) {
  const button = queryButtonByText(text)
  expect(button).toBeTruthy()
  button?.click()
  await flushPromises()
}

function input(testId: string) {
  return document.body.querySelector(`[data-testid="${testId}"]`) as HTMLInputElement | null
}

/** Types into a `v-model` input the way a user does: set the value, then fire `input`. */
async function typeInto(testId: string, value: string) {
  const field = input(testId)
  expect(field).toBeTruthy()
  field!.value = value
  field!.dispatchEvent(new Event('input'))
  await flushPromises()
}

/**
 * The dialog's selects in document order: supplier, channel, and — on a SPOD destination — the
 * environment. Emitting on the component is how the other admin specs drive a Reka select.
 */
function selectAt(wrapper: VueWrapper, index: number) {
  return wrapper.findAllComponents(Select)[index]!
}

function syncButton(destinationId: number) {
  return document.body.querySelector(
    `[data-testid="destination-sync-${destinationId}"]`,
  ) as HTMLButtonElement | null
}

async function openCreateDialog() {
  await clickButtonByText('Add Destination')
}

async function openEditDialog(label: string) {
  const editButton = document.body.querySelector(
    `[aria-label="Edit destination ${label}"]`,
  ) as HTMLButtonElement | null
  expect(editButton).toBeTruthy()
  editButton?.click()
  await flushPromises()
}

describe('ProductionDestinationsView', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    mocks.toast.mockReset()
    resetStoreState()
  })

  it('loads the destinations with the supplier names of their rows', async () => {
    mocks.storeState.destinations = [spodDestination, sftpDestination]

    const wrapper = await mountView()

    expect(mocks.storeState.fetchDestinations).toHaveBeenCalledTimes(1)
    expect(mocks.suppliersState.fetchSuppliers).toHaveBeenCalledTimes(1)
    const text = wrapper.text()
    expect(text).toContain('Acme print-on-demand')
    expect(text).toContain('Acme')
    expect(text).toContain('SPOD')
    expect(text).toContain('PRODUCTION · 45s')
    expect(text).toContain('acme@sftp.acme.test:22/in')
  })

  it('offers the SFTP form by default and swaps to the SPOD form when the channel changes', async () => {
    const wrapper = await mountView()
    await openCreateDialog()

    expect(document.body.querySelector('[data-testid="destination-sftp-form"]')).toBeTruthy()
    expect(input('destination-sftp-host')).toBeTruthy()
    expect(input('destination-sftp-password')).toBeTruthy()
    expect(document.body.querySelector('[data-testid="destination-spod-form"]')).toBeNull()

    selectAt(wrapper, 1).vm.$emit('update:modelValue', 'SPOD')
    await flushPromises()

    expect(document.body.querySelector('[data-testid="destination-spod-form"]')).toBeTruthy()
    expect(document.body.querySelector('[data-testid="destination-spod-environment"]')).toBeTruthy()
    expect(input('destination-spod-timeout')).toBeTruthy()
    expect(input('destination-spod-access-token')).toBeTruthy()
    expect(document.body.querySelector('[data-testid="destination-sftp-form"]')).toBeNull()
  })

  it('never renders the access token back after a save or when an existing destination is reopened', async () => {
    mocks.storeState.destinations = [spodDestination]
    mocks.storeState.fetchDestination.mockResolvedValue(spodDestination)
    mocks.storeState.updateDestination.mockResolvedValue(spodDestination)

    await mountView()
    await openEditDialog('Acme print-on-demand')

    // The stored destination answers no token, so the edit form starts empty and says why.
    expect(input('destination-spod-access-token')?.value).toBe('')
    expect(document.body.textContent).toContain('Leave empty to keep the stored value.')
    expect(input('destination-spod-timeout')?.value).toBe('45')

    await typeInto('destination-spod-access-token', 'freshly-typed-token')

    await clickButtonByText('Save Destination')

    expect(mocks.storeState.updateDestination).toHaveBeenCalledWith(
      1,
      expect.objectContaining({
        channel: 'SPOD',
        spod: expect.objectContaining({
          environment: 'PRODUCTION',
          accessToken: 'freshly-typed-token',
          timeoutSeconds: 45,
        }),
      }),
    )

    await openEditDialog('Acme print-on-demand')
    expect(input('destination-spod-access-token')?.value).toBe('')
    expect(document.body.textContent).not.toContain('freshly-typed-token')
  })

  it('keeps the stored token when the field is left empty on an update', async () => {
    mocks.storeState.destinations = [spodDestination]
    mocks.storeState.fetchDestination.mockResolvedValue(spodDestination)
    mocks.storeState.updateDestination.mockResolvedValue(spodDestination)

    await mountView()
    await openEditDialog('Acme print-on-demand')
    await clickButtonByText('Save Destination')

    const payload = mocks.storeState.updateDestination.mock.calls[0]![1]
    expect(payload.spod.accessToken).toBeUndefined()
  })

  it('shows the refused write on the fields the backend named', async () => {
    mocks.storeState.destinations = [spodDestination]
    mocks.storeState.fetchDestination.mockResolvedValue(spodDestination)
    mocks.storeState.updateDestination.mockRejectedValue(
      new mocks.InvalidDestinationRequestError('Validation failed', {
        channel: ['Supplier already has an enabled SPOD destination; disable it first'],
        'spod.accessToken': ['AccessToken must be at most 512 characters'],
      }),
    )

    await mountView()
    await openEditDialog('Acme print-on-demand')
    await clickButtonByText('Save Destination')

    const text = document.body.textContent ?? ''
    expect(text).toContain('Supplier already has an enabled SPOD destination; disable it first')
    expect(text).toContain('AccessToken must be at most 512 characters')
  })

  it('offers the sync only on SPOD rows and runs it for that destination', async () => {
    mocks.storeState.destinations = [spodDestination, sftpDestination]

    await mountView()

    expect(syncButton(2)).toBeNull()
    const button = syncButton(1)
    expect(button).toBeTruthy()
    expect(button?.disabled).toBe(false)

    button?.click()
    await flushPromises()

    expect(mocks.storeState.syncArticles).toHaveBeenCalledWith(1)
  })

  it('keeps a disabled destination syncable', async () => {
    // A disabled destination sends no jobs, but its catalog is still read (issue #224, decision D5).
    mocks.storeState.destinations = [{ ...spodDestination, enabled: false }]

    await mountView()

    expect(syncButton(1)?.disabled).toBe(false)
  })

  it('disables the button of a destination while its run is in flight', async () => {
    mocks.storeState.destinations = [{ ...spodDestination, enabled: false }]
    mocks.storeState.isSyncing.mockReturnValue(true)

    await mountView()

    const button = syncButton(1)
    expect(button?.disabled).toBe(true)
    expect(button?.textContent).toContain('Syncing...')
  })

  it('reports what the finished run did, with the warnings behind a toggle', async () => {
    mocks.storeState.destinations = [spodDestination]
    mocks.storeState.syncReport.mockReturnValue(
      syncReport({
        warnings: [
          {
            code: 'COLOR_WITHOUT_IMAGE',
            spodArticleId: 'spod-3',
            detail: 'colorId 42 has no front view',
          },
        ],
      }),
    )

    await mountView()

    const panel = document.body.querySelector('[data-testid="destination-sync-report-1"]')
    expect(panel?.textContent).toContain('Read 9 articles from PRODUCTION.')
    expect(panel?.textContent).toContain('Created 1')
    expect(panel?.textContent).toContain('Updated 2')
    expect(panel?.textContent).toContain('Unchanged 3')
    expect(panel?.textContent).toContain('Deactivated 1')
    expect(panel?.textContent).toContain('Failed 0')

    expect(document.body.textContent).not.toContain('colorId 42 has no front view')

    const toggle = document.body.querySelector(
      '[data-testid="destination-sync-warnings-toggle-1"]',
    ) as HTMLButtonElement | null
    expect(toggle?.textContent).toContain('Show 1 warnings')
    toggle?.click()
    await flushPromises()

    const warnings = document.body.querySelector('[data-testid="destination-sync-warnings-1"]')
    expect(warnings?.textContent).toContain('COLOR_WITHOUT_IMAGE')
    expect(warnings?.textContent).toContain('spod-3')
    expect(warnings?.textContent).toContain('colorId 42 has no front view')
  })

  it('collapses the warnings of the previous run when the next one starts', async () => {
    mocks.storeState.destinations = [spodDestination]
    mocks.storeState.syncReport.mockReturnValue(
      syncReport({
        warnings: [
          { code: 'COLOR_WITHOUT_IMAGE', spodArticleId: 'spod-3', detail: 'no front view' },
        ],
      }),
    )

    await mountView()

    const toggle = () =>
      document.body.querySelector(
        '[data-testid="destination-sync-warnings-toggle-1"]',
      ) as HTMLButtonElement | null
    toggle()?.click()
    await flushPromises()
    expect(toggle()?.textContent).toContain('Hide 1 warnings')

    syncButton(1)?.click()
    await flushPromises()

    expect(toggle()?.textContent).toContain('Show 1 warnings')
  })

  it('shows a failed run as a destructive alert with its bounded reason', async () => {
    mocks.storeState.destinations = [spodDestination]
    mocks.storeState.syncReport.mockReturnValue(
      syncReport({
        status: 'FAILED',
        failure: 'PROVIDER_UNAVAILABLE',
        created: [],
        updated: [],
        unchanged: [],
        deactivated: [],
      }),
    )

    await mountView()

    const alert = document.body.querySelector(
      '[data-testid="destination-sync-report-1"] [role="alert"]',
    )
    expect(alert?.textContent).toContain('nothing was written')
    expect(alert?.textContent).toContain('PROVIDER_UNAVAILABLE')
  })

  it('says that a destination is already syncing when the backend refuses the run', async () => {
    mocks.storeState.destinations = [spodDestination]
    mocks.storeState.syncArticles.mockRejectedValue(
      new Error('A sync is already running for this destination.'),
    )

    await mountView()
    syncButton(1)?.click()
    await flushPromises()

    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Sync failed',
      description: 'A sync is already running for this destination.',
      variant: 'destructive',
    })
  })

  it('names the way out when a referenced destination cannot be deleted', async () => {
    mocks.storeState.destinations = [spodDestination]
    mocks.storeState.fetchDestination.mockResolvedValue(spodDestination)
    mocks.storeState.deleteDestination.mockRejectedValue(
      new mocks.DestinationInUseError(
        'Production destination is in use and cannot be deleted; disable it instead',
      ),
    )

    await mountView()
    await openEditDialog('Acme print-on-demand')
    await clickButtonByText('Delete Destination')

    const confirmButton = document.body.querySelector(
      '[data-testid="confirm-delete-destination"]',
    ) as HTMLButtonElement | null
    expect(confirmButton).toBeTruthy()
    confirmButton?.click()
    await flushPromises()

    expect(mocks.toast).toHaveBeenCalledWith(
      expect.objectContaining({
        title: 'Destination cannot be deleted',
        variant: 'destructive',
      }),
    )
  })
})
