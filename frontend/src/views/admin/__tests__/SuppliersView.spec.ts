import { flushPromises, mount } from '@vue/test-utils'
import { ref, shallowRef } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SuppliersView from '../SuppliersView.vue'
import type {
  AdminSupplierDetailDto,
  AdminSupplierListItemDto,
  CreateAdminSupplierRequest,
} from '@/stores/admin/suppliers'

const mocks = vi.hoisted(() => {
  class SupplierNotFoundError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'SupplierNotFoundError'
    }
  }

  class SupplierInUseError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'SupplierInUseError'
    }
  }

  class SupplierCountryNotFoundError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'SupplierCountryNotFoundError'
    }
  }

  return {
    toast: vi.fn(),
    storeState: {
      suppliers: [] as AdminSupplierListItemDto[],
      isLoading: false,
      error: null as string | null,
      fetchSuppliers: vi.fn(),
      fetchSupplier: vi.fn(),
      createSupplier: vi.fn(),
      updateSupplier: vi.fn(),
      deleteSupplier: vi.fn(),
    },
    SupplierNotFoundError,
    SupplierInUseError,
    SupplierCountryNotFoundError,
  }
})

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast: mocks.toast }),
}))

vi.mock('@/composables/useAdminCountries', () => ({
  useAdminCountries: () => ({
    countries: ref([{ id: 1, name: 'Germany', countryCode: 'DE' }]),
    error: shallowRef(null),
    isLoading: shallowRef(false),
    loadCountries: vi.fn(),
  }),
}))

vi.mock('@/stores/admin/suppliers', () => ({
  useAdminSuppliersStore: () => mocks.storeState,
  SupplierNotFoundError: mocks.SupplierNotFoundError,
  SupplierInUseError: mocks.SupplierInUseError,
  SupplierCountryNotFoundError: mocks.SupplierCountryNotFoundError,
}))

const acmeListItem: AdminSupplierListItemDto = {
  id: 1,
  name: 'ACME',
  contactPerson: 'Ms. Ada Lovelace',
  city: 'Berlin',
  country: { id: 1, name: 'Germany', countryCode: 'DE' },
  email: 'info@acme.test',
}

const acmeDetail: AdminSupplierDetailDto = {
  id: 1,
  name: 'ACME',
  title: 'Ms.',
  firstName: 'Ada',
  lastName: 'Lovelace',
  street: 'Main St',
  houseNumber: '1',
  city: 'Berlin',
  postalCode: '10115',
  countryId: 1,
  country: { id: 1, name: 'Germany', countryCode: 'DE' },
  phoneNumber1: '+49 30 1234',
  phoneNumber2: null,
  phoneNumber3: null,
  email: 'info@acme.test',
  website: 'https://acme.test',
}

function resetStoreState() {
  mocks.storeState.suppliers = []
  mocks.storeState.isLoading = false
  mocks.storeState.error = null
  mocks.storeState.fetchSuppliers.mockReset().mockResolvedValue(undefined)
  mocks.storeState.fetchSupplier.mockReset()
  mocks.storeState.createSupplier.mockReset()
  mocks.storeState.updateSupplier.mockReset()
  mocks.storeState.deleteSupplier.mockReset()
}

async function mountSuppliersView() {
  const wrapper = mount(SuppliersView, {
    attachTo: document.body,
  })

  await flushPromises()
  return wrapper
}

