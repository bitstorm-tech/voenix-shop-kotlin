import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref, shallowRef } from 'vue'
import {
  type AdminMugArticleDto,
  ArticleNotFoundError,
  InvalidArticleRequestError,
  type SaveAdminMugArticleRequest,
} from '@/stores/admin/articles'
import { useAdminArticleEditor } from '../useAdminArticleEditor'
import type { useAdminPriceForm } from '../useAdminPriceForm'

const mocks = vi.hoisted(() => ({
  toast: vi.fn(),
  push: vi.fn(async () => {}),
  replace: vi.fn(async () => {}),
  route: { name: 'admin-article-edit', params: {} as Record<string, string>, query: { page: '2' } },
  fetchArticle: vi.fn(),
  createArticle: vi.fn(),
  updateArticle: vi.fn(),
  deleteArticle: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({ push: mocks.push, replace: mocks.replace }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast: mocks.toast }),
}))

vi.mock('@/stores/admin/articles', async () => {
  const actual =
    await vi.importActual<typeof import('@/stores/admin/articles')>('@/stores/admin/articles')

  return {
    ...actual,
    useAdminArticlesStore: () => ({
      fetchArticle: mocks.fetchArticle,
      createArticle: mocks.createArticle,
      updateArticle: mocks.updateArticle,
      deleteArticle: mocks.deleteArticle,
    }),
  }
})

// The reference data is fetched but never read by the lifecycle itself, so an empty store is enough.
vi.mock('@/stores/admin/articleCategories', () => ({
  useAdminArticleCategoriesStore: () => ({ categories: [], fetchCategories: vi.fn() }),
}))
vi.mock('@/stores/admin/articleSubcategories', () => ({
  useAdminArticleSubcategoriesStore: () => ({ subcategories: [], fetchSubcategories: vi.fn() }),
}))
vi.mock('@/stores/admin/suppliers', () => ({
  useAdminSuppliersStore: () => ({ suppliers: [], fetchSuppliers: vi.fn() }),
}))
vi.mock('@/stores/admin/vat', () => ({
  useAdminVatStore: () => ({ vats: [], fetchAll: vi.fn() }),
}))

const LIST_ROUTE = { name: 'admin-articles', query: { page: '2' } }

function createPriceForm() {
  return {
    lastCalculatedPrice: shallowRef(null),
    isCalculationPending: ref(false),
    error: ref(null),
    hasExistingPrice: ref(false),
    initialize: vi.fn(async () => {}),
    calculateNow: vi.fn(async () => {}),
    validateForSave: vi.fn(() => true),
  } as unknown as ReturnType<typeof useAdminPriceForm>
}

function createEditor(
  overrides: Partial<Parameters<typeof useAdminArticleEditor<'MUG'>>[0]> = {},
  payload: Partial<SaveAdminMugArticleRequest> = {},
) {
  const articlePrice = createPriceForm()
  const options = {
    articleType: 'MUG' as const,
    priceTab: 'price',
    articlePrice,
    resetForm: vi.fn(),
    fillForm: vi.fn(),
    clearErrors: vi.fn(),
    validate: vi.fn(() => true),
    buildPayload: vi.fn(
      () => ({ name: 'Cup', active: false, ...payload }) as SaveAdminMugArticleRequest,
    ),
    applySaveErrors: vi.fn(() => 'Category does not exist'),
    showPriceRequired: vi.fn(),
    articleName: () => 'Cup',
    ...overrides,
  }

  return { editor: useAdminArticleEditor(options), options, articlePrice }
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.route.params = {}
  mocks.fetchArticle.mockResolvedValue({
    name: 'Cup',
    price: null,
  } as unknown as AdminMugArticleDto)
  mocks.createArticle.mockResolvedValue({ name: 'Cup' } as AdminMugArticleDto)
  mocks.updateArticle.mockResolvedValue({ name: 'Cup' } as AdminMugArticleDto)
  mocks.deleteArticle.mockResolvedValue(undefined)
})

