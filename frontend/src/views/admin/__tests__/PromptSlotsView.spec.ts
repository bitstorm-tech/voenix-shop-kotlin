import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PromptSlotsView from '../PromptSlotsView.vue'
import type {
  AdminPromptSlotDto,
  AdminPromptSlotVariantDto,
  CreateAdminPromptSlotVariantRequest,
} from '@/stores/admin/promptSlots'

const mocks = vi.hoisted(() => {
  class PromptSlotNotFoundError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromptSlotNotFoundError'
    }
  }

  class PromptSlotNameConflictError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromptSlotNameConflictError'
    }
  }

  class PromptSlotInUseError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromptSlotInUseError'
    }
  }

  class PromptSlotVariantNotFoundError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromptSlotVariantNotFoundError'
    }
  }

  class PromptSlotVariantNameConflictError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromptSlotVariantNameConflictError'
    }
  }

  class PromptSlotVariantInUseError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromptSlotVariantInUseError'
    }
  }

  class PromptSlotValidationError extends Error {
    readonly fieldErrors: Record<string, string[]>

    constructor(message: string, fieldErrors: Record<string, string[]>) {
      super(message)
      this.name = 'PromptSlotValidationError'
      this.fieldErrors = fieldErrors
    }

    fieldError(field: string): string | null {
      return this.fieldErrors[field]?.[0] ?? null
    }
  }

  return {
    toast: vi.fn(),
    storeState: {
      slots: [] as AdminPromptSlotDto[],
      slotVariants: [] as AdminPromptSlotVariantDto[],
      variantsBySlotId: {} as Record<number, AdminPromptSlotVariantDto[]>,
      isLoading: false,
      error: null as string | null,
      fetchSlots: vi.fn(),
      fetchSlotVariants: vi.fn(),
      createSlot: vi.fn(),
      updateSlot: vi.fn(),
      deleteSlot: vi.fn(),
      createSlotVariant: vi.fn(),
      updateSlotVariant: vi.fn(),
      deleteSlotVariant: vi.fn(),
    },
    PromptSlotNotFoundError,
    PromptSlotNameConflictError,
    PromptSlotInUseError,
    PromptSlotValidationError,
    PromptSlotVariantNotFoundError,
    PromptSlotVariantNameConflictError,
    PromptSlotVariantInUseError,
  }
})

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast: mocks.toast }),
}))

vi.mock('@/stores/admin/promptSlots', () => ({
  useAdminPromptSlotsStore: () => mocks.storeState,
  PromptSlotNotFoundError: mocks.PromptSlotNotFoundError,
  PromptSlotNameConflictError: mocks.PromptSlotNameConflictError,
  PromptSlotInUseError: mocks.PromptSlotInUseError,
  PromptSlotValidationError: mocks.PromptSlotValidationError,
  PromptSlotVariantNotFoundError: mocks.PromptSlotVariantNotFoundError,
  PromptSlotVariantNameConflictError: mocks.PromptSlotVariantNameConflictError,
  PromptSlotVariantInUseError: mocks.PromptSlotVariantInUseError,
}))

const subjectSlot: AdminPromptSlotDto = {
  id: 1,
  name: 'Subject',
  position: 1,
  variantCount: 1,
}

const styleSlot: AdminPromptSlotDto = {
  id: 2,
  name: 'Style',
  position: 2,
  variantCount: 0,
}

const portraitVariant: AdminPromptSlotVariantDto = {
  id: 11,
  slotId: 1,
  slotName: 'Subject',
  name: 'Portrait',
  prompt: 'portrait prompt',
  description: null,
  llm: null,
  assignedPromptCount: 0,
}

const assignedOilVariant: AdminPromptSlotVariantDto = {
  id: 12,
  slotId: 2,
  slotName: 'Style',
  name: 'Oil',
  prompt: 'oil prompt',
  description: 'Painterly style',
  llm: 'gpt-image',
  assignedPromptCount: 2,
}

