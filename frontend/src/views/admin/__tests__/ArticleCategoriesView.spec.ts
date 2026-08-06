import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ArticleCategoriesView from '../ArticleCategoriesView.vue'
import AdminArticleSubcategoryDialog from '@/components/admin/article/subcategory/AdminArticleSubcategoryDialog.vue'
import type { AdminArticleCategoryDto } from '@/stores/admin/articleCategories'
import type { AdminArticleSubcategoryDto } from '@/stores/admin/articleSubcategories'

const mocks = vi.hoisted(() => {
  class ArticleCategoryNotFoundError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'ArticleCategoryNotFoundError'
    }
  }

  class ArticleCategoryNameConflictError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'ArticleCategoryNameConflictError'
    }
  }

  class ArticleCategoryInUseError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'ArticleCategoryInUseError'
    }
  }

  class ArticleCategoryOrderConflictError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'ArticleCategoryOrderConflictError'
    }
  }

  class ArticleSubcategoryNotFoundError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'ArticleSubcategoryNotFoundError'
    }
  }

  class ArticleSubcategoryNameConflictError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'ArticleSubcategoryNameConflictError'
    }
  }

  class ArticleSubcategoryInUseError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'ArticleSubcategoryInUseError'
    }
  }

  class ArticleSubcategoryOrderConflictError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'ArticleSubcategoryOrderConflictError'
    }
  }

  class ArticleSubcategoryValidationError extends Error {
    readonly fieldErrors: Record<string, string[]>

    constructor(message: string, fieldErrors: Record<string, string[]>) {
      super(message)
      this.name = 'ArticleSubcategoryValidationError'
      this.fieldErrors = fieldErrors
    }

    fieldError(field: string): string | null {
      return this.fieldErrors[field]?.[0] ?? null
    }
  }

  return {
    toast: vi.fn(),
    categoriesState: {
      categories: [] as AdminArticleCategoryDto[],
      isLoading: false,
      error: null as string | null,
      fetchCategories: vi.fn(),
      createCategory: vi.fn(),
      updateCategory: vi.fn(),
      deleteCategory: vi.fn(),
      reorderCategories: vi.fn(),
    },
    subcategoriesState: {
      subcategories: [] as AdminArticleSubcategoryDto[],
      isLoading: false,
      error: null as string | null,
      fetchSubcategories: vi.fn(),
      createSubcategory: vi.fn(),
      updateSubcategory: vi.fn(),
      deleteSubcategory: vi.fn(),
      reorderSubcategories: vi.fn(),
      uploadExampleImage: vi.fn(),
    },
    ArticleCategoryNotFoundError,
    ArticleCategoryNameConflictError,
    ArticleCategoryInUseError,
    ArticleCategoryOrderConflictError,
    ArticleSubcategoryNotFoundError,
    ArticleSubcategoryNameConflictError,
    ArticleSubcategoryInUseError,
    ArticleSubcategoryOrderConflictError,
    ArticleSubcategoryValidationError,
  }
})

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast: mocks.toast }),
}))

vi.mock('@/stores/admin/articleCategories', () => ({
  useAdminArticleCategoriesStore: () => mocks.categoriesState,
  ArticleCategoryNotFoundError: mocks.ArticleCategoryNotFoundError,
  ArticleCategoryNameConflictError: mocks.ArticleCategoryNameConflictError,
  ArticleCategoryInUseError: mocks.ArticleCategoryInUseError,
  ArticleCategoryOrderConflictError: mocks.ArticleCategoryOrderConflictError,
}))

vi.mock('@/stores/admin/articleSubcategories', () => ({
  useAdminArticleSubcategoriesStore: () => mocks.subcategoriesState,
  ArticleSubcategoryNotFoundError: mocks.ArticleSubcategoryNotFoundError,
  ArticleSubcategoryNameConflictError: mocks.ArticleSubcategoryNameConflictError,
  ArticleSubcategoryInUseError: mocks.ArticleSubcategoryInUseError,
  ArticleSubcategoryOrderConflictError: mocks.ArticleSubcategoryOrderConflictError,
  ArticleSubcategoryValidationError: mocks.ArticleSubcategoryValidationError,
}))

