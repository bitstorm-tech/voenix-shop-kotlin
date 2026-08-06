import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import AdminVatDialog from '../AdminVatDialog.vue'
import type { AdminVatDto, CreateAdminVatRequest } from '@/stores/admin/vat'

const standardVat: AdminVatDto = {
  id: 1,
  name: 'Standard',
  percent: 19,
  description: 'Standard rate',
  isDefault: true,
}

async function mountDialog(vat: AdminVatDto | null = null) {
  const wrapper = mount(AdminVatDialog, {
    attachTo: document.body,
    props: {
      open: true,
      vat,
    },
  })

  await flushPromises()
  return wrapper
}

async function setFieldValue(selector: string, value: string) {
  const field = document.body.querySelector(selector) as HTMLInputElement | null
  expect(field).toBeTruthy()
  if (!field) {
    return
  }

  field.value = value
  field.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

async function setCheckbox(selector: string, checked: boolean) {
  const input = document.body.querySelector(selector) as HTMLInputElement | null
  expect(input).toBeTruthy()
  if (!input) {
    return
  }

  input.checked = checked
  input.dispatchEvent(new Event('change', { bubbles: true }))
  await flushPromises()
}

async function submitDialogForm() {
  const form = document.body.querySelector('form')
  expect(form).toBeTruthy()
  form?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await flushPromises()
}

function savedPayload(saveEvents: unknown[][] | undefined): CreateAdminVatRequest {
  const payload = saveEvents?.[0]?.[0]
  expect(payload).toBeDefined()
  return payload as CreateAdminVatRequest
}

describe('AdminVatDialog', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
  })

  it('saves the default flag from the checkbox-card option row', async () => {
    const wrapper = await mountDialog()

    await setFieldValue('#vat-name', '  Standard  ')
    await setFieldValue('#vat-percent', '19')
    await setCheckbox('#vat-is-default', true)
    await submitDialogForm()

    expect(savedPayload(wrapper.emitted('save'))).toEqual({
      name: 'Standard',
      percent: 19,
      description: null,
      isDefault: true,
    })
  })

  it('prefills the default checkbox card when editing', async () => {
    await mountDialog(standardVat)

    const input = document.body.querySelector('#vat-is-default') as HTMLInputElement | null

    expect(input?.checked).toBe(true)
  })
})
