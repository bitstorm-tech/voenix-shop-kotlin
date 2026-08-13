import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import type { SupplierJob } from '@/stores/supplier/jobs'
import ShipJobDialog from '../ShipJobDialog.vue'

const job: SupplierJob = {
  jobId: 5,
  orderId: 42,
  orderDate: '2026-08-13',
  customerFirstName: 'Ada',
  customerLastName: 'Lovelace',
  shippingStreet: 'Hauptstrasse',
  shippingHouseNumber: '7',
  shippingPostalCode: '10115',
  shippingCity: 'Berlin',
  shippingCountry: 'Germany',
  items: [],
  pdfAvailable: true,
  shippedAt: null,
  shippingCarrier: null,
  trackingNumber: null,
}

function mountDialog(props: Record<string, unknown> = {}) {
  return mount(ShipJobDialog, {
    attachTo: document.body,
    props: { open: true, job, ...props },
  })
}

function bodyText() {
  return document.body.textContent ?? ''
}

function confirmButton() {
  return document.body.querySelector<HTMLButtonElement>('[data-testid="confirm-ship"]')
}

beforeEach(() => {
  document.body.innerHTML = ''
})

describe('ShipJobDialog', () => {
  it('states that the action is irreversible and that the customer is notified', async () => {
    mountDialog()
    await flushPromises()

    expect(bodyText()).toContain('Ship ORD-42')
    expect(bodyText()).toContain('cannot be undone')
    expect(bodyText()).toContain('customer is notified')
  })

  it('reports an empty form as a shipment without carrier and number', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    confirmButton()!.click()
    await flushPromises()

    expect(wrapper.emitted('confirm')).toEqual([[{ carrier: null, trackingNumber: null }]])
  })

  it('does not submit twice while the first attempt is still running', async () => {
    const wrapper = mountDialog()
    await flushPromises()

    confirmButton()!.click()
    confirmButton()!.click()
    await flushPromises()

    expect(wrapper.emitted('confirm')).toHaveLength(1)
    expect(confirmButton()!.disabled).toBe(true)
  })

  it('renders an API field error at its field and lets the supplier try again', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    confirmButton()!.click()
    await flushPromises()

    await wrapper.setProps({
      fieldErrors: { trackingNumber: ['TrackingNumber must be at most 128 characters'] },
    })
    await flushPromises()

    expect(bodyText()).toContain('TrackingNumber must be at most 128 characters')
    expect(confirmButton()!.disabled).toBe(false)
  })

  it('shows a general error that belongs to no field', async () => {
    mountDialog({ generalError: 'The shipment could not be reported.' })
    await flushPromises()

    expect(bodyText()).toContain('The shipment could not be reported.')
  })
})