const mugCategory: AdminArticleCategoryDto = {
  id: 1,
  name: 'Mugs',
  description: 'Coffee mugs',
  position: 1,
  active: false,
}

const cardCategory: AdminArticleCategoryDto = {
  id: 2,
  name: 'Cards',
  description: null,
  position: 2,
  active: true,
}

function subcategory(
  overrides: Partial<AdminArticleSubcategoryDto> = {},
): AdminArticleSubcategoryDto {
  return {
    id: 10,
    categoryId: mugCategory.id,
    name: 'Espresso',
    description: 'Small cups',
    exampleImageFilename: null,
    position: 1,
    active: true,
    ...overrides,
  }
}

const espressoSubcategory = subcategory()

function resetStoreState() {
  mocks.categoriesState.categories = []
  mocks.categoriesState.isLoading = false
  mocks.categoriesState.error = null
  mocks.categoriesState.fetchCategories.mockReset().mockResolvedValue(undefined)
  mocks.categoriesState.createCategory.mockReset()
  mocks.categoriesState.updateCategory.mockReset()
  mocks.categoriesState.deleteCategory.mockReset()
  mocks.categoriesState.reorderCategories.mockReset().mockResolvedValue([])
  mocks.subcategoriesState.subcategories = []
  mocks.subcategoriesState.isLoading = false
  mocks.subcategoriesState.error = null
  mocks.subcategoriesState.fetchSubcategories.mockReset().mockResolvedValue(undefined)
  mocks.subcategoriesState.createSubcategory.mockReset()
  mocks.subcategoriesState.updateSubcategory.mockReset()
  mocks.subcategoriesState.deleteSubcategory.mockReset()
  mocks.subcategoriesState.reorderSubcategories.mockReset().mockResolvedValue([])
  mocks.subcategoriesState.uploadExampleImage.mockReset()
}

