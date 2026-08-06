import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AdminPriceEditor from '../AdminPriceEditor.vue'
import type { AdminPriceDto } from '@/stores/admin/prices'

const standardVat = { id: 1, name: 'Standard', percent: 19 }

const price: AdminPriceDto = {
  id: null,
  purchaseVatId: 1,
  purchaseCalculationMode: 'NET',
  purchaseActiveRow: 'COST',
  purchasePriceInputCents: 0,
  purchaseCostInputCents: 0,
  purchaseCostPercent: 0,
  salesVatId: 1,
  salesCalculationMode: 'GROSS',
  salesActiveRow: 'TOTAL',
  salesMarginInputCents: 0,
  salesMarginPercent: 0,
  salesTotalInputCents: 0,
  purchaseVat: standardVat,
  purchasePrice: { net: 0, tax: 0, gross: 0 },
  purchaseCost: { net: 0, tax: 0, gross: 0 },
  calculatedPurchaseCostPercent: 0,
  purchaseTotal: { net: 0, tax: 0, gross: 0 },
  salesVat: standardVat,
  salesMargin: { net: 0, tax: 0, gross: 0 },
  calculatedSalesMarginPercent: 0,
  salesTotal: { net: 0, tax: 0, gross: 0 },
}

function mountEditor() {
  return mount(AdminPriceEditor, {
    props: {
      description: 'Preise werden vom Backend berechnet; geänderte Eingaben bleiben sichtbar.',
      form: {
        purchaseVatId: 1,
        purchaseCalculationMode: 'NET',
        purchaseActiveRow: 'COST',
        purchasePriceInputCents: 0,
        purchaseCostInputCents: 0,
        purchaseCostPercent: 0,
        salesVatId: 1,
        salesCalculationMode: 'GROSS',
        salesActiveRow: 'TOTAL',
        salesMarginInputCents: 0,
        salesMarginPercent: 0,
        salesTotalInputCents: 0,
      },
      fields: {
        purchasePrice: '0,00',
        purchaseCost: '0,00',
        purchaseCostPercent: '0',
        salesMargin: '0,00',
        salesMarginPercent: '0',
        salesTotal: '0,00',
      },
      price,
      vatOptions: [standardVat],
      isLoading: false,
      isCalculating: false,
      setupError: null,
      error: null,
      inputError: null,
    },
  })
}

describe('AdminPriceEditor', () => {
  it('renders entity-neutral pricing copy and all purchase and sales sections', () => {
    const wrapper = mountEditor()

    expect(wrapper.text()).toContain(
      'Preise werden vom Backend berechnet; geänderte Eingaben bleiben sichtbar.',
    )
    expect(wrapper.text()).not.toContain('Artikelpreise')
    expect(wrapper.text()).toContain('Einkauf')
    expect(wrapper.text()).toContain('Verkauf')
    expect(wrapper.find('[data-testid="price-purchase-price-net"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="price-sales-total-gross"]').exists()).toBe(true)
  })

  it('emits committed field edits through its typed public event contract', async () => {
    const wrapper = mountEditor()
    const purchasePriceInput = wrapper.find('[data-testid="price-purchase-price-net"]')

    await purchasePriceInput.setValue('12,34')
    await purchasePriceInput.trigger('blur')

    expect(wrapper.emitted('purchasePriceChange')).toEqual([['12,34']])
  })
})
