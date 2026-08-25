import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AdminPriceEditor from '../AdminPriceEditor.vue'
import type { AdminPriceFormState } from '@/lib/adminPrice'
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
  regularSalesMargin: { net: 500, tax: 0, gross: 500 },
  calculatedRegularSalesMarginPercent: 0,
  regularSalesTotal: { net: 1672, tax: 318, gross: 1990 },
  discount: { discountType: 'PERCENTAGE', discountValue: 20 },
  salesDiscount: { net: 334, tax: 64, gross: 398 },
  salesMargin: { net: 102, tax: 0, gross: 102 },
  calculatedSalesMarginPercent: 6.5,
  salesTotal: { net: 1338, tax: 254, gross: 1592 },
}

function mountEditor(formOverrides: Partial<AdminPriceFormState> = {}) {
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
        discountType: 'PERCENTAGE',
        discountValue: 20,
        ...formOverrides,
      },
      fields: {
        purchasePrice: '0,00',
        purchaseCost: '0,00',
        purchaseCostPercent: '0',
        salesMargin: '0,00',
        salesMarginPercent: '0',
        salesTotal: '0,00',
        discountValue: '20',
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

  it('shows the discount preview of the calculated price', () => {
    const wrapper = mountEditor()

    expect(wrapper.text()).toContain('Discount')
    expect(wrapper.find('[data-testid="price-discount-regular-total-gross"]').text()).toContain(
      '19,90',
    )
    expect(wrapper.find('[data-testid="price-discount-saving-gross"]').text()).toContain('3,98')
    expect(wrapper.find('[data-testid="price-discount-effective-total-gross"]').text()).toContain(
      '15,92',
    )
    expect(
      wrapper.find('[data-testid="price-discount-effective-margin-percent"]').text(),
    ).toContain('6,5')
    expect(wrapper.find('[data-testid="price-discount-value"]').exists()).toBe(true)
  })

  it('hides the discount value field when no discount kind is selected', () => {
    const wrapper = mountEditor({ discountType: null })

    expect(wrapper.find('[data-testid="price-discount-value"]').exists()).toBe(false)
  })

  it('emits committed field edits through its typed public event contract', async () => {
    const wrapper = mountEditor()
    const purchasePriceInput = wrapper.find('[data-testid="price-purchase-price-net"]')

    await purchasePriceInput.setValue('12,34')
    await purchasePriceInput.trigger('blur')

    expect(wrapper.emitted('purchasePriceChange')).toEqual([['12,34']])
  })
})
