import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import VatView from '../VatView.vue'
import type { AdminVatDto, CreateAdminVatRequest, UpdateAdminVatRequest } from '@/stores/admin/vat'

const mocks = vi.hoisted(() => {
  class VatNotFoundError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'VatNotFoundError'
    }
  }

  class VatNameConflictError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'VatNameConflictError'
    }
  }

  return {
    toast: vi.fn(),
    storeState: {
      vats: [] as AdminVatDto[],
      isLoading: false,
      error: null as string | null,
      fetchAll: vi.fn(),
      createVat: vi.fn(),
      updateVat: vi.fn(),
      deleteVat: vi.fn(),
    },
    VatNotFoundError,
    VatNameConflictError,
  }
})

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast: mocks.toast }),
}))

vi.mock('@/stores/admin/vat', () => ({
  useAdminVatStore: () => mocks.storeState,
  VatNotFoundError: mocks.VatNotFoundError,
  VatNameConflictError: mocks.VatNameConflictError,
}))

const standardVat: AdminVatDto = {
  id: 1,
  name: 'Standard',
  percent: 19,
  description: 'Standard rate',
  isDefault: true,
}

const reducedVat: AdminVatDto = {
  id: 2,
  name: 'Reduced',
  percent: 7,
  description: 'Reduced rate',
  isDefault: false,
}

function resetStoreState() {
  mocks.storeState.vats = []
  mocks.storeState.isLoading = false
  mocks.storeState.error = null
  mocks.storeState.fetchAll.mockReset().mockResolvedValue(undefined)
  mocks.storeState.createVat.mockReset()
  mocks.storeState.updateVat.mockReset()
  mocks.storeState.deleteVat.mockReset()
}

async function mountVatView() {
  const wrapper = mount(VatView, {
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

describe('VatView', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    mocks.toast.mockReset()
    resetStoreState()
  })

  it('loads and renders VAT entries', async () => {
    mocks.storeState.vats = [standardVat, reducedVat]

    const wrapper = await mountVatView()

    expect(mocks.storeState.fetchAll).toHaveBeenCalledTimes(1)
    expect(wrapper.find('h1').text()).toBe('VAT')
    expect(bodyText()).toContain('Standard')
    expect(bodyText()).toContain('19%')
    expect(bodyText()).toContain('Reduced')
  })

  it('blocks creation when required fields are blank', async () => {
    mocks.storeState.vats = [standardVat]

    await mountVatView()
    await clickButtonByText('New VAT')
    await submitFieldForm('#vat-name')

    expect(bodyText()).toContain('Name is required.')
    expect(bodyText()).toContain('Percent is required.')
    expect(mocks.storeState.createVat).not.toHaveBeenCalled()
  })

  it('prevents saving VAT percent values above the upper bound', async () => {
    mocks.storeState.vats = [standardVat]

    await mountVatView()
    await clickButtonByText('New VAT')
    await setFieldValue('#vat-name', 'Typo VAT')
    await setFieldValue('#vat-percent', '101')
    await submitFieldForm('#vat-name')

    expect(bodyText()).toContain('Percent must be at most 100.')
    expect(mocks.storeState.createVat).not.toHaveBeenCalled()
    expect(mocks.storeState.updateVat).not.toHaveBeenCalled()
  })

  it('creates a VAT entry with a trimmed payload', async () => {
    mocks.storeState.vats = [standardVat]
    mocks.storeState.createVat.mockImplementation(async (payload: CreateAdminVatRequest) => ({
      ...standardVat,
      id: 3,
      ...payload,
    }))

    await mountVatView()
    await clickButtonByText('New VAT')
    await setFieldValue('#vat-name', '  Zero  ')
    await setFieldValue('#vat-percent', '0')
    await setFieldValue('#vat-description', '   ')
    await submitFieldForm('#vat-name')

    expect(mocks.storeState.createVat).toHaveBeenCalledWith({
      name: 'Zero',
      percent: 0,
      description: null,
      isDefault: false,
    })
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'VAT created',
      description: 'Zero (0%) was saved.',
      variant: 'success',
    })
  })

  it('maps duplicate VAT names into the dialog', async () => {
    mocks.storeState.vats = [standardVat]
    mocks.storeState.createVat.mockRejectedValue(
      new mocks.VatNameConflictError('VAT name already exists'),
    )

    await mountVatView()
    await clickButtonByText('New VAT')
    await setFieldValue('#vat-name', 'Standard')
    await setFieldValue('#vat-percent', '19')
    await submitFieldForm('#vat-name')

    expect(bodyText()).toContain('VAT name already exists')
    expect(mocks.toast).not.toHaveBeenCalled()
  })

  it('opens the edit dialog prefilled from the table row and updates the entry', async () => {
    mocks.storeState.vats = [standardVat, reducedVat]
    mocks.storeState.updateVat.mockImplementation(
      async (id: number, payload: UpdateAdminVatRequest) => ({
        ...reducedVat,
        ...payload,
      }),
    )

    await mountVatView()
    await clickBySelector('[aria-label="Edit VAT Reduced"]')

    expect(getFieldValue('#vat-name')).toBe('Reduced')
    expect(getFieldValue('#vat-percent')).toBe('7')

    await setFieldValue('#vat-percent', '5')
    await submitFieldForm('#vat-name')

    expect(mocks.storeState.updateVat).toHaveBeenCalledWith(2, {
      name: 'Reduced',
      percent: 5,
      description: 'Reduced rate',
      isDefault: false,
    })
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'VAT saved',
      description: 'Reduced (5%) was saved.',
      variant: 'success',
    })
  })

  it('deletes a VAT entry after destructive confirmation', async () => {
    mocks.storeState.vats = [standardVat]
    mocks.storeState.deleteVat.mockResolvedValue(undefined)

    await mountVatView()
    await clickBySelector('[aria-label="Edit VAT Standard"]')
    await clickButtonByText('Delete VAT')
    await clickBySelector('[data-testid="confirm-delete-vat"]')

    expect(mocks.storeState.deleteVat).toHaveBeenCalledWith(1)
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'VAT deleted',
      description: 'Standard was deleted.',
      variant: 'success',
    })
  })
})
