import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PromptSlotsView from '../PromptSlotsView.vue'
import type {
  AdminPromptSlotTypeDto,
  AdminPromptSlotVariantDetailDto,
  CreateAdminPromptSlotVariantRequest,
} from '@/stores/admin/promptSlots'

const mocks = vi.hoisted(() => {
  class PromptSlotTypeNotFoundError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromptSlotTypeNotFoundError'
    }
  }

  class PromptSlotTypeNameConflictError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromptSlotTypeNameConflictError'
    }
  }

  class PromptSlotTypeInUseError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromptSlotTypeInUseError'
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

  class PromptSlotVariantSlotTypeNotFoundError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromptSlotVariantSlotTypeNotFoundError'
    }
  }

  return {
    toast: vi.fn(),
    storeState: {
      slotTypes: [] as AdminPromptSlotTypeDto[],
      slotVariants: [] as AdminPromptSlotVariantDetailDto[],
      variantsBySlotTypeId: {} as Record<number, AdminPromptSlotVariantDetailDto[]>,
      isLoading: false,
      error: null as string | null,
      fetchSlotTypes: vi.fn(),
      fetchSlotVariants: vi.fn(),
      createSlotType: vi.fn(),
      updateSlotType: vi.fn(),
      deleteSlotType: vi.fn(),
      createSlotVariant: vi.fn(),
      updateSlotVariant: vi.fn(),
      deleteSlotVariant: vi.fn(),
    },
    PromptSlotTypeNotFoundError,
    PromptSlotTypeNameConflictError,
    PromptSlotTypeInUseError,
    PromptSlotVariantNotFoundError,
    PromptSlotVariantNameConflictError,
    PromptSlotVariantInUseError,
    PromptSlotVariantSlotTypeNotFoundError,
  }
})

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast: mocks.toast }),
}))

vi.mock('@/stores/admin/promptSlots', () => ({
  useAdminPromptSlotsStore: () => mocks.storeState,
  PromptSlotTypeNotFoundError: mocks.PromptSlotTypeNotFoundError,
  PromptSlotTypeNameConflictError: mocks.PromptSlotTypeNameConflictError,
  PromptSlotTypeInUseError: mocks.PromptSlotTypeInUseError,
  PromptSlotVariantNotFoundError: mocks.PromptSlotVariantNotFoundError,
  PromptSlotVariantNameConflictError: mocks.PromptSlotVariantNameConflictError,
  PromptSlotVariantInUseError: mocks.PromptSlotVariantInUseError,
  PromptSlotVariantSlotTypeNotFoundError: mocks.PromptSlotVariantSlotTypeNotFoundError,
}))

const subjectSlotType: AdminPromptSlotTypeDto = {
  id: 1,
  name: 'Subject',
  position: 1,
  variantCount: 1,
}

const styleSlotType: AdminPromptSlotTypeDto = {
  id: 2,
  name: 'Style',
  position: 2,
  variantCount: 0,
}

const portraitVariant: AdminPromptSlotVariantDetailDto = {
  id: 11,
  slotType: {
    id: 1,
    name: 'Subject',
    position: 1,
  },
  name: 'Portrait',
  prompt: 'portrait prompt',
  description: null,
  llm: null,
  assignedPromptCount: 0,
}

const assignedOilVariant: AdminPromptSlotVariantDetailDto = {
  id: 12,
  slotType: {
    id: 2,
    name: 'Style',
    position: 2,
  },
  name: 'Oil',
  prompt: 'oil prompt',
  description: 'Painterly style',
  llm: 'gpt-image',
  assignedPromptCount: 2,
}

