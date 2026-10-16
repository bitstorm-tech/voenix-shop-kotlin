import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import type { AdminJob } from '@/stores/admin/fulfillment'
import ReleaseSpodJobDialog from '../ReleaseSpodJobDialog.vue'

const job = { jobId: 7, orderId: 42 } as AdminJob

function mountDialog() {
  return mount(ReleaseSpodJobDialog, { attachTo: document.body, props: { open: true, job } })
}

const element = <T extends HTMLElement>(selector: string) =>
  document.body.querySelector<T>(selector)!

const confirmButton = () => element<HTMLButtonElement>('[data-testid="confirm-release"]')

async function type(selector: string, value: string) {
  const input = element<HTMLInputElement>(selector)
  input.value = value
  input.dispatchEvent(new Event('input'))
  await flushPromises()
}

async function choose(testId: string) {
  element(`[data-testid="${testId}"]`).click()
  await flushPromises()
}

beforeEach(() => {
  document.body.innerHTML = ''
})

describe('ReleaseSpodJobDialog', () => {
  it('names the reference to search and to type', async () => {
    mountDialog()
    await flushPromises()

    expect(document.body.textContent).toContain('Release ORD-42-JOB-7')
    expect(document.body.textContent).toContain('Type ORD-42-JOB-7 to confirm')
  })

  it('stays disabled until a decision is chosen and the typed reference matches', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    expect(confirmButton().disabled).toBe(true)

    await type('#release-confirmation', 'ORD-42-JOB-7')
    expect(confirmButton().disabled).toBe(true)

    await choose('release-not-created')
    await type('#release-confirmation', 'ORD-42-JOB-8')
    expect(confirmButton().disabled).toBe(true)
    confirmButton().click()
    await flushPromises()
    expect(wrapper.emitted('confirm')).toBeUndefined()

    await type('#release-confirmation', 'ORD-42-JOB-7')
    expect(confirmButton().disabled).toBe(false)
    confirmButton().click()
    await flushPromises()
    expect(wrapper.emitted('confirm')).toEqual([
      [{ decision: 'NOT_CREATED', confirmation: 'ORD-42-JOB-7' }],
    ])
  })

  it('asks for the partner order id when exactly one order exists', async () => {
    const wrapper = mountDialog()
    await flushPromises()
    await choose('release-created')
    await type('#release-confirmation', 'ORD-42-JOB-7')
    expect(confirmButton().disabled).toBe(true)

    await type('#release-external-reference', '  spod-123 ')
    expect(confirmButton().disabled).toBe(false)
    confirmButton().click()
    await flushPromises()
    expect(wrapper.emitted('confirm')).toEqual([
      [{ decision: 'CREATED', externalReference: 'spod-123', confirmation: 'ORD-42-JOB-7' }],
    ])
  })
})
