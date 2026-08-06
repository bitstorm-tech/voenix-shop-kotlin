import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PromptCategoriesView from '../PromptCategoriesView.vue'
import type {
  AdminPromptCategoryDto,
  AdminPromptSubcategoryDetailDto,
  CreateAdminPromptSubcategoryRequest,
} from '@/stores/admin/promptCategories'

const mocks = vi.hoisted(() => {
  class PromptCategoryNotFoundError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromptCategoryNotFoundError'
    }
  }

  class PromptCategoryNameConflictError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromptCategoryNameConflictError'
    }
  }

  class PromptCategoryInUseError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromptCategoryInUseError'
    }
  }

  class PromptCategoryOrderConflictError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromptCategoryOrderConflictError'
    }
  }

  class PromptSubcategoryNotFoundError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromptSubcategoryNotFoundError'
    }
  }

  class PromptSubcategoryNameConflictError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromptSubcategoryNameConflictError'
    }
  }

  class PromptSubcategoryInUseError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromptSubcategoryInUseError'
    }
  }

  class PromptSubcategoryCategoryNotFoundError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromptSubcategoryCategoryNotFoundError'
    }
  }

  class PromptSubcategoryOrderConflictError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'PromptSubcategoryOrderConflictError'
    }
  }

  return {
    toast: vi.fn(),
    storeState: {
      categories: [] as AdminPromptCategoryDto[],
      subcategories: [] as AdminPromptSubcategoryDetailDto[],
      subcategoriesByCategoryId: {} as Record<number, AdminPromptSubcategoryDetailDto[]>,
      isLoading: false,
      error: null as string | null,
      fetchCategories: vi.fn(),
      fetchSubcategories: vi.fn(),
      createCategory: vi.fn(),
      updateCategory: vi.fn(),
      deleteCategory: vi.fn(),
      reorderCategories: vi.fn(),
      createSubcategory: vi.fn(),
      updateSubcategory: vi.fn(),
      deleteSubcategory: vi.fn(),
      reorderSubcategories: vi.fn(),
    },
    PromptCategoryNotFoundError,
    PromptCategoryNameConflictError,
    PromptCategoryInUseError,
    PromptCategoryOrderConflictError,
    PromptSubcategoryNotFoundError,
    PromptSubcategoryNameConflictError,
    PromptSubcategoryInUseError,
    PromptSubcategoryCategoryNotFoundError,
    PromptSubcategoryOrderConflictError,
  }
})

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast: mocks.toast }),
}))

vi.mock('@/stores/admin/promptCategories', () => ({
  useAdminPromptCategoriesStore: () => mocks.storeState,
  PromptCategoryNotFoundError: mocks.PromptCategoryNotFoundError,
  PromptCategoryNameConflictError: mocks.PromptCategoryNameConflictError,
  PromptCategoryInUseError: mocks.PromptCategoryInUseError,
  PromptCategoryOrderConflictError: mocks.PromptCategoryOrderConflictError,
  PromptSubcategoryNotFoundError: mocks.PromptSubcategoryNotFoundError,
  PromptSubcategoryNameConflictError: mocks.PromptSubcategoryNameConflictError,
  PromptSubcategoryInUseError: mocks.PromptSubcategoryInUseError,
  PromptSubcategoryCategoryNotFoundError: mocks.PromptSubcategoryCategoryNotFoundError,
  PromptSubcategoryOrderConflictError: mocks.PromptSubcategoryOrderConflictError,
}))

const portraitsCategory: AdminPromptCategoryDto = {
  id: 1,
  name: 'Portraits',
  position: 1,
  active: false,
}

const seasonalCategory: AdminPromptCategoryDto = {
  id: 2,
  name: 'Seasonal',
  position: 2,
  active: true,
}

const minimalistSubcategory: AdminPromptSubcategoryDetailDto = {
  id: 11,
  promptCategory: portraitsCategory,
  name: 'Minimalist',
  description: null,
  position: 1,
  active: true,
}

const studioSubcategory: AdminPromptSubcategoryDetailDto = {
  id: 12,
  promptCategory: portraitsCategory,
  name: 'Studio',
  description: null,
  position: 2,
  active: false,
}

