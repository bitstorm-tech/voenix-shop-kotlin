import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ProductionDestinationsView from '../ProductionDestinationsView.vue'
import { Select } from '@/components/ui/select'
import type { AdminProductionDestinationDto } from '@/stores/admin/productionDestinations'

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
  mocks.suppliersState.fetchSuppliers.mockReset().mockResolvedValue(undefined)
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