function resetStoreState() {
  mocks.storeState.slots = []
  mocks.storeState.slotVariants = []
  mocks.storeState.variantsBySlotId = {}
  mocks.storeState.isLoading = false
  mocks.storeState.error = null
  mocks.storeState.fetchSlots.mockReset().mockResolvedValue(undefined)
  mocks.storeState.fetchSlotVariants.mockReset().mockResolvedValue(undefined)
  mocks.storeState.createSlot.mockReset()
  mocks.storeState.updateSlot.mockReset()
  mocks.storeState.deleteSlot.mockReset()
  mocks.storeState.createSlotVariant.mockReset()
  mocks.storeState.updateSlotVariant.mockReset()
  mocks.storeState.deleteSlotVariant.mockReset()
}

async function mountPromptSlotsView() {
  const wrapper = mount(PromptSlotsView, {
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

async function clickBySelector(selector: string) {
  const element = document.body.querySelector(selector) as HTMLElement | null
  expect(element).toBeTruthy()
  element?.click()
  await flushPromises()
}

async function toggleSlotVariants(slotName: string, action: 'Show' | 'Hide' = 'Show') {
  await clickBySelector(`[aria-label="${action} variants for ${slotName}"]`)
}

async function setFieldValue(selector: string, value: string) {
  const field = document.body.querySelector(selector) as
    | HTMLInputElement
    | HTMLTextAreaElement
    | null
  expect(field).toBeTruthy()
  if (!field) {
    return
  }

  field.value = value
  field.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises()
}

async function submitFieldForm(selector: string) {
  const field = document.body.querySelector(selector) as HTMLElement | null
  expect(field).toBeTruthy()
  const form = field?.closest('form')
  expect(form).toBeTruthy()
  form?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  await flushPromises()
}

describe('PromptSlotsView', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    mocks.toast.mockReset()
    resetStoreState()
  })

  it('loads and renders prompt slot cards initially collapsed', async () => {
    mocks.storeState.slots = [subjectSlot, styleSlot]
    mocks.storeState.slotVariants = [portraitVariant]
    mocks.storeState.variantsBySlotId = {
      1: [portraitVariant],
      2: [],
    }

    const wrapper = await mountPromptSlotsView()

    expect(mocks.storeState.fetchSlots).toHaveBeenCalledTimes(1)
    expect(mocks.storeState.fetchSlotVariants).toHaveBeenCalledTimes(1)
    expect(wrapper.find('h1').text()).toBe('Prompt Slots')
    expect(bodyText()).toContain('Subject')
    const subjectHeading = [...document.body.querySelectorAll('h2')].find(
      (heading) => heading.textContent === 'Subject',
    )
    expect(subjectHeading?.parentElement?.textContent).not.toContain('#1')
    expect(subjectHeading?.parentElement?.textContent).not.toContain('Position 1')
    expect(bodyText()).toContain('1 variant')
    expect(bodyText()).not.toContain('Portrait')
    expect(bodyText()).not.toContain('portrait prompt')
    expect(bodyText()).toContain('Style')
    expect(bodyText()).toContain('0 variants')
    expect(bodyText()).not.toContain('No variants yet.')
  })

  it('toggles variant visibility per slot card', async () => {
    mocks.storeState.slots = [subjectSlot, styleSlot]
    mocks.storeState.slotVariants = [portraitVariant]
    mocks.storeState.variantsBySlotId = {
      1: [portraitVariant],
      2: [],
    }

    await mountPromptSlotsView()

    expect(bodyText()).not.toContain('Portrait')

    await toggleSlotVariants('Subject')

    expect(bodyText()).toContain('Portrait')
    expect(bodyText()).toContain('portrait prompt')
    expect([...document.body.querySelectorAll('th')].map((header) => header.textContent)).toEqual([
      'Name',
      'Prompt',
      'Description',
      'LLM',
      'Assigned',
      'Actions',
    ])
    expect(bodyText()).not.toContain('#11')

    await toggleSlotVariants('Style')

    expect(bodyText()).toContain('No variants yet.')

    await toggleSlotVariants('Subject', 'Hide')

    expect(bodyText()).not.toContain('Portrait')
    expect(bodyText()).toContain('No variants yet.')
  })

  it('renders load errors and the empty state', async () => {
    mocks.storeState.error = 'HTTP error 500'

    await mountPromptSlotsView()

    expect(bodyText()).toContain('Failed to load prompt slots. HTTP error 500')

    document.body.innerHTML = ''
    resetStoreState()

    await mountPromptSlotsView()

    expect(bodyText()).toContain('No prompt slots found.')
  })

  it('blocks slot creation when the name is blank', async () => {
    mocks.storeState.slots = [subjectSlot]
    mocks.storeState.variantsBySlotId = { 1: [] }

    await mountPromptSlotsView()
    await clickButtonByText('New Slot')
    await submitFieldForm('#prompt-slot-name')

    expect(bodyText()).toContain('Name is required.')
    expect(mocks.storeState.createSlot).not.toHaveBeenCalled()
  })

  it('creates a slot with a trimmed payload', async () => {
    mocks.storeState.slots = [subjectSlot]
    mocks.storeState.variantsBySlotId = { 1: [] }
    mocks.storeState.createSlot.mockImplementation(async (payload: { name: string }) => ({
      id: 3,
      name: payload.name,
      position: 3,
      variantCount: 0,
    }))

    await mountPromptSlotsView()
    await clickButtonByText('New Slot')
    await setFieldValue('#prompt-slot-name', '  Background  ')
    await submitFieldForm('#prompt-slot-name')

    expect(mocks.storeState.createSlot).toHaveBeenCalledWith({ name: 'Background' })
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Prompt slot created',
      description: 'Background was saved.',
      variant: 'success',
    })
  })

  it('refuses a slot name that only differs in case before it is sent', async () => {
    mocks.storeState.slots = [subjectSlot]
    mocks.storeState.variantsBySlotId = { 1: [] }

    await mountPromptSlotsView()
    await clickButtonByText('New Slot')
    await setFieldValue('#prompt-slot-name', 'subject')
    await submitFieldForm('#prompt-slot-name')

    expect(bodyText()).toContain('A prompt slot with this name already exists.')
    expect(mocks.storeState.createSlot).not.toHaveBeenCalled()
  })

  it('maps the 409 of a slot write into the slot dialog', async () => {
    mocks.storeState.slots = [subjectSlot]
    mocks.storeState.variantsBySlotId = { 1: [] }
    mocks.storeState.createSlot.mockRejectedValue(
      new mocks.PromptSlotNameConflictError('Prompt slot name already exists'),
    )

    await mountPromptSlotsView()
    await clickButtonByText('New Slot')
    await setFieldValue('#prompt-slot-name', 'Background')
    await submitFieldForm('#prompt-slot-name')

    expect(bodyText()).toContain('Prompt slot name already exists')
  })

  it('refuses a variant name already taken in another slot before it is sent', async () => {
    mocks.storeState.slots = [subjectSlot, styleSlot]
    mocks.storeState.slotVariants = [assignedOilVariant]
    mocks.storeState.variantsBySlotId = { 1: [], 2: [assignedOilVariant] }

    await mountPromptSlotsView()
    await clickBySelector('[aria-label="Add variant to Subject"]')
    await setFieldValue('#prompt-slot-variant-name', 'oil')
    await setFieldValue('#prompt-slot-variant-prompt', 'a prompt')
    await submitFieldForm('#prompt-slot-variant-name')

    expect(bodyText()).toContain('A prompt slot variant with this name already exists.')
    expect(mocks.storeState.createSlotVariant).not.toHaveBeenCalled()
  })

  it('shows a 400 field error on slotId in the variant dialog', async () => {
    mocks.storeState.slots = [subjectSlot]
    mocks.storeState.variantsBySlotId = { 1: [] }
    mocks.storeState.createSlotVariant.mockRejectedValue(
      new mocks.PromptSlotValidationError('Validation failed', {
        slotId: ['Prompt slot does not exist'],
      }),
    )

    await mountPromptSlotsView()
    await clickBySelector('[aria-label="Add variant to Subject"]')
    await setFieldValue('#prompt-slot-variant-name', 'Landscape')
    await setFieldValue('#prompt-slot-variant-prompt', 'landscape prompt')
    await submitFieldForm('#prompt-slot-variant-name')

    expect(bodyText()).toContain('Prompt slot does not exist')
  })

  it('preselects the group slot when creating a variant and trims payloads', async () => {
    mocks.storeState.slots = [subjectSlot]
    mocks.storeState.variantsBySlotId = { 1: [] }
    mocks.storeState.createSlotVariant.mockImplementation(
      async (payload: CreateAdminPromptSlotVariantRequest) => ({
        id: 13,
        slotId: payload.slotId,
        slotName: 'Subject',
        name: payload.name,
        prompt: payload.prompt,
        description: payload.description ?? null,
        llm: payload.llm ?? null,
        assignedPromptCount: 0,
      }),
    )

    await mountPromptSlotsView()
    await clickBySelector('[aria-label="Add variant to Subject"]')
    expect(bodyText()).toContain('Subject (#1)')
    await setFieldValue('#prompt-slot-variant-name', '  Landscape  ')
    await setFieldValue('#prompt-slot-variant-prompt', '  landscape prompt  ')
    await setFieldValue('#prompt-slot-variant-description', '   ')
    await setFieldValue('#prompt-slot-variant-llm', '   ')
    await submitFieldForm('#prompt-slot-variant-name')

    expect(mocks.storeState.createSlotVariant).toHaveBeenCalledWith({
      slotId: 1,
      name: 'Landscape',
      prompt: 'landscape prompt',
      description: null,
      llm: null,
    })
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Prompt slot variant created',
      description: 'Landscape was saved.',
      variant: 'success',
    })
  })

  it('blocks variant creation when required fields are blank', async () => {
    mocks.storeState.slots = [subjectSlot]
    mocks.storeState.variantsBySlotId = { 1: [] }

    await mountPromptSlotsView()
    await clickBySelector('[aria-label="Add variant to Subject"]')
    await submitFieldForm('#prompt-slot-variant-name')

    expect(bodyText()).toContain('Name is required.')
    expect(bodyText()).toContain('Prompt is required.')
    expect(mocks.storeState.createSlotVariant).not.toHaveBeenCalled()
  })

  it('disables slot deletion when loaded variants exist', async () => {
    mocks.storeState.slots = [subjectSlot]
    mocks.storeState.slotVariants = [portraitVariant]
    mocks.storeState.variantsBySlotId = { 1: [portraitVariant] }

    await mountPromptSlotsView()
    await clickBySelector('[aria-label="Delete prompt slot Subject"]')

    const deleteButton = queryButtonByText('Delete Slot')
    expect(deleteButton?.disabled).toBe(true)
    expect(bodyText()).toContain('Remove variants first.')
    expect(mocks.storeState.deleteSlot).not.toHaveBeenCalled()
  })

  it('disables variant deletion when it is assigned to prompts', async () => {
    mocks.storeState.slots = [styleSlot]
    mocks.storeState.slotVariants = [assignedOilVariant]
    mocks.storeState.variantsBySlotId = { 2: [assignedOilVariant] }

    await mountPromptSlotsView()
    await toggleSlotVariants('Style')
    await clickBySelector('[aria-label="Delete prompt slot variant Oil"]')

    const deleteButton = queryButtonByText('Delete Variant')
    expect(deleteButton?.disabled).toBe(true)
    expect(bodyText()).toContain('Remove this variant from prompts first.')
    expect(mocks.storeState.deleteSlotVariant).not.toHaveBeenCalled()
  })

  it('deletes a variant after destructive confirmation', async () => {
    mocks.storeState.slots = [subjectSlot]
    mocks.storeState.slotVariants = [portraitVariant]
    mocks.storeState.variantsBySlotId = { 1: [portraitVariant] }
    mocks.storeState.deleteSlotVariant.mockResolvedValue(undefined)

    await mountPromptSlotsView()
    await toggleSlotVariants('Subject')
    await clickBySelector('[aria-label="Delete prompt slot variant Portrait"]')
    await clickButtonByText('Delete Variant')
    await clickBySelector('[data-testid="confirm-delete-prompt-slot-variant"]')

    expect(mocks.storeState.deleteSlotVariant).toHaveBeenCalledWith(11)
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Prompt slot variant deleted',
      description: 'Portrait was deleted.',
      variant: 'success',
    })
  })
})