function resetStoreState() {
  mocks.storeState.categories = []
  mocks.storeState.subcategories = []
  mocks.storeState.subcategoriesByCategoryId = {}
  mocks.storeState.isLoading = false
  mocks.storeState.error = null
  mocks.storeState.fetchCategories.mockReset().mockResolvedValue(undefined)
  mocks.storeState.fetchSubcategories.mockReset().mockResolvedValue(undefined)
  mocks.storeState.createCategory.mockReset()
  mocks.storeState.updateCategory.mockReset()
  mocks.storeState.deleteCategory.mockReset()
  mocks.storeState.reorderCategories.mockReset().mockResolvedValue(undefined)
  mocks.storeState.createSubcategory.mockReset()
  mocks.storeState.updateSubcategory.mockReset()
  mocks.storeState.deleteSubcategory.mockReset()
  mocks.storeState.reorderSubcategories.mockReset().mockResolvedValue(undefined)
}

async function mountPromptCategoriesView() {
  const wrapper = mount(PromptCategoriesView, {
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

async function toggleCategorySubcategories(categoryName: string, action: 'Show' | 'Hide' = 'Show') {
  await clickBySelector(`[aria-label="${action} subcategories for ${categoryName}"]`)
}

function createDragEvent(type: string) {
  const event = new Event(type, { bubbles: true, cancelable: true }) as Event & {
    dataTransfer: {
      effectAllowed: string
      dropEffect: string
      setData: ReturnType<typeof vi.fn>
    }
  }
  Object.defineProperty(event, 'dataTransfer', {
    value: {
      effectAllowed: '',
      dropEffect: '',
      setData: vi.fn(),
    },
  })
  return event
}

async function dragCategoryOverTarget(handleLabel: string, targetSelector: string) {
  const handle = document.body.querySelector(`[aria-label="${handleLabel}"]`) as HTMLElement | null
  const target = document.body.querySelector(targetSelector) as HTMLElement | null
  expect(handle).toBeTruthy()
  expect(target).toBeTruthy()

  handle?.dispatchEvent(createDragEvent('dragstart'))
  target?.dispatchEvent(createDragEvent('dragover'))
  await flushPromises()

  return { handle, target }
}

async function dragCategoryOntoTarget(handleLabel: string, targetSelector: string) {
  const { target } = await dragCategoryOverTarget(handleLabel, targetSelector)
  target?.dispatchEvent(createDragEvent('drop'))
  await flushPromises()
}

async function dragSubcategoryOverTarget(handleLabel: string, targetSelector: string) {
  const handle = document.body.querySelector(`[aria-label="${handleLabel}"]`) as HTMLElement | null
  const target = document.body.querySelector(targetSelector) as HTMLElement | null
  expect(handle).toBeTruthy()
  expect(target).toBeTruthy()

  handle?.dispatchEvent(createDragEvent('dragstart'))
  target?.dispatchEvent(createDragEvent('dragover'))
  await flushPromises()

  return { handle, target }
}

async function dragSubcategoryOntoTarget(handleLabel: string, targetSelector: string) {
  const { target } = await dragSubcategoryOverTarget(handleLabel, targetSelector)
  target?.dispatchEvent(createDragEvent('drop'))
  await flushPromises()
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

async function setCheckbox(selector: string, checked: boolean) {
  const input = document.body.querySelector(selector) as HTMLInputElement | null
  expect(input).toBeTruthy()
  if (!input) return
  input.checked = checked
  input.dispatchEvent(new Event('change', { bubbles: true }))
  await flushPromises()
}

describe('PromptCategoriesView', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    mocks.toast.mockReset()
    resetStoreState()
  })

  it('loads and renders prompt category cards initially collapsed', async () => {
    mocks.storeState.categories = [portraitsCategory, seasonalCategory]
    mocks.storeState.subcategories = [minimalistSubcategory]
    mocks.storeState.subcategoriesByCategoryId = {
      1: [minimalistSubcategory],
      2: [],
    }

    const wrapper = await mountPromptCategoriesView()

    expect(mocks.storeState.fetchCategories).toHaveBeenCalledTimes(1)
    expect(mocks.storeState.fetchSubcategories).toHaveBeenCalledTimes(1)
    expect(wrapper.find('h1').text()).toBe('Prompt Categories')
    expect(bodyText()).toContain('Portraits')
    expect(wrapper.find('[data-testid="prompt-category-drop-1"]').text()).toContain('Inactive')
    expect(bodyText()).not.toContain('#1')
    expect(bodyText()).not.toContain('Position 1')
    expect(bodyText()).toContain('1 subcategory')
    expect(bodyText()).toContain('Seasonal')
    expect(bodyText()).toContain('0 subcategories')
    expect(bodyText()).not.toContain('Minimalist')
    expect(bodyText()).not.toContain('No subcategories in this prompt category yet.')
  })

  it('toggles subcategory visibility per category card', async () => {
    mocks.storeState.categories = [portraitsCategory, seasonalCategory]
    mocks.storeState.subcategories = [minimalistSubcategory]
    mocks.storeState.subcategoriesByCategoryId = {
      1: [minimalistSubcategory],
      2: [],
    }

    await mountPromptCategoriesView()

    expect(bodyText()).not.toContain('Minimalist')

    await toggleCategorySubcategories('Portraits')

    expect(bodyText()).toContain('Minimalist')
    const childRow = document.body.querySelector('[data-testid="prompt-subcategory-drop-11"]')
    expect(childRow?.textContent).toContain('Active')
    expect(bodyText()).toContain('—')

    await toggleCategorySubcategories('Seasonal')

    expect(bodyText()).toContain('No subcategories in this prompt category yet.')

    await toggleCategorySubcategories('Portraits', 'Hide')

    expect(bodyText()).not.toContain('Minimalist')
    expect(bodyText()).toContain('No subcategories in this prompt category yet.')
  })

  it('renders load errors and the empty state', async () => {
    mocks.storeState.error = 'HTTP error 500'

    await mountPromptCategoriesView()

    expect(bodyText()).toContain('Failed to load prompt categories. HTTP error 500')

    document.body.innerHTML = ''
    resetStoreState()

    await mountPromptCategoriesView()

    expect(bodyText()).toContain('No prompt categories found.')
  })

  it('blocks category creation when the name is blank', async () => {
    mocks.storeState.categories = [portraitsCategory]
    mocks.storeState.subcategoriesByCategoryId = { 1: [] }

    await mountPromptCategoriesView()
    await clickButtonByText('New Category')
    const activeCheckbox = document.body.querySelector(
      '#prompt-category-active',
    ) as HTMLInputElement | null
    expect(activeCheckbox?.checked).toBe(true)
    await setCheckbox('#prompt-category-active', false)
    await submitFieldForm('#prompt-category-name')

    expect(bodyText()).toContain('Name is required.')
    expect(mocks.storeState.createCategory).not.toHaveBeenCalled()
  })

  it('creates a category with a trimmed payload', async () => {
    mocks.storeState.categories = [portraitsCategory]
    mocks.storeState.subcategoriesByCategoryId = { 1: [] }
    mocks.storeState.createCategory.mockImplementation(async (payload: { name: string }) => ({
      id: 3,
      name: payload.name,
      position: 2,
    }))

    await mountPromptCategoriesView()
    await clickButtonByText('New Category')
    await setCheckbox('#prompt-category-active', false)
    await setFieldValue('#prompt-category-name', '  Characters  ')
    await submitFieldForm('#prompt-category-name')

    expect(mocks.storeState.createCategory).toHaveBeenCalledWith({
      name: 'Characters',
      active: false,
    })
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Prompt category created',
      description: 'Characters was saved.',
      variant: 'success',
    })
  })

  it('maps duplicate category names into the category dialog', async () => {
    mocks.storeState.categories = [portraitsCategory]
    mocks.storeState.subcategoriesByCategoryId = { 1: [] }
    mocks.storeState.createCategory.mockRejectedValue(
      new mocks.PromptCategoryNameConflictError('Prompt category name already exists'),
    )

    await mountPromptCategoriesView()
    await clickButtonByText('New Category')
    await setFieldValue('#prompt-category-name', 'Portraits')
    await submitFieldForm('#prompt-category-name')

    expect(bodyText()).toContain('Prompt category name already exists')
  })

  it('preselects the group category when creating a subcategory and trims payloads', async () => {
    mocks.storeState.categories = [portraitsCategory]
    mocks.storeState.subcategoriesByCategoryId = { 1: [] }
    mocks.storeState.createSubcategory.mockImplementation(
      async (payload: CreateAdminPromptSubcategoryRequest) => ({
        id: 12,
        promptCategory: portraitsCategory,
        name: payload.name,
        description: payload.description ?? null,
        position: 1,
      }),
    )

    await mountPromptCategoriesView()
    await clickBySelector('[aria-label="Add subcategory to Portraits"]')
    const activeCheckbox = document.body.querySelector(
      '#prompt-subcategory-active',
    ) as HTMLInputElement | null
    expect(activeCheckbox?.checked).toBe(true)
    await setCheckbox('#prompt-subcategory-active', false)
    await setFieldValue('#prompt-subcategory-name', '  Line Art  ')
    await setFieldValue('#prompt-subcategory-description', '   ')
    await submitFieldForm('#prompt-subcategory-name')

    expect(mocks.storeState.createSubcategory).toHaveBeenCalledWith({
      promptCategoryId: 1,
      name: 'Line Art',
      description: null,
      active: false,
    })
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Prompt subcategory created',
      description: 'Line Art was saved.',
      variant: 'success',
    })
  })

  it('blocks subcategory creation when the name is blank', async () => {
    mocks.storeState.categories = [portraitsCategory]
    mocks.storeState.subcategoriesByCategoryId = { 1: [] }

    await mountPromptCategoriesView()
    await clickBySelector('[aria-label="Add subcategory to Portraits"]')
    await submitFieldForm('#prompt-subcategory-name')

    expect(bodyText()).toContain('Name is required.')
    expect(mocks.storeState.createSubcategory).not.toHaveBeenCalled()
  })

  it('disables category deletion when loaded subcategories exist', async () => {
    mocks.storeState.categories = [portraitsCategory]
    mocks.storeState.subcategories = [minimalistSubcategory]
    mocks.storeState.subcategoriesByCategoryId = { 1: [minimalistSubcategory] }

    await mountPromptCategoriesView()
    await clickBySelector('[aria-label="Delete prompt category Portraits"]')

    const activeCheckbox = document.body.querySelector(
      '#prompt-category-active',
    ) as HTMLInputElement | null
    expect(activeCheckbox?.checked).toBe(false)

    const deleteButton = queryButtonByText('Delete Category')
    expect(deleteButton?.disabled).toBe(true)
    expect(bodyText()).toContain('Remove subcategories first.')
    expect(mocks.storeState.deleteCategory).not.toHaveBeenCalled()
  })

  it('deletes a subcategory after destructive confirmation', async () => {
    const inactiveSubcategory = { ...minimalistSubcategory, active: false }
    mocks.storeState.categories = [portraitsCategory]
    mocks.storeState.subcategories = [inactiveSubcategory]
    mocks.storeState.subcategoriesByCategoryId = { 1: [inactiveSubcategory] }
    mocks.storeState.deleteSubcategory.mockResolvedValue(undefined)

    await mountPromptCategoriesView()
    await toggleCategorySubcategories('Portraits')
    await clickBySelector('[aria-label="Delete prompt subcategory Minimalist"]')
    const activeCheckbox = document.body.querySelector(
      '#prompt-subcategory-active',
    ) as HTMLInputElement | null
    expect(activeCheckbox?.checked).toBe(false)
    await clickButtonByText('Delete Subcategory')
    await clickBySelector('[data-testid="confirm-delete-prompt-subcategory"]')

    expect(mocks.storeState.deleteSubcategory).toHaveBeenCalledWith(11)
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Prompt subcategory deleted',
      description: 'Minimalist was deleted.',
      variant: 'success',
    })
  })

  it('saves prompt category order immediately after drag and drop', async () => {
    mocks.storeState.categories = [portraitsCategory, seasonalCategory]
    mocks.storeState.subcategoriesByCategoryId = { 1: [], 2: [] }
    mocks.storeState.reorderCategories.mockResolvedValue(undefined)

    await mountPromptCategoriesView()
    await dragCategoryOntoTarget(
      'Drag prompt category Seasonal',
      '[data-testid="prompt-category-drop-1"]',
    )

    expect(mocks.storeState.reorderCategories).toHaveBeenCalledWith(2, 1)
  })

  it('shows a category skeleton at the drop position while dragging', async () => {
    mocks.storeState.categories = [portraitsCategory, seasonalCategory]
    mocks.storeState.subcategoriesByCategoryId = { 1: [], 2: [] }

    await mountPromptCategoriesView()
    const { target } = await dragCategoryOverTarget(
      'Drag prompt category Seasonal',
      '[data-testid="prompt-category-drop-1"]',
    )

    const skeleton = document.body.querySelector('[data-testid="prompt-category-drop-skeleton"]')
    expect(skeleton).toBeTruthy()
    expect(skeleton?.compareDocumentPosition(target!)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    expect(target?.querySelector('section')?.className).not.toContain('bg-primary')

    skeleton?.dispatchEvent(createDragEvent('drop'))
    await flushPromises()

    expect(mocks.storeState.reorderCategories).toHaveBeenCalledWith(2, 1)
  })

  it('reloads prompt categories and shows an error toast when drag reorder conflicts', async () => {
    mocks.storeState.categories = [portraitsCategory, seasonalCategory]
    mocks.storeState.subcategoriesByCategoryId = { 1: [], 2: [] }
    mocks.storeState.reorderCategories.mockRejectedValue(
      new mocks.PromptCategoryOrderConflictError('Prompt category order is stale'),
    )

    await mountPromptCategoriesView()
    await dragCategoryOntoTarget(
      'Drag prompt category Seasonal',
      '[data-testid="prompt-category-drop-1"]',
    )

    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Prompt category order changed',
      description: 'Prompt category order is stale',
      variant: 'destructive',
    })
    expect(mocks.storeState.fetchCategories).toHaveBeenCalledTimes(2)
    expect(mocks.storeState.fetchSubcategories).toHaveBeenCalledTimes(2)
  })

  it('saves prompt subcategory order immediately after same-category drag and drop', async () => {
    mocks.storeState.categories = [portraitsCategory]
    mocks.storeState.subcategories = [minimalistSubcategory, studioSubcategory]
    mocks.storeState.subcategoriesByCategoryId = {
      1: [minimalistSubcategory, studioSubcategory],
    }

    await mountPromptCategoriesView()
    await toggleCategorySubcategories('Portraits')
    await dragSubcategoryOntoTarget(
      'Drag prompt subcategory Studio',
      '[data-testid="prompt-subcategory-drop-11"]',
    )

    expect(mocks.storeState.reorderSubcategories).toHaveBeenCalledWith(12, 11)
  })

  it('shows a subcategory skeleton row at the drop position while dragging', async () => {
    mocks.storeState.categories = [portraitsCategory]
    mocks.storeState.subcategories = [minimalistSubcategory, studioSubcategory]
    mocks.storeState.subcategoriesByCategoryId = {
      1: [minimalistSubcategory, studioSubcategory],
    }

    await mountPromptCategoriesView()
    await toggleCategorySubcategories('Portraits')
    const { target } = await dragSubcategoryOverTarget(
      'Drag prompt subcategory Studio',
      '[data-testid="prompt-subcategory-drop-11"]',
    )

    const skeleton = document.body.querySelector('[data-testid="prompt-subcategory-drop-skeleton"]')
    expect(skeleton).toBeTruthy()
    expect(skeleton?.compareDocumentPosition(target!)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    expect(target?.className).not.toContain('bg-primary')

    skeleton?.dispatchEvent(createDragEvent('drop'))
    await flushPromises()

    expect(mocks.storeState.reorderSubcategories).toHaveBeenCalledWith(12, 11)
  })

  it('reloads prompt categories and shows an error toast when subcategory reorder conflicts', async () => {
    mocks.storeState.categories = [portraitsCategory]
    mocks.storeState.subcategories = [minimalistSubcategory, studioSubcategory]
    mocks.storeState.subcategoriesByCategoryId = {
      1: [minimalistSubcategory, studioSubcategory],
    }
    mocks.storeState.reorderSubcategories.mockRejectedValue(
      new mocks.PromptSubcategoryOrderConflictError('Prompt subcategory order is stale'),
    )

    await mountPromptCategoriesView()
    await toggleCategorySubcategories('Portraits')
    await dragSubcategoryOntoTarget(
      'Drag prompt subcategory Studio',
      '[data-testid="prompt-subcategory-drop-11"]',
    )

    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Prompt subcategory order changed',
      description: 'Prompt subcategory order is stale',
      variant: 'destructive',
    })
    expect(mocks.storeState.fetchCategories).toHaveBeenCalledTimes(2)
    expect(mocks.storeState.fetchSubcategories).toHaveBeenCalledTimes(2)
  })
})
