import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import AddressForm from '@/components/shop/AddressForm.vue'
import { createEmptyAddress, type Address } from '@/stores/shop/checkout'
import type { Country } from '@/stores/shop/countries'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

const countries: Country[] = [
  { name: 'Germany', countryCode: 'DE', dialCode: '+49' },
  { name: 'France', countryCode: 'FR', dialCode: '+33' },
]

/** Reka's Select is a listbox; a native select keeps the same contract testable in jsdom. */
const selectStubs = {
  Select: {
    props: ['modelValue', 'disabled'],
    emits: ['update:modelValue'],
    template: `<select :disabled="disabled" :value="modelValue"
      @change="$emit('update:modelValue', $event.target.value)"><slot /></select>`,
  },
  SelectTrigger: { template: '<span v-bind="$attrs"><slot /></span>' },
  SelectContent: { template: '<slot />' },
  SelectValue: { template: '<span />' },
  SelectItem: {
    props: ['value'],
    template: '<option :value="value"><slot /></option>',
  },
}

function mountAddressForm(props: Partial<InstanceType<typeof AddressForm>['$props']> = {}) {
  return mount(AddressForm, {
    props: {
      modelValue: createEmptyAddress(),
      idPrefix: 'shipping',
      countryOptions: countries,
      ...props,
    },
    global: { stubs: selectStubs },
  })
}

function lastAddress(wrapper: ReturnType<typeof mountAddressForm>): Address {
  const lastEvent = wrapper.emitted('update:modelValue')?.at(-1)
  if (!lastEvent) {
    throw new Error('No address update was emitted')
  }
  return lastEvent[0] as Address
}

describe('AddressForm country field', () => {
  it('offers the shippable countries as a dropdown and emits the selected code', async () => {
    const wrapper = mountAddressForm()

    const select = wrapper.get('select')
    expect(wrapper.findAll('option').map((option) => option.text())).toEqual(['Germany', 'France'])
    // The label points at the trigger, which carries the field id.
    expect(wrapper.find('#shipping-country').exists()).toBe(true)

    await select.setValue('FR')

    expect(lastAddress(wrapper).country).toBe('FR')
  })

  it('disables the dropdown when the country list could not be loaded', () => {
    const wrapper = mountAddressForm({ countryOptions: [] })

    expect(wrapper.get('select').attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('option')).toHaveLength(0)
  })

  it('renders the server field error inline and links it to the field', () => {
    const wrapper = mountAddressForm({ countryError: 'We do not ship to this country' })

    const message = wrapper.get('[role="alert"]')
    expect(message.text()).toBe('We do not ship to this country')
    expect(wrapper.get('#shipping-country').attributes('aria-describedby')).toBe(
      'shipping-country-error',
    )
  })

  it('takes the billing country as free text and keeps it a two-letter uppercase code', async () => {
    const wrapper = mountAddressForm({ idPrefix: 'billing', countryMode: 'text' })

    const input = wrapper.get('input#billing-country')
    expect(wrapper.find('select#billing-country').exists()).toBe(false)

    await input.setValue('u5s')

    expect(lastAddress(wrapper).country).toBe('US')
  })

  it('does not restrict the billing country to the shippable list', async () => {
    const wrapper = mountAddressForm({
      idPrefix: 'billing',
      countryMode: 'text',
      countryOptions: [],
    })

    await wrapper.get('input#billing-country').setValue('ch')

    expect(lastAddress(wrapper).country).toBe('CH')
  })
})
