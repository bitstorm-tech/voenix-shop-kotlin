import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import AdminPromptsTable from '../AdminPromptsTable.vue'
import type { AdminPromptListItemDto } from '@/stores/admin/prompts'
import { createDragEvent } from '@/testing/dragEvent'

function prompt(overrides: Partial<AdminPromptListItemDto> = {}): AdminPromptListItemDto {
  return {
    id: 1,
    position: 1,
    title: 'First',
    categoryId: 1,
    categoryName: 'People',
    subcategoryId: null,
    subcategoryName: null,
    exampleImageFilename: null,
    llm: null,
    active: true,
    archived: false,
    price: null,
    ...overrides,
  }
}

function mountTable(props: {
  prompts: AdminPromptListItemDto[]
  reordering?: boolean
  reorderDisabled?: boolean
}) {
  return mount(AdminPromptsTable, { props })
}

function pointerEvent(
  type: string,
  {
    x = 0,
    y = 0,
    pointerType = 'touch',
  }: { x?: number; y?: number; pointerType?: 'touch' | 'pen' } = {},
) {
  const event = new Event(type, { bubbles: true, cancelable: true }) as Event & {
    button: number
    clientX: number
    clientY: number
    isPrimary: boolean
    pointerId: number
    pointerType: string
  }
  Object.defineProperties(event, {
    button: { value: 0 },
    clientX: { value: x },
    clientY: { value: y },
    isPrimary: { value: true },
    pointerId: { value: 7 },
    pointerType: { value: pointerType },
  })
  return event
}

function installPointerEnvironment(handle: Element, target: Element) {
  const setPointerCapture = vi.fn()
  const releasePointerCapture = vi.fn()
  Object.defineProperties(handle, {
    setPointerCapture: { configurable: true, value: setPointerCapture },
    releasePointerCapture: { configurable: true, value: releasePointerCapture },
  })
  const originalElementFromPoint = Object.getOwnPropertyDescriptor(document, 'elementFromPoint')
  Object.defineProperty(document, 'elementFromPoint', {
    configurable: true,
    value: vi.fn(() => target),
  })

  return {
    setPointerCapture,
    releasePointerCapture,
    restore() {
      if (originalElementFromPoint) {
        Object.defineProperty(document, 'elementFromPoint', originalElementFromPoint)
      } else {
        Reflect.deleteProperty(document, 'elementFromPoint')
      }
    },
  }
}

