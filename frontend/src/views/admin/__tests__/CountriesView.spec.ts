import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CountriesView from '@/views/admin/CountriesView.vue'
import AdminCountryDialog from '@/components/admin/countries/AdminCountryDialog.vue'
import { useAdminCountriesStore } from '@/stores/admin/countries'

const toastMock = vi.hoisted(() => vi.fn())

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({
    toast: toastMock,
  }),
}))

const stubs = {
  AdminPageHeader: {
    props: ['title'],
    template: '<div><h1>{{ title }}</h1><slot name="actions" /></div>',
  },
  Alert: { template: '<div role="alert"><slot /></div>' },
  Badge: { template: '<span><slot /></span>' },
  Button: {
    props: ['disabled', 'type'],
    template: '<button v-bind="$attrs" :type="type" :disabled="disabled"><slot /></button>',
  },
  Card: { template: '<section><slot /></section>' },
  ConfirmDeleteDialog: true,
  Dialog: {
    props: ['open'],
    template: '<div v-if="open"><slot /></div>',
  },
  DialogContent: { template: '<div><slot /></div>' },
  DialogFooter: { template: '<div><slot /></div>' },
  DialogHeader: { template: '<div><slot /></div>' },
  DialogTitle: { template: '<h2><slot /></h2>' },
  FormField: {
    props: ['label', 'error'],
    template: '<div><label>{{ label }}</label><slot /><p v-if="error">{{ error }}</p></div>',
  },
  Input: {
    props: ['modelValue', 'id'],
    emits: ['update:modelValue'],
    template:
      '<input :id="id" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Table: { template: '<table><slot /></table>' },
  TableBody: { template: '<tbody><slot /></tbody>' },
  TableCell: { template: '<td><slot /></td>' },
  TableHead: { template: '<th><slot /></th>' },
  TableHeader: { template: '<thead><slot /></thead>' },
  TableRow: { template: '<tr><slot /></tr>' },
}

describe('CountriesView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    toastMock.mockReset()
  })

  it('renders countries with formatted shipping costs and thresholds and saves a new country', async () => {
    const store = useAdminCountriesStore()
    vi.spyOn(store, 'fetchAll').mockImplementation(async () => {
      store.countries = [
        {
          id: 1,
          name: 'Germany',
          countryCode: 'DE',
          shippingCostCents: 490,
          freeShippingThresholdCents: 5000,
        },
        {
          id: 2,
          name: 'Austria',
          countryCode: 'AT',
          shippingCostCents: 1190,
          freeShippingThresholdCents: null,
        },
      ]
    })
    const createCountry = vi.spyOn(store, 'createCountry').mockResolvedValue({
      id: 3,
      name: 'Denmark',
      countryCode: 'DK',
      shippingCostCents: 1290,
      freeShippingThresholdCents: null,
    })

    const wrapper = mount(CountriesView, { global: { stubs } })
    await flushPromises()

    expect(wrapper.text()).toContain('Germany')
    expect(wrapper.text()).toContain('4,90')
    expect(wrapper.text()).toContain('50,00')
    expect(wrapper.text()).toContain('Austria')
    expect(wrapper.text()).toContain('11,90')

    const newButton = wrapper.findAll('button').find((b) => b.text().includes('New Country'))
    await newButton?.trigger('click')
    await flushPromises()

    await wrapper.get('#country-name').setValue('Denmark')
    await wrapper.get('#country-code').setValue('dk')
    await wrapper.get('#country-shipping-cost').setValue('12,90')
    await wrapper.get('#country-free-threshold').setValue('')
    await wrapper.findComponent(AdminCountryDialog).get('form').trigger('submit')
    await flushPromises()

    expect(createCountry).toHaveBeenCalledWith({
      name: 'Denmark',
      countryCode: 'DK',
      shippingCostCents: 1290,
      freeShippingThresholdCents: null,
    })
  })
})