describe('loading the article of the route', () => {
  it('opens an empty form without an id, and fetches nothing', async () => {
    const { editor, options, articlePrice } = createEditor()
    await flushPromises()

    expect(options.resetForm).toHaveBeenCalled()
    expect(mocks.fetchArticle).not.toHaveBeenCalled()
    expect(articlePrice.initialize).toHaveBeenCalledWith(null)
    expect(editor.isEditMode.value).toBe(false)
    expect(editor.isLoading.value).toBe(false)
  })

  it('fills the form from the fetched article of an edit route', async () => {
    mocks.route.params = { id: '7' }
    const article = { name: 'Cup', price: null } as unknown as AdminMugArticleDto
    mocks.fetchArticle.mockResolvedValue(article)

    const { editor, options } = createEditor()
    await flushPromises()

    expect(mocks.fetchArticle).toHaveBeenCalledWith('MUG', 7)
    expect(options.fillForm).toHaveBeenCalledWith(article)
    expect(editor.isEditMode.value).toBe(true)
    expect(editor.isLoading.value).toBe(false)
  })

  it('sends the user back to the list when the article does not exist', async () => {
    mocks.route.params = { id: '7' }
    mocks.fetchArticle.mockRejectedValue(new ArticleNotFoundError('Article 7 does not exist'))

    const { options } = createEditor()
    await flushPromises()

    expect(options.fillForm).not.toHaveBeenCalled()
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Article not found',
      description: 'Article 7 does not exist',
      variant: 'destructive',
    })
    expect(mocks.replace).toHaveBeenCalledWith(LIST_ROUTE)
  })

  it('keeps the user on a failed load and shows why', async () => {
    mocks.route.params = { id: '7' }
    mocks.fetchArticle.mockRejectedValue(new Error('Network down'))

    const { editor } = createEditor()
    await flushPromises()

    expect(editor.generalError.value).toBe('Network down')
    expect(mocks.replace).not.toHaveBeenCalled()
  })
})

describe('saving the article', () => {
  it('creates the article without an id and returns to the list', async () => {
    const { editor } = createEditor()
    await flushPromises()

    await editor.saveArticle()

    expect(mocks.createArticle).toHaveBeenCalledWith('MUG', { name: 'Cup', active: false })
    expect(mocks.updateArticle).not.toHaveBeenCalled()
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Article created',
      description: 'Cup was saved.',
      variant: 'success',
    })
    expect(mocks.push).toHaveBeenCalledWith(LIST_ROUTE)
    expect(editor.isSaving.value).toBe(false)
  })

  it('updates the article of an edit route', async () => {
    mocks.route.params = { id: '7' }
    const { editor } = createEditor()
    await flushPromises()

    await editor.saveArticle()

    expect(mocks.updateArticle).toHaveBeenCalledWith('MUG', 7, { name: 'Cup', active: false })
    expect(mocks.createArticle).not.toHaveBeenCalled()
  })

  it('does not save what the form itself rejects', async () => {
    const { editor } = createEditor({ validate: vi.fn(() => false) })
    await flushPromises()

    await editor.saveArticle()

    expect(mocks.createArticle).not.toHaveBeenCalled()
  })

  it('refuses an active article that has no price and opens the price tab', async () => {
    const { editor, options } = createEditor({}, { active: true })
    await flushPromises()

    await editor.saveArticle()

    expect(options.showPriceRequired).toHaveBeenCalled()
    expect(editor.activeTab.value).toBe('price')
    expect(mocks.createArticle).not.toHaveBeenCalled()
  })

  it('files a rejected write onto the form and shows what it answered', async () => {
    mocks.createArticle.mockRejectedValue(
      new InvalidArticleRequestError('Validation failed', {
        categoryId: ['Article category does not exist'],
      }),
    )

    const { editor, options } = createEditor()
    await flushPromises()

    await editor.saveArticle()

    expect(options.applySaveErrors).toHaveBeenCalled()
    expect(editor.generalError.value).toBe('Category does not exist')
    expect(mocks.push).not.toHaveBeenCalled()
  })

  it('shows the message of a failure it cannot file onto a field', async () => {
    mocks.createArticle.mockRejectedValue(new Error('Network down'))

    const { editor, options } = createEditor()
    await flushPromises()

    await editor.saveArticle()

    expect(options.applySaveErrors).not.toHaveBeenCalled()
    expect(editor.generalError.value).toBe('Network down')
  })
})

describe('deleting the article', () => {
  it('deletes the article of an edit route and returns to the list', async () => {
    mocks.route.params = { id: '7' }
    const { editor } = createEditor()
    await flushPromises()
    editor.isDeleteDialogOpen.value = true

    await editor.deleteCurrentArticle()

    expect(mocks.deleteArticle).toHaveBeenCalledWith('MUG', 7)
    expect(editor.isDeleteDialogOpen.value).toBe(false)
    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Article deleted',
      description: 'Cup was deleted.',
      variant: 'success',
    })
    expect(mocks.push).toHaveBeenCalledWith(LIST_ROUTE)
  })

  it('deletes nothing in create mode', async () => {
    const { editor } = createEditor()
    await flushPromises()

    await editor.deleteCurrentArticle()

    expect(mocks.deleteArticle).not.toHaveBeenCalled()
  })

  it('shows why a delete failed and stays on the form', async () => {
    mocks.route.params = { id: '7' }
    mocks.deleteArticle.mockRejectedValue(new Error('Article is referenced'))

    const { editor } = createEditor()
    await flushPromises()

    await editor.deleteCurrentArticle()

    expect(editor.generalError.value).toBe('Article is referenced')
    expect(mocks.push).not.toHaveBeenCalled()
    expect(editor.isDeleting.value).toBe(false)
  })
})