describe('AdminPromptsTable', () => {
  it('renders a hover-preview thumbnail with the resized example image', () => {
    const wrapper = mountTable({
      prompts: [prompt({ exampleImageFilename: 'style.png' })],
    })

    const thumbnail = wrapper.get('[data-testid="example-image-thumbnail"]')

    expect(thumbnail.attributes('src')).toBe(
      '/api/images/public/80/prompt-example-images/style.png',
    )
    expect(thumbnail.attributes('alt')).toBe('First')
    expect(thumbnail.attributes('data-state')).toBe('closed')
    expect(wrapper.find('[data-testid="example-image-placeholder"]').exists()).toBe(false)
  })

  it('renders the placeholder without a hover-preview trigger when no example image is set', () => {
    const wrapper = mountTable({ prompts: [prompt()] })

    expect(wrapper.find('[data-testid="example-image-placeholder"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="example-image-thumbnail"]').exists()).toBe(false)
    expect(wrapper.find('[data-state]').exists()).toBe(false)
  })

  it('falls back to the placeholder when the thumbnail fails to load', async () => {
    const wrapper = mountTable({
      prompts: [prompt({ exampleImageFilename: 'broken.png' })],
    })

    await wrapper.get('[data-testid="example-image-thumbnail"]').trigger('error')

    expect(wrapper.find('[data-testid="example-image-thumbnail"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="example-image-placeholder"]').exists()).toBe(true)
  })

  it('renders the display names the flat row carries and the small price projection', () => {
    const wrapper = mountTable({
      prompts: [
        prompt({
          categoryName: 'People',
          subcategoryId: 20,
          subcategoryName: 'Portraits',
          price: {
            salesTotalNet: 1000,
            salesTotalGross: 1190,
            salesTotalTax: 190,
            salesVatRatePercent: 19,
          },
        }),
      ],
    })

    const cells = wrapper.findAll('td').map((cell) => cell.text())

    expect(cells).toContain('People')
    expect(cells).toContain('Portraits')
    expect(cells.some((cell) => cell.includes('11,90'))).toBe(true)
  })

  it('renders a dash for a prompt without a subcategory or a price', () => {
    const wrapper = mountTable({ prompts: [prompt()] })

    const cells = wrapper.findAll('td').map((cell) => cell.text())

    expect(cells.filter((cell) => cell === '—')).toHaveLength(2)
  })

  it('renders the image column instead of the LLM column', () => {
    const wrapper = mountTable({
      prompts: [prompt({ llm: 'gpt-image-1', exampleImageFilename: 'style.png' })],
    })

    const headers = wrapper.findAll('th').map((header) => header.text())

    expect(headers).toEqual([
      'Order',
      'Image',
      'Title',
      'Category',
      'Subcategory',
      'Price',
      'Status',
      'Actions',
    ])
    expect(wrapper.text()).not.toContain('gpt-image-1')
  })

  it('renders authoritative positions and emits a mouse reorder from the drag handle', async () => {
    const prompts = [prompt(), prompt({ id: 2, position: 2, title: 'Second', active: false })]
    const original = structuredClone(prompts)
    const wrapper = mountTable({ prompts })
    const handle = wrapper.get('[aria-label="Drag prompt Second"]')
    const target = wrapper.get('[data-testid="prompt-drop-1"]')

    handle.element.dispatchEvent(createDragEvent('dragstart'))
    target.element.dispatchEvent(createDragEvent('dragover'))
    await flushPromises()

    expect(wrapper.get('[data-testid="prompt-drop-2"]').classes()).toContain('opacity-50')
    expect(wrapper.find('[data-testid="prompt-drop-skeleton"]').exists()).toBe(true)

    target.element.dispatchEvent(createDragEvent('drop'))
    await flushPromises()

    expect(wrapper.emitted('reorderPrompts')).toEqual([[2, 1]])
    expect(prompts).toEqual(original)
    expect(wrapper.text()).toContain('1')
    expect(wrapper.text()).toContain('2')
  })

  it('keeps edit separate and suppresses same-prompt drops', async () => {
    const wrapper = mountTable({ prompts: [prompt()] })
    const handle = wrapper.get('[aria-label="Drag prompt First"]')
    const row = wrapper.get('[data-testid="prompt-drop-1"]')

    handle.element.dispatchEvent(createDragEvent('dragstart'))
    row.element.dispatchEvent(createDragEvent('drop'))
    await wrapper.get('[aria-label="Edit prompt First"]').trigger('click')

    expect(wrapper.emitted('reorderPrompts')).toBeUndefined()
    expect(wrapper.emitted('edit')?.[0]?.[0]).toMatchObject({ id: 1 })
  })

  it('renders an after indicator and emits a downward mouse reorder', async () => {
    const wrapper = mountTable({
      prompts: [prompt(), prompt({ id: 2, position: 2, title: 'Second' })],
    })
    const handle = wrapper.get('[aria-label="Drag prompt First"]')
    const target = wrapper.get('[data-testid="prompt-drop-2"]')

    handle.element.dispatchEvent(createDragEvent('dragstart'))
    target.element.dispatchEvent(createDragEvent('dragover'))
    await flushPromises()

    const html = wrapper.html()
    expect(html.indexOf('data-testid="prompt-drop-2"')).toBeLessThan(
      html.indexOf('data-testid="prompt-drop-skeleton"'),
    )

    target.element.dispatchEvent(createDragEvent('drop'))
    await flushPromises()
    expect(wrapper.emitted('reorderPrompts')).toEqual([[1, 2]])
  })

  it('disables reorder controls and announces saving state', () => {
    const wrapper = mountTable({ prompts: [prompt()], reordering: true })

    expect(wrapper.get('[aria-label="Drag prompt First"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[role="status"]').text()).toBe('Saving prompt order...')
  })

  it.each(['touch', 'pen'] as const)(
    'reorders through a %s pointer after the movement threshold',
    async (pointerType) => {
      const wrapper = mountTable({
        prompts: [prompt(), prompt({ id: 2, position: 2, title: 'Second' })],
      })
      const handle = wrapper.get('[aria-label="Drag prompt Second"]')
      const target = wrapper.get('[data-testid="prompt-drop-1"]')
      const pointerEnvironment = installPointerEnvironment(handle.element, target.element)

      try {
        handle.element.dispatchEvent(pointerEvent('pointerdown', { pointerType }))
        handle.element.dispatchEvent(pointerEvent('pointermove', { x: 10, y: 20, pointerType }))
        await flushPromises()
        expect(wrapper.find('[data-testid="prompt-drop-skeleton"]').exists()).toBe(true)

        handle.element.dispatchEvent(pointerEvent('pointerup', { x: 10, y: 20, pointerType }))
        await flushPromises()

        expect(pointerEnvironment.setPointerCapture).toHaveBeenCalledWith(7)
        expect(pointerEnvironment.releasePointerCapture).toHaveBeenCalledWith(7)
        expect(wrapper.emitted('reorderPrompts')).toEqual([[2, 1]])
      } finally {
        pointerEnvironment.restore()
      }
    },
  )

  it('does not start a pointer reorder below the movement threshold', async () => {
    const wrapper = mountTable({
      prompts: [prompt(), prompt({ id: 2, position: 2, title: 'Second' })],
    })
    const handle = wrapper.get('[aria-label="Drag prompt Second"]')
    const target = wrapper.get('[data-testid="prompt-drop-1"]')
    const pointerEnvironment = installPointerEnvironment(handle.element, target.element)

    try {
      handle.element.dispatchEvent(pointerEvent('pointerdown'))
      handle.element.dispatchEvent(pointerEvent('pointermove', { x: 4, y: 4 }))
      handle.element.dispatchEvent(pointerEvent('pointerup', { x: 4, y: 4 }))
      await flushPromises()

      expect(wrapper.find('[data-testid="prompt-drop-skeleton"]').exists()).toBe(false)
      expect(wrapper.emitted('reorderPrompts')).toBeUndefined()
    } finally {
      pointerEnvironment.restore()
    }
  })

  it.each(['pointercancel', 'lostpointercapture'])(
    'clears pointer reorder state on %s',
    async (terminalEvent) => {
      const wrapper = mountTable({
        prompts: [prompt(), prompt({ id: 2, position: 2, title: 'Second' })],
      })
      const handle = wrapper.get('[aria-label="Drag prompt Second"]')
      const target = wrapper.get('[data-testid="prompt-drop-1"]')
      const pointerEnvironment = installPointerEnvironment(handle.element, target.element)

      try {
        handle.element.dispatchEvent(pointerEvent('pointerdown'))
        handle.element.dispatchEvent(pointerEvent('pointermove', { x: 10, y: 20 }))
        await flushPromises()
        expect(wrapper.find('[data-testid="prompt-drop-skeleton"]').exists()).toBe(true)

        handle.element.dispatchEvent(pointerEvent(terminalEvent))
        await flushPromises()

        expect(wrapper.find('[data-testid="prompt-drop-skeleton"]').exists()).toBe(false)
        expect(wrapper.emitted('reorderPrompts')).toBeUndefined()
      } finally {
        pointerEnvironment.restore()
      }
    },
  )
})