async function mountArticleCategoriesView() {
  const wrapper = mount(ArticleCategoriesView, {
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

async function dragCategoryOntoTarget(handleLabel: string, targetSelector: string) {
  const handle = document.body.querySelector(`[aria-label="${handleLabel}"]`) as HTMLElement | null
  const target = document.body.querySelector(targetSelector) as HTMLElement | null
  expect(handle).toBeTruthy()
  expect(target).toBeTruthy()

  handle?.dispatchEvent(createDragEvent('dragstart'))
  target?.dispatchEvent(createDragEvent('dragover'))
  target?.dispatchEvent(createDragEvent('drop'))
  await flushPromises()
}

async function dragSubcategoryOntoTarget(handleLabel: string, targetSelector: string) {
  const handle = document.body.querySelector(`[aria-label="${handleLabel}"]`) as HTMLElement | null
  const target = document.body.querySelector(targetSelector) as HTMLElement | null
  expect(handle).toBeTruthy()
  expect(target).toBeTruthy()

  handle?.dispatchEvent(createDragEvent('dragstart'))
  target?.dispatchEvent(createDragEvent('dragover'))
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

function getFieldValue(selector: string) {
  const field = document.body.querySelector(selector) as
    | HTMLInputElement
    | HTMLTextAreaElement
    | null
  expect(field).toBeTruthy()
  return field?.value
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

function installObjectUrlMock() {
  Object.defineProperty(URL, 'createObjectURL', {
    configurable: true,
    value: vi.fn(() => 'blob:subcategory-preview'),
  })
  Object.defineProperty(URL, 'revokeObjectURL', {
    configurable: true,
    value: vi.fn(),
  })
}

async function setFileInput(selector: string, file: File) {
  const input = document.body.querySelector(selector) as HTMLInputElement | null
  expect(input).toBeTruthy()
  if (!input) {
    return
  }

  Object.defineProperty(input, 'files', {
    configurable: true,
    value: [file],
  })
  input.dispatchEvent(new Event('change', { bubbles: true }))
  await flushPromises()
}

describe('ArticleCategoriesView', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    mocks.toast.mockReset()
    resetStoreState()
  })

  it('loads article categories and subcategories grouped by category', async () => {
    mocks.categoriesState.categories = [mugCategory, cardCategory]
    mocks.subcategoriesState.subcategories = [espressoSubcategory]

    const wrapper = await mountArticleCategoriesView()

    expect(mocks.categoriesState.fetchCategories).toHaveBeenCalledTimes(1)
    expect(mocks.subcategoriesState.fetchSubcategories).toHaveBeenCalledTimes(1)
    expect(wrapper.find('h1').text()).toBe('Article Categories')
    expect(bodyText()).toContain('Mugs')
    expect(wrapper.find('[data-testid="article-category-drop-1"]').text()).toContain('Inactive')
    expect(bodyText()).not.toContain('Espresso')

    await toggleCategorySubcategories('Mugs')

    expect(bodyText()).toContain('Espresso')
    expect(wrapper.find('[data-testid="article-subcategory-drop-10"]').text()).toContain('Active')

    await toggleCategorySubcategories('Cards')

    expect(bodyText()).toContain('No subcategories yet.')
  })

  it('renders the empty state when no categories exist', async () => {
    await mountArticleCategoriesView()

    expect(bodyText()).toContain('No article categories found.')
  })

  it('renders load errors from either store', async () => {
    mocks.subcategoriesState.error = 'HTTP error 500'

    await mountArticleCategoriesView()

    expect(bodyText()).toContain(
      'Failed to load article categories and subcategories. HTTP error 500',
    )
  })

  it('creates a category with a trimmed payload', async () => {
    mocks.categoriesState.categories = [mugCategory]
    mocks.categoriesState.createCategory.mockImplementation(
      async (payload: { name: string; description: string | null }) => ({
        id: 3,
        name: payload.name,
        description: payload.description,
        position: 2,
      }),
    )

    await mountArticleCategoriesView()
    await clickButtonByText('New Category')
    const activeCheckbox = document.body.querySelector(
      '#article-category-active',
    ) as HTMLInputElement | null
    expect(activeCheckbox?.checked).toBe(true)
    await setCheckbox('#article-category-active', false)
    await setFieldValue('#article-category-name', '  Posters  ')
    await setFieldValue('#article-category-description', '   ')
    await submitFieldForm('#article-category-name')

    expect(mocks.categoriesState.createCategory).toHaveBeenCalledWith({
      name: 'Posters',
      description: null,
      active: false,
    })
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Article category created',
      description: 'Posters was saved.',
      variant: 'success',
    })
  })

  it('maps duplicate category names into the category dialog', async () => {
    mocks.categoriesState.categories = [mugCategory]
    mocks.categoriesState.createCategory.mockRejectedValue(
      new mocks.ArticleCategoryNameConflictError('Article category name already exists'),
    )

    await mountArticleCategoriesView()
    await clickButtonByText('New Category')
    await setFieldValue('#article-category-name', 'Mugs')
    await submitFieldForm('#article-category-name')

    expect(bodyText()).toContain('Article category name already exists')
    expect(mocks.toast).not.toHaveBeenCalled()
  })

  it('pre-uploads a selected example image and creates the subcategory with its name', async () => {
    installObjectUrlMock()
    mocks.categoriesState.categories = [mugCategory]
    mocks.subcategoriesState.uploadExampleImage.mockResolvedValue('uploaded.webp')
    mocks.subcategoriesState.createSubcategory.mockImplementation(
      async (payload: { name: string; description: string | null }) => ({
        ...espressoSubcategory,
        id: 99,
        name: payload.name,
        description: payload.description,
        exampleImageFilename: 'uploaded.webp',
      }),
    )
    const file = new File(['image'], 'latte.png', { type: 'image/png' })

    await mountArticleCategoriesView()

    await clickBySelector('[aria-label="Add subcategory to Mugs"]')
    await setFieldValue('#article-subcategory-name', 'Latte')
    await setFileInput('[data-testid="subcategory-example-image-input"]', file)
    await submitFieldForm('#article-subcategory-name')

    expect(mocks.subcategoriesState.uploadExampleImage).toHaveBeenCalledWith(file)
    expect(mocks.subcategoriesState.createSubcategory).toHaveBeenCalledWith({
      categoryId: 1,
      name: 'Latte',
      description: null,
      exampleImageFilename: 'uploaded.webp',
      active: true,
    })
    expect(URL.createObjectURL).toHaveBeenCalledWith(file)
  })

  it('shows a rejected pre-upload on the example image field', async () => {
    installObjectUrlMock()
    mocks.categoriesState.categories = [mugCategory]
    mocks.subcategoriesState.uploadExampleImage.mockRejectedValue(
      new mocks.ArticleSubcategoryValidationError('Validation failed', {
        file: ['Example image must not exceed 10 MiB'],
      }),
    )
    const file = new File(['image'], 'latte.png', { type: 'image/png' })

    await mountArticleCategoriesView()

    await clickBySelector('[aria-label="Add subcategory to Mugs"]')
    await setFieldValue('#article-subcategory-name', 'Latte')
    await setFileInput('[data-testid="subcategory-example-image-input"]', file)
    await submitFieldForm('#article-subcategory-name')

    expect(mocks.subcategoriesState.createSubcategory).not.toHaveBeenCalled()
    expect(bodyText()).toContain('Example image must not exceed 10 MiB')
    expect(mocks.toast).not.toHaveBeenCalled()
  })

  it('shows a rejected category on the category field of the subcategory dialog', async () => {
    mocks.categoriesState.categories = [mugCategory, cardCategory]
    mocks.subcategoriesState.subcategories = [espressoSubcategory]
    mocks.subcategoriesState.updateSubcategory.mockRejectedValue(
      new mocks.ArticleSubcategoryValidationError('Validation failed', {
        categoryId: [
          'Article subcategory is used by articles and cannot be moved to another category',
        ],
      }),
    )

    await mountArticleCategoriesView()
    await toggleCategorySubcategories('Mugs')
    await clickBySelector('[aria-label="Edit article subcategory Espresso"]')
    await submitFieldForm('#article-subcategory-name')

    expect(bodyText()).toContain(
      'Article subcategory is used by articles and cannot be moved to another category',
    )
    expect(mocks.toast).not.toHaveBeenCalled()
  })

  it('sends a null file name when removing the current example image', async () => {
    mocks.categoriesState.categories = [mugCategory]
    mocks.subcategoriesState.subcategories = [
      subcategory({ exampleImageFilename: 'espresso.webp', active: false }),
    ]
    mocks.subcategoriesState.updateSubcategory.mockImplementation(
      async (id: number, payload: { categoryId: number; name: string }) => ({
        ...espressoSubcategory,
        id,
        categoryId: payload.categoryId,
        name: payload.name,
        exampleImageFilename: null,
      }),
    )

    await mountArticleCategoriesView()

    await toggleCategorySubcategories('Mugs')
    await clickBySelector('[aria-label="Edit article subcategory Espresso"]')
    const activeCheckbox = document.body.querySelector(
      '#article-subcategory-active',
    ) as HTMLInputElement | null
    expect(activeCheckbox?.checked).toBe(false)
    const preview = document.body.querySelector(
      '[data-testid="subcategory-example-image-preview"] img',
    ) as HTMLImageElement | null
    expect(preview?.getAttribute('src')).toBe(
      '/api/images/public/400/articles/subcategory-example-images/espresso.webp',
    )

    await clickBySelector('[data-testid="subcategory-example-image-remove"]')
    await submitFieldForm('#article-subcategory-name')

    expect(mocks.subcategoriesState.uploadExampleImage).not.toHaveBeenCalled()
    expect(mocks.subcategoriesState.updateSubcategory).toHaveBeenCalledWith(10, {
      categoryId: 1,
      name: 'Espresso',
      description: 'Small cups',
      exampleImageFilename: null,
      active: false,
    })
  })

  it('rejects unsupported example image files client-side', async () => {
    installObjectUrlMock()
    mocks.categoriesState.categories = [mugCategory]
    const file = new File(['image'], 'animated.gif', { type: 'image/gif' })

    await mountArticleCategoriesView()

    await clickBySelector('[aria-label="Add subcategory to Mugs"]')
    await setFieldValue('#article-subcategory-name', 'Latte')
    await setFileInput('[data-testid="subcategory-example-image-input"]', file)

    expect(bodyText()).toContain('Choose a JPG, PNG, or WebP image.')

    await submitFieldForm('#article-subcategory-name')

    expect(mocks.subcategoriesState.createSubcategory).not.toHaveBeenCalled()
  })

  it('rejects example image files over 10 MB client-side', async () => {
    installObjectUrlMock()
    mocks.categoriesState.categories = [mugCategory]
    const file = new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'large.png', {
      type: 'image/png',
    })

    await mountArticleCategoriesView()

    await clickBySelector('[aria-label="Add subcategory to Mugs"]')
    await setFieldValue('#article-subcategory-name', 'Latte')
    await setFileInput('[data-testid="subcategory-example-image-input"]', file)

    expect(bodyText()).toContain('Image must be at most 10 MB.')

    await submitFieldForm('#article-subcategory-name')

    expect(mocks.subcategoriesState.createSubcategory).not.toHaveBeenCalled()
  })

  it('shows a destructive error in the dialog when category delete returns a conflict', async () => {
    mocks.categoriesState.categories = [mugCategory]
    mocks.categoriesState.deleteCategory.mockRejectedValue(
      new mocks.ArticleCategoryInUseError('Category is referenced by articles'),
    )

    await mountArticleCategoriesView()
    await clickBySelector('[aria-label="Edit article category Mugs"]')
    const activeCheckbox = document.body.querySelector(
      '#article-category-active',
    ) as HTMLInputElement | null
    expect(activeCheckbox?.checked).toBe(false)
    await clickButtonByText('Delete Category')
    await clickBySelector('[data-testid="confirm-delete-article-category"]')

    expect(mocks.categoriesState.deleteCategory).toHaveBeenCalledWith(1)
    expect(bodyText()).toContain('Category is referenced by articles')
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Article category cannot be deleted',
      description: 'Category is referenced by articles',
      variant: 'destructive',
    })
  })

  it('preselects the group category when creating a subcategory and trims payloads', async () => {
    mocks.categoriesState.categories = [mugCategory, cardCategory]
    mocks.subcategoriesState.createSubcategory.mockImplementation(
      async (payload: { categoryId: number; name: string; description: string | null }) => ({
        ...espressoSubcategory,
        id: 99,
        categoryId: payload.categoryId,
        name: payload.name,
        description: payload.description,
      }),
    )

    await mountArticleCategoriesView()
    await clickBySelector('[aria-label="Add subcategory to Cards"]')
    const activeCheckbox = document.body.querySelector(
      '#article-subcategory-active',
    ) as HTMLInputElement | null
    expect(activeCheckbox?.checked).toBe(true)
    await setCheckbox('#article-subcategory-active', false)
    await setFieldValue('#article-subcategory-name', '  Birthday  ')
    await setFieldValue('#article-subcategory-description', '   ')
    await submitFieldForm('#article-subcategory-name')

    expect(mocks.subcategoriesState.createSubcategory).toHaveBeenCalledWith({
      categoryId: 2,
      name: 'Birthday',
      description: null,
      exampleImageFilename: null,
      active: false,
    })
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Article subcategory created',
      description: 'Birthday was saved.',
      variant: 'success',
    })
  })

  it('maps duplicate subcategory names into the subcategory dialog', async () => {
    mocks.categoriesState.categories = [mugCategory]
    mocks.subcategoriesState.subcategories = [espressoSubcategory]
    mocks.subcategoriesState.createSubcategory.mockRejectedValue(
      new mocks.ArticleSubcategoryNameConflictError(
        'Article subcategory name already exists in this article category',
      ),
    )

    await mountArticleCategoriesView()
    await clickBySelector('[aria-label="Add subcategory to Mugs"]')
    await setFieldValue('#article-subcategory-name', 'Espresso')
    await submitFieldForm('#article-subcategory-name')

    expect(bodyText()).toContain('Article subcategory name already exists in this article category')
    expect(mocks.toast).not.toHaveBeenCalled()
  })

  it('moves a subcategory to another category via the edit dialog', async () => {
    mocks.categoriesState.categories = [mugCategory, cardCategory]
    mocks.subcategoriesState.subcategories = [espressoSubcategory]
    mocks.subcategoriesState.updateSubcategory.mockImplementation(
      async (id: number, payload: { categoryId: number; name: string }) => ({
        ...espressoSubcategory,
        id,
        categoryId: payload.categoryId,
        name: payload.name,
      }),
    )

    const wrapper = await mountArticleCategoriesView()
    await toggleCategorySubcategories('Mugs')
    await clickBySelector('[aria-label="Edit article subcategory Espresso"]')

    expect(getFieldValue('#article-subcategory-name')).toBe('Espresso')

    wrapper.getComponent(AdminArticleSubcategoryDialog).vm.$emit('save', {
      categoryId: 2,
      name: 'Premium',
      description: null,
      active: true,
      exampleImage: null,
      exampleImageFilename: null,
    })
    await flushPromises()

    expect(mocks.subcategoriesState.updateSubcategory).toHaveBeenCalledWith(10, {
      categoryId: 2,
      name: 'Premium',
      description: null,
      exampleImageFilename: null,
      active: true,
    })
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Article subcategory saved',
      description: 'Premium was saved.',
      variant: 'success',
    })
  })

  it('deletes a subcategory after destructive confirmation', async () => {
    mocks.categoriesState.categories = [mugCategory]
    mocks.subcategoriesState.subcategories = [espressoSubcategory]
    mocks.subcategoriesState.deleteSubcategory.mockResolvedValue(undefined)

    await mountArticleCategoriesView()
    await toggleCategorySubcategories('Mugs')
    await clickBySelector('[aria-label="Edit article subcategory Espresso"]')
    await clickButtonByText('Delete Subcategory')
    await clickBySelector('[data-testid="confirm-delete-article-subcategory"]')

    expect(mocks.subcategoriesState.deleteSubcategory).toHaveBeenCalledWith(10)
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Article subcategory deleted',
      description: 'Espresso was deleted.',
      variant: 'success',
    })
  })

  it('shows a destructive error in the dialog when subcategory delete returns a conflict', async () => {
    mocks.categoriesState.categories = [mugCategory]
    mocks.subcategoriesState.subcategories = [espressoSubcategory]
    mocks.subcategoriesState.deleteSubcategory.mockRejectedValue(
      new mocks.ArticleSubcategoryInUseError('Article subcategory is in use by existing articles'),
    )

    await mountArticleCategoriesView()
    await toggleCategorySubcategories('Mugs')
    await clickBySelector('[aria-label="Edit article subcategory Espresso"]')
    await clickButtonByText('Delete Subcategory')
    await clickBySelector('[data-testid="confirm-delete-article-subcategory"]')

    expect(bodyText()).toContain('Article subcategory is in use by existing articles')
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Article subcategory cannot be deleted',
      description: 'Article subcategory is in use by existing articles',
      variant: 'destructive',
    })
  })

  it('saves article category order immediately after drag and drop', async () => {
    mocks.categoriesState.categories = [mugCategory, cardCategory]
    mocks.categoriesState.reorderCategories.mockResolvedValue([cardCategory, mugCategory])

    await mountArticleCategoriesView()
    await dragCategoryOntoTarget(
      'Drag article category Cards',
      '[data-testid="article-category-drop-1"]',
    )

    expect(mocks.categoriesState.reorderCategories).toHaveBeenCalledWith(2, 1)
  })

  it('reloads article categories when a reordered category no longer exists', async () => {
    mocks.categoriesState.categories = [mugCategory, cardCategory]
    mocks.categoriesState.reorderCategories.mockRejectedValue(
      new mocks.ArticleCategoryNotFoundError('Article category not found'),
    )

    await mountArticleCategoriesView()
    await dragCategoryOntoTarget(
      'Drag article category Cards',
      '[data-testid="article-category-drop-1"]',
    )

    expect(mocks.categoriesState.fetchCategories).toHaveBeenCalledTimes(2)
    expect(mocks.subcategoriesState.fetchSubcategories).toHaveBeenCalledTimes(2)
  })

  it('reloads article categories and shows an error toast when category reorder conflicts', async () => {
    mocks.categoriesState.categories = [mugCategory, cardCategory]
    mocks.categoriesState.reorderCategories.mockRejectedValue(
      new mocks.ArticleCategoryOrderConflictError('Article category order is stale'),
    )

    await mountArticleCategoriesView()
    await dragCategoryOntoTarget(
      'Drag article category Cards',
      '[data-testid="article-category-drop-1"]',
    )

    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Article category order changed',
      description: 'Article category order is stale',
      variant: 'destructive',
    })
    expect(mocks.categoriesState.fetchCategories).toHaveBeenCalledTimes(2)
    expect(mocks.subcategoriesState.fetchSubcategories).toHaveBeenCalledTimes(2)
  })

  it('saves article subcategory order immediately after same-category drag and drop', async () => {
    const travelSubcategory = subcategory({ id: 11, name: 'Travel', position: 2 })
    mocks.categoriesState.categories = [mugCategory]
    mocks.subcategoriesState.subcategories = [espressoSubcategory, travelSubcategory]

    await mountArticleCategoriesView()
    await toggleCategorySubcategories('Mugs')
    await dragSubcategoryOntoTarget(
      'Drag article subcategory Travel',
      '[data-testid="article-subcategory-drop-10"]',
    )

    expect(mocks.subcategoriesState.reorderSubcategories).toHaveBeenCalledWith(11, 10)
  })

  it('reloads article categories and shows an error toast when subcategory reorder conflicts', async () => {
    const travelSubcategory = subcategory({ id: 11, name: 'Travel', position: 2 })
    mocks.categoriesState.categories = [mugCategory]
    mocks.subcategoriesState.subcategories = [espressoSubcategory, travelSubcategory]
    mocks.subcategoriesState.reorderSubcategories.mockRejectedValue(
      new mocks.ArticleSubcategoryOrderConflictError('Article subcategory order is stale'),
    )

    await mountArticleCategoriesView()
    await toggleCategorySubcategories('Mugs')
    await dragSubcategoryOntoTarget(
      'Drag article subcategory Travel',
      '[data-testid="article-subcategory-drop-10"]',
    )

    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Article subcategory order changed',
      description: 'Article subcategory order is stale',
      variant: 'destructive',
    })
    expect(mocks.categoriesState.fetchCategories).toHaveBeenCalledTimes(2)
    expect(mocks.subcategoriesState.fetchSubcategories).toHaveBeenCalledTimes(2)
  })
})
