import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { ApiError } from '@/lib/api'
import type { AdminSupplierDto } from '@/stores/admin/suppliers'
import {
  type SupplierLogin,
  SupplierLoginEmailTakenError,
  SupplierLoginInvitationNotDeliveredError,
} from '@/stores/admin/supplierLogins'
import AdminSupplierLoginsDialog from '../AdminSupplierLoginsDialog.vue'

const storeState = reactive({
  logins: [] as SupplierLogin[],
  loadedSupplierId: null as number | null,
  isLoading: false,
  error: null as Error | null,
  isCreating: false,
  deletingUserId: null as number | null,
  fetchLogins: vi.fn(),
  createLogin: vi.fn(),
  deleteLogin: vi.fn(),
})

vi.mock('@/stores/admin/supplierLogins', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/stores/admin/supplierLogins')>()
  return { ...actual, useAdminSupplierLoginsStore: () => storeState }
})

const supplier = { id: 3, name: 'Acme' } as AdminSupplierDto

const login: SupplierLogin = {
  userId: 7,
  email: 'packing@acme.example',
  supplierId: 3,
  createdAt: '2026-08-13T09:30:00Z',
}

/** The dialog content is teleported to the document body, so the assertions read it there. */
function dialogText(): string {
  return document.body.textContent ?? ''
}

async function mountDialog() {
  const wrapper = mount(AdminSupplierLoginsDialog, {
    props: { open: false, supplier },
    attachTo: document.body,
  })
  // Opening is what loads the list, exactly as a click on the row action does.
  await wrapper.setProps({ open: true })
  await flushPromises()
  return wrapper
}

async function createLoginThroughForm(email = 'packing@acme.example') {
  const field = document.body.querySelector<HTMLInputElement>('#supplier-login-email')
  expect(field).toBeTruthy()
  field!.value = email
  field!.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()

  document.body
    .querySelector('form')
    ?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await flushPromises()
}

beforeEach(() => {
  document.body.innerHTML = ''
  storeState.logins = [login]
  storeState.isLoading = false
  storeState.error = null
  storeState.isCreating = false
  storeState.deletingUserId = null
  storeState.fetchLogins.mockReset().mockResolvedValue([login])
  storeState.createLogin.mockReset().mockResolvedValue(login)
  storeState.deleteLogin.mockReset().mockResolvedValue(undefined)
})

describe('AdminSupplierLoginsDialog', () => {
  it('loads and lists the logins of the supplier it was opened for', async () => {
    await mountDialog()

    expect(storeState.fetchLogins).toHaveBeenCalledWith(3)
    expect(dialogText()).toContain('Logins for Acme')
    expect(dialogText()).toContain('packing@acme.example')
    expect(dialogText()).toContain('2026-08-13')
  })

  it('creates a login from the entered address', async () => {
    await mountDialog()

    await createLoginThroughForm('new@acme.example')

    expect(storeState.createLogin).toHaveBeenCalledWith(3, 'new@acme.example')
  })

  it('shows a taken address at the form instead of as a failed create', async () => {
    storeState.createLogin.mockRejectedValue(new SupplierLoginEmailTakenError('Email exists'))
    await mountDialog()

    await createLoginThroughForm()

    expect(dialogText()).toContain('already belongs to an account')
  })

  it('shows a validation 400 at the e-mail field', async () => {
    storeState.createLogin.mockRejectedValue(
      new ApiError('Validation failed', 400, {
        message: 'Validation failed',
        errors: { email: ['Email is not valid'] },
      }),
    )
    await mountDialog()

    await createLoginThroughForm()

    expect(dialogText()).toContain('Email is not valid')
  })

  it('says the login exists and the invitation did not arrive on a 502, and reloads the list', async () => {
    storeState.createLogin.mockRejectedValue(
      new SupplierLoginInvitationNotDeliveredError('Not delivered'),
    )
    await mountDialog()
    storeState.fetchLogins.mockClear()

    await createLoginThroughForm()

    expect(dialogText()).toContain(
      'The login was created, but its invitation e-mail could not be delivered',
    )
    expect(dialogText()).toContain('Forgot password')
    expect(storeState.fetchLogins).toHaveBeenCalledWith(3)
  })

  it('confirms a deletion, says that access is revoked immediately, and deletes', async () => {
    await mountDialog()

    const deleteButton = document.body.querySelector<HTMLButtonElement>(
      '[aria-label="Delete login packing@acme.example"]',
    )
    expect(deleteButton).toBeTruthy()
    deleteButton!.click()
    await flushPromises()

    expect(dialogText()).toContain('revokes its access immediately')

    document.body
      .querySelector<HTMLButtonElement>('[data-testid="confirm-delete-supplier-login"]')
      ?.click()
    await flushPromises()

    expect(storeState.deleteLogin).toHaveBeenCalledWith(7)
  })
})