function bodyText() {
  return document.body.textContent ?? ''
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

async function clickBySelector(selector: string) {
  const element = document.body.querySelector(selector) as HTMLElement | null
  expect(element).toBeTruthy()
  element?.click()
  await flushPromises()
}

async function setFieldValue(selector: string, value: string) {
  const field = document.body.querySelector(selector) as
    | HTMLInputElement
    | HTMLTextAreaElement
    | null
  expect(field).toBeTruthy()
  if (!field) {
    return
  }

  field.value = value
  field.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

function getFieldValue(selector: string) {
  const field = document.body.querySelector(selector) as
    | HTMLInputElement
    | HTMLTextAreaElement
    | null
  expect(field).toBeTruthy()
  return field?.value
}

async function submitFieldForm(selector: string) {
  const field = document.body.querySelector(selector) as HTMLElement | null
  expect(field).toBeTruthy()
  const form = field?.closest('form')
  expect(form).toBeTruthy()
  form?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await flushPromises()
}

describe('SuppliersView', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    mocks.toast.mockReset()
    resetStoreState()
  })

  it('loads and renders suppliers', async () => {
    mocks.storeState.suppliers = [acmeListItem]

    const wrapper = await mountSuppliersView()

    expect(mocks.storeState.fetchSuppliers).toHaveBeenCalledTimes(1)
    expect(wrapper.find('h1').text()).toBe('Suppliers')
    expect(bodyText()).toContain('ACME')
    expect(bodyText()).toContain('Berlin')
  })

  it('blocks creation when the name is blank', async () => {
    mocks.storeState.suppliers = [acmeListItem]

    await mountSuppliersView()
    await clickButtonByText('Add Supplier')
    await submitFieldForm('#supplier-name')

    expect(bodyText()).toContain('Name is required.')
    expect(mocks.storeState.createSupplier).not.toHaveBeenCalled()
  })

  it('creates a supplier with a trimmed payload', async () => {
    mocks.storeState.suppliers = [acmeListItem]
    mocks.storeState.createSupplier.mockImplementation(
      async (payload: CreateAdminSupplierRequest) => ({
        ...acmeDetail,
        id: 2,
        ...payload,
      }),
    )

    await mountSuppliersView()
    await clickButtonByText('Add Supplier')
    await setFieldValue('#supplier-name', '  Globex  ')
    await setFieldValue('#supplier-city', 'Springfield')
    await submitFieldForm('#supplier-name')

    expect(mocks.storeState.createSupplier).toHaveBeenCalledWith({
      name: 'Globex',
      title: null,
      firstName: null,
      lastName: null,
      street: null,
      houseNumber: null,
      city: 'Springfield',
      postalCode: null,
      countryId: null,
      phoneNumber1: null,
      phoneNumber2: null,
      phoneNumber3: null,
      email: null,
      website: null,
    })
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Supplier created',
      description: 'Globex was saved.',
      variant: 'success',
    })
  })

  it('fetches the detail and prefills the edit dialog from the table row', async () => {
    mocks.storeState.suppliers = [acmeListItem]
    mocks.storeState.fetchSupplier.mockResolvedValue(acmeDetail)
    mocks.storeState.updateSupplier.mockImplementation(
      async (id: number, payload: CreateAdminSupplierRequest) => ({
        ...acmeDetail,
        ...payload,
      }),
    )

    await mountSuppliersView()
    await clickBySelector('[aria-label="Edit supplier ACME"]')

    expect(mocks.storeState.fetchSupplier).toHaveBeenCalledWith(1)
    expect(getFieldValue('#supplier-name')).toBe('ACME')
    expect(getFieldValue('#supplier-city')).toBe('Berlin')

    await setFieldValue('#supplier-city', 'Hamburg')
    await submitFieldForm('#supplier-name')

    expect(mocks.storeState.updateSupplier).toHaveBeenCalledWith(
      1,
      expect.objectContaining({ name: 'ACME', city: 'Hamburg', countryId: 1 }),
    )
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Supplier saved',
      description: 'ACME was saved.',
      variant: 'success',
    })
  })

  it('closes the dialog with a toast when the supplier no longer exists', async () => {
    mocks.storeState.suppliers = [acmeListItem]
    mocks.storeState.fetchSupplier.mockRejectedValue(
      new mocks.SupplierNotFoundError('Supplier gone'),
    )

    await mountSuppliersView()
    await clickBySelector('[aria-label="Edit supplier ACME"]')

    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Supplier not found',
      description: 'Supplier gone',
      variant: 'destructive',
    })
    expect(mocks.storeState.fetchSuppliers).toHaveBeenCalledTimes(2)
  })

  it('deletes a supplier after destructive confirmation', async () => {
    mocks.storeState.suppliers = [acmeListItem]
    mocks.storeState.fetchSupplier.mockResolvedValue(acmeDetail)
    mocks.storeState.deleteSupplier.mockResolvedValue(undefined)

    await mountSuppliersView()
    await clickBySelector('[aria-label="Edit supplier ACME"]')
    await clickButtonByText('Delete Supplier')
    await clickBySelector('[data-testid="confirm-delete-supplier"]')

    expect(mocks.storeState.deleteSupplier).toHaveBeenCalledWith(1)
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Supplier deleted',
      description: 'ACME was deleted.',
      variant: 'success',
    })
  })

  it('shows a destructive error in the dialog when delete returns a conflict', async () => {
    mocks.storeState.suppliers = [acmeListItem]
    mocks.storeState.fetchSupplier.mockResolvedValue(acmeDetail)
    mocks.storeState.deleteSupplier.mockRejectedValue(new mocks.SupplierInUseError('in use'))

    await mountSuppliersView()
    await clickBySelector('[aria-label="Edit supplier ACME"]')
    await clickButtonByText('Delete Supplier')
    await clickBySelector('[data-testid="confirm-delete-supplier"]')

    expect(bodyText()).toContain('Supplier is referenced by articles and cannot be deleted')
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Supplier cannot be deleted',
      description: 'Supplier is referenced by articles and cannot be deleted',
      variant: 'destructive',
    })
  })
})