function resetStoreState() {
  mocks.storeState.slotTypes = []
  mocks.storeState.slotVariants = []
  mocks.storeState.variantsBySlotTypeId = {}
  mocks.storeState.isLoading = false
  mocks.storeState.error = null
  mocks.storeState.fetchSlotTypes.mockReset().mockResolvedValue(undefined)
  mocks.storeState.fetchSlotVariants.mockReset().mockResolvedValue(undefined)
  mocks.storeState.createSlotType.mockReset()
  mocks.storeState.updateSlotType.mockReset()
  mocks.storeState.deleteSlotType.mockReset()
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

async function toggleSlotTypeVariants(slotTypeName: string, action: 'Show' | 'Hide' = 'Show') {
  await clickBySelector(`[aria-label="${action} variants for ${slotTypeName}"]`)
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

  it('loads and renders prompt slot type cards initially collapsed', async () => {
    mocks.storeState.slotTypes = [subjectSlotType, styleSlotType]
    mocks.storeState.slotVariants = [portraitVariant]
    mocks.storeState.variantsBySlotTypeId = {
      1: [portraitVariant],
      2: [],
    }

    const wrapper = await mountPromptSlotsView()

    expect(mocks.storeState.fetchSlotTypes).toHaveBeenCalledTimes(1)
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

  it('toggles variant visibility per slot type card', async () => {
    mocks.storeState.slotTypes = [subjectSlotType, styleSlotType]
    mocks.storeState.slotVariants = [portraitVariant]
    mocks.storeState.variantsBySlotTypeId = {
      1: [portraitVariant],
      2: [],
    }

    await mountPromptSlotsView()

    expect(bodyText()).not.toContain('Portrait')

    await toggleSlotTypeVariants('Subject')

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

    await toggleSlotTypeVariants('Style')

    expect(bodyText()).toContain('No variants yet.')

    await toggleSlotTypeVariants('Subject', 'Hide')

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

    expect(bodyText()).toContain('No prompt slot types found.')
  })

  it('blocks slot type creation when the name is blank', async () => {
    mocks.storeState.slotTypes = [subjectSlotType]
    mocks.storeState.variantsBySlotTypeId = { 1: [] }

    await mountPromptSlotsView()
    await clickButtonByText('New Slot Type')
    await submitFieldForm('#prompt-slot-type-name')

    expect(bodyText()).toContain('Name is required.')
    expect(mocks.storeState.createSlotType).not.toHaveBeenCalled()
  })

  it('creates a slot type with a trimmed payload', async () => {
    mocks.storeState.slotTypes = [subjectSlotType]
    mocks.storeState.variantsBySlotTypeId = { 1: [] }
    mocks.storeState.createSlotType.mockImplementation(async (payload: { name: string }) => ({
      id: 3,
      name: payload.name,
      position: 3,
      variantCount: 0,
    }))

    await mountPromptSlotsView()
    await clickButtonByText('New Slot Type')
    await setFieldValue('#prompt-slot-type-name', '  Background  ')
    await submitFieldForm('#prompt-slot-type-name')

    expect(mocks.storeState.createSlotType).toHaveBeenCalledWith({ name: 'Background' })
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Prompt slot type created',
      description: 'Background was saved.',
      variant: 'success',
    })
  })

  it('maps duplicate slot type names into the slot type dialog', async () => {
    mocks.storeState.slotTypes = [subjectSlotType]
    mocks.storeState.variantsBySlotTypeId = { 1: [] }
    mocks.storeState.createSlotType.mockRejectedValue(
      new mocks.PromptSlotTypeNameConflictError('Prompt slot type name already exists'),
    )

    await mountPromptSlotsView()
    await clickButtonByText('New Slot Type')
    await setFieldValue('#prompt-slot-type-name', 'Subject')
    await submitFieldForm('#prompt-slot-type-name')

    expect(bodyText()).toContain('Prompt slot type name already exists')
  })

  it('preselects the group slot type when creating a variant and trims payloads', async () => {
    mocks.storeState.slotTypes = [subjectSlotType]
    mocks.storeState.variantsBySlotTypeId = { 1: [] }
    mocks.storeState.createSlotVariant.mockImplementation(
      async (payload: CreateAdminPromptSlotVariantRequest) => ({
        id: 13,
        slotType: {
          id: payload.slotTypeId,
          name: 'Subject',
          position: 1,
        },
        name: payload.name,
        prompt: payload.prompt,
        description: payload.description ?? null,
        llm: payload.llm ?? null,
        assignedPromptCount: 0,
      }),
    )

    await mountPromptSlotsView()
    await clickBySelector('[aria-label="Add variant to Subject"]')
    expect(bodyText()).toContain('Subject (#1, position 1)')
    await setFieldValue('#prompt-slot-variant-name', '  Landscape  ')
    await setFieldValue('#prompt-slot-variant-prompt', '  landscape prompt  ')
    await setFieldValue('#prompt-slot-variant-description', '   ')
    await setFieldValue('#prompt-slot-variant-llm', '   ')
    await submitFieldForm('#prompt-slot-variant-name')

    expect(mocks.storeState.createSlotVariant).toHaveBeenCalledWith({
      slotTypeId: 1,
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
    mocks.storeState.slotTypes = [subjectSlotType]
    mocks.storeState.variantsBySlotTypeId = { 1: [] }

    await mountPromptSlotsView()
    await clickBySelector('[aria-label="Add variant to Subject"]')
    await submitFieldForm('#prompt-slot-variant-name')

    expect(bodyText()).toContain('Name is required.')
    expect(bodyText()).toContain('Prompt is required.')
    expect(mocks.storeState.createSlotVariant).not.toHaveBeenCalled()
  })

  it('disables slot type deletion when loaded variants exist', async () => {
    mocks.storeState.slotTypes = [subjectSlotType]
    mocks.storeState.slotVariants = [portraitVariant]
    mocks.storeState.variantsBySlotTypeId = { 1: [portraitVariant] }

    await mountPromptSlotsView()
    await clickBySelector('[aria-label="Delete prompt slot type Subject"]')

    const deleteButton = queryButtonByText('Delete Slot Type')
    expect(deleteButton?.disabled).toBe(true)
    expect(bodyText()).toContain('Remove variants first.')
    expect(mocks.storeState.deleteSlotType).not.toHaveBeenCalled()
  })

  it('disables variant deletion when it is assigned to prompts', async () => {
    mocks.storeState.slotTypes = [styleSlotType]
    mocks.storeState.slotVariants = [assignedOilVariant]
    mocks.storeState.variantsBySlotTypeId = { 2: [assignedOilVariant] }

    await mountPromptSlotsView()
    await toggleSlotTypeVariants('Style')
    await clickBySelector('[aria-label="Delete prompt slot variant Oil"]')

    const deleteButton = queryButtonByText('Delete Variant')
    expect(deleteButton?.disabled).toBe(true)
    expect(bodyText()).toContain('Remove this variant from prompts first.')
    expect(mocks.storeState.deleteSlotVariant).not.toHaveBeenCalled()
  })

  it('deletes a variant after destructive confirmation', async () => {
    mocks.storeState.slotTypes = [subjectSlotType]
    mocks.storeState.slotVariants = [portraitVariant]
    mocks.storeState.variantsBySlotTypeId = { 1: [portraitVariant] }
    mocks.storeState.deleteSlotVariant.mockResolvedValue(undefined)

    await mountPromptSlotsView()
    await toggleSlotTypeVariants('Subject')
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
