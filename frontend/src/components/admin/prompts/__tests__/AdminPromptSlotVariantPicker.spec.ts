import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminPromptSlotVariantPicker from '../AdminPromptSlotVariantPicker.vue'
import type { AdminPromptSlotTypeDto, AdminPromptSlotVariantDto } from '@/stores/admin/promptSlots'

const mocks = vi.hoisted(() => ({
  storeState: {
    slotTypes: [] as AdminPromptSlotTypeDto[],
    slotVariants: [] as AdminPromptSlotVariantDto[],
    variantsBySlotTypeId: {} as Record<number, AdminPromptSlotVariantDto[]>,
    isLoading: false,
    error: null as string | null,
  },
}))

vi.mock('@/stores/admin/promptSlots', () => ({
  useAdminPromptSlotsStore: () => mocks.storeState,
}))

function makeSlotType(id: number, name: string, position: number): AdminPromptSlotTypeDto {
  return {
    id,
    name,
    position,
    variantCount: 0,
  }
}

function makeVariant(
  id: number,
  slotType: AdminPromptSlotTypeDto,
  name: string,
  prompt: string,
): AdminPromptSlotVariantDto {
  return {
    id,
    slotType: {
      id: slotType.id,
      name: slotType.name,
      position: slotType.position,
    },
    name,
    prompt,
    description: null,
    llm: null,
    assignedPromptCount: 0,
  }
}

const subjectSlotType = makeSlotType(1, 'Subject', 1)
const styleSlotType = makeSlotType(2, 'Style', 2)
const emptySlotType = makeSlotType(3, 'Mood', 3)

const portraitVariant = makeVariant(11, subjectSlotType, 'Portrait', 'portrait prompt')
const landscapeVariant = makeVariant(12, subjectSlotType, 'Landscape', 'landscape prompt')
const oilVariant = makeVariant(21, styleSlotType, 'Oil', 'oil prompt')

function resetStoreState() {
  mocks.storeState.slotTypes = [subjectSlotType, styleSlotType, emptySlotType]
  mocks.storeState.slotVariants = [portraitVariant, landscapeVariant, oilVariant]
  mocks.storeState.variantsBySlotTypeId = {
    1: [landscapeVariant, portraitVariant],
    2: [oilVariant],
  }
  mocks.storeState.isLoading = false
  mocks.storeState.error = null
}

async function mountPicker(modelValue: number[]) {
  const wrapper = mount(AdminPromptSlotVariantPicker, {
    props: { modelValue },
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

function queryCheckboxes() {
  return [...document.body.querySelectorAll('input[type="checkbox"]')] as HTMLInputElement[]
}

describe('AdminPromptSlotVariantPicker', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    resetStoreState()
  })

  it('renders a collapsible per slot type and skips types without variants', async () => {
    await mountPicker([])

    expect(bodyText()).toContain('Subject')
    expect(bodyText()).toContain('Style')
    expect(bodyText()).not.toContain('Mood')
  })

  it('shows the selected count badge per slot type', async () => {
    await mountPicker([11, 12])

    expect(bodyText()).toContain('2/2 selected')
    expect(bodyText()).toContain('0/1 selected')
  })

  it('starts collapsed and reveals variants after clicking the trigger', async () => {
    await mountPicker([])

    expect(bodyText()).not.toContain('Portrait')
    expect(bodyText()).not.toContain('portrait prompt')

    await clickButtonByText('Subject')

    expect(bodyText()).toContain('Portrait')
    expect(bodyText()).toContain('portrait prompt')
    expect(bodyText()).toContain('Landscape')
    expect(bodyText()).not.toContain('Oil')
  })

  it('emits update:modelValue with the added id without losing other ids', async () => {
    const wrapper = await mountPicker([21])

    await clickButtonByText('Subject')

    const checkboxes = queryCheckboxes()
    expect(checkboxes).toHaveLength(2)
    // Variants are rendered in store order: Landscape (12) first, Portrait (11) second.
    checkboxes[1]?.click()
    await flushPromises()

    expect(wrapper.emitted('update:modelValue')).toEqual([[[21, 11]]])
  })

  it('emits update:modelValue without the removed id', async () => {
    const wrapper = await mountPicker([11, 21])

    await clickButtonByText('Subject')

    const checkboxes = queryCheckboxes()
    const checked = checkboxes.find((checkbox) => checkbox.checked)
    expect(checked).toBeTruthy()
    checked?.click()
    await flushPromises()

    expect(wrapper.emitted('update:modelValue')).toEqual([[[21]]])
  })

  it('renders the loading state', async () => {
    mocks.storeState.isLoading = true

    await mountPicker([])

    expect(bodyText()).toContain('Loading prompt slots...')
  })

  it('renders the error state', async () => {
    mocks.storeState.error = 'HTTP error 500'

    await mountPicker([])

    expect(bodyText()).toContain('Failed to load prompt slots. HTTP error 500')
  })

  it('renders an empty hint when no slot type has variants', async () => {
    mocks.storeState.variantsBySlotTypeId = {}

    await mountPicker([])

    expect(bodyText()).toContain('No prompt slot variants available yet.')
  })
})
