import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MugArticlesView from '../MugArticlesView.vue'
import { ArticleOrderConflictError, type AdminArticleListItemDto } from '@/stores/admin/articles'
import { createAdminArticleListItem as article } from '@/testing/adminArticle'
import { dragArticle } from '@/testing/dragEvent'

const mocks = vi.hoisted(() => ({
  toast: vi.fn(),
  storeState: {
    articles: [] as AdminArticleListItemDto[],
    isLoading: false,
    isReordering: false,
    error: null as string | null,
    fetchArticles: vi.fn(),
    reorderArticles: vi.fn(),
  },
  categoriesState: {
    categories: [] as { id: number; name: string; position: number; active: boolean }[],
    isLoading: false,
    error: null as string | null,
    fetchCategories: vi.fn(),
  },
  subcategoriesState: {
    subcategories: [] as unknown[],
    isLoading: false,
    error: null as string | null,
    fetchSubcategories: vi.fn(),
  },
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast: mocks.toast }),
}))

vi.mock('@/stores/admin/mugArticles', () => ({
  useAdminMugArticlesStore: () => mocks.storeState,
}))

vi.mock('@/stores/admin/articleCategories', () => ({
  useAdminArticleCategoriesStore: () => mocks.categoriesState,
}))

vi.mock('@/stores/admin/articleSubcategories', () => ({
  useAdminArticleSubcategoriesStore: () => mocks.subcategoriesState,
}))

function resetStoreState() {
  mocks.storeState.articles = []
  mocks.storeState.isLoading = false
  mocks.storeState.isReordering = false
  mocks.storeState.error = null
  mocks.storeState.fetchArticles.mockReset().mockResolvedValue(undefined)
  mocks.storeState.reorderArticles.mockReset().mockResolvedValue([])
  mocks.categoriesState.categories = []
  mocks.categoriesState.fetchCategories.mockReset().mockResolvedValue(undefined)
  mocks.subcategoriesState.subcategories = []
  mocks.subcategoriesState.fetchSubcategories.mockReset().mockResolvedValue(undefined)
}

async function mountMugArticlesView(query: Record<string, string> = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/admin/articles/mugs',
        name: 'admin-mug-articles',
        component: { template: '<div />' },
      },
      {
        path: '/admin/articles/mugs/new',
        name: 'admin-mug-article-new',
        component: { template: '<div />' },
      },
      {
        path: '/admin/articles/mugs/:id/edit',
        name: 'admin-mug-article-edit',
        component: { template: '<div />' },
      },
    ],
  })
  await router.push({ path: '/admin/articles/mugs', query })
  await router.isReady()

  const wrapper = mount(MugArticlesView, {
    attachTo: document.body,
    global: { plugins: [router] },
  })
  await flushPromises()
  return wrapper
}

describe('MugArticlesView', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    mocks.toast.mockReset()
    resetStoreState()
  })

  it('renders the mug list workflow', async () => {
    const wrapper = await mountMugArticlesView()

    expect(wrapper.find('h1').text()).toBe('Mugs')
    expect(wrapper.text()).toContain('Reload')
    expect(wrapper.text()).toContain('No mugs found.')
    expect(wrapper.find('[data-testid="add-mug-article"]').text()).toContain('Add Mug')
  })

  it('renders the article ordering table', async () => {
    mocks.storeState.articles = [article({ name: 'Becher', active: false })]

    const wrapper = await mountMugArticlesView()

    expect(wrapper.text()).toContain('Order')
    expect(wrapper.text()).toContain('Inactive')
    expect(wrapper.find('[aria-label="Drag article Becher"]').exists()).toBe(true)
  })

  it('delegates a reorder without replacing or reloading the authoritative store collection', async () => {
    const authoritativeArticles = [
      article({ id: 1, position: 1, name: 'First' }),
      article({ id: 2, position: 2, name: 'Second' }),
    ]
    mocks.storeState.articles = authoritativeArticles

    await mountMugArticlesView()
    await dragArticle('Second', 1)

    expect(mocks.storeState.reorderArticles).toHaveBeenCalledWith(2, 1)
    expect(mocks.storeState.fetchArticles).toHaveBeenCalledTimes(1)
    expect(mocks.storeState.articles).toBe(authoritativeArticles)
  })

  it('shows actionable feedback and reloads after an ordering conflict', async () => {
    mocks.storeState.articles = [
      article({ id: 1, position: 1, name: 'First' }),
      article({ id: 2, position: 2, name: 'Second' }),
    ]
    mocks.storeState.reorderArticles.mockRejectedValue(
      new ArticleOrderConflictError('Article order is stale'),
    )

    await mountMugArticlesView()
    await dragArticle('Second', 1)

    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Article order changed',
      description: 'The current order was reloaded. Try reordering again.',
      variant: 'destructive',
    })
    expect(mocks.storeState.fetchArticles).toHaveBeenCalledTimes(2)
  })

  it('shows actionable feedback and reloads after any other reorder failure', async () => {
    mocks.storeState.articles = [
      article({ id: 1, position: 1, name: 'First' }),
      article({ id: 2, position: 2, name: 'Second' }),
    ]
    mocks.storeState.reorderArticles.mockRejectedValue(new Error('Internal persistence detail'))

    await mountMugArticlesView()
    await dragArticle('Second', 1)

    expect(mocks.toast).toHaveBeenCalledWith({
      title: 'Failed to reorder articles',
      description: 'The current order was reloaded. Try reordering again.',
      variant: 'destructive',
    })
    expect(mocks.storeState.fetchArticles).toHaveBeenCalledTimes(2)
  })

  it('shows saving status and disables drag handles during a reorder request', async () => {
    mocks.storeState.articles = [article({ id: 1, name: 'First' })]
    mocks.storeState.isReordering = true

    await mountMugArticlesView()

    const handle = document.body.querySelector(
      '[aria-label="Drag article First"]',
    ) as HTMLButtonElement | null
    expect(handle).toBeTruthy()
    expect(handle?.disabled).toBe(true)
    expect(handle?.getAttribute('draggable')).toBe('false')
    expect(document.body.querySelector('[role="status"]')?.textContent).toContain(
      'Saving article order...',
    )
  })

  it('renders the filter bar in the header actions and fetches the category references', async () => {
    mocks.storeState.articles = [article({ id: 1, name: 'First' })]

    const wrapper = await mountMugArticlesView()

    expect(wrapper.find('[data-testid="article-filter-category"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="article-filter-status"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="article-filter-name"]').exists()).toBe(true)
    expect(mocks.categoriesState.fetchCategories).toHaveBeenCalledTimes(1)
    expect(mocks.subcategoriesState.fetchSubcategories).toHaveBeenCalledTimes(1)
  })

  it('disables drag-and-drop reordering while a filter is active', async () => {
    mocks.storeState.articles = [article({ id: 1, name: 'First', active: false })]

    await mountMugArticlesView({ status: 'inactive' })

    const handle = document.body.querySelector(
      '[aria-label="Drag article First"]',
    ) as HTMLButtonElement | null
    expect(handle).toBeTruthy()
    expect(handle?.disabled).toBe(true)
    expect(handle?.getAttribute('draggable')).toBe('false')
  })

  it('shows the filtered empty state with a reset offer when no article matches', async () => {
    mocks.storeState.articles = [article({ id: 1, name: 'First' })]

    const wrapper = await mountMugArticlesView({ name: 'zzz' })

    expect(wrapper.find('[data-testid="article-filter-empty"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('No mugs match the active filters.')
    expect(wrapper.find('[data-testid="article-drop-1"]').exists()).toBe(false)
  })

  it('carries the active filter query into the editor and new-article routes', async () => {
    mocks.storeState.articles = [article({ id: 1, name: 'First', active: false })]

    const wrapper = await mountMugArticlesView({ status: 'inactive' })

    expect(wrapper.find('a[href="/admin/articles/mugs/new?status=inactive"]').exists()).toBe(true)
    const editLink = document.body.querySelector('[aria-label="Edit article First"]')
    expect(editLink?.getAttribute('href')).toBe('/admin/articles/mugs/1/edit?status=inactive')
  })

  it('disables drag handles during a reload without announcing that order is saving', async () => {
    mocks.storeState.articles = [article({ id: 1, name: 'First' })]
    mocks.storeState.isLoading = true

    await mountMugArticlesView()

    const handle = document.body.querySelector(
      '[aria-label="Drag article First"]',
    ) as HTMLButtonElement | null
    expect(handle).toBeTruthy()
    expect(handle?.disabled).toBe(true)
    expect(handle?.getAttribute('draggable')).toBe('false')
    expect(document.body.querySelector('[role="status"]')).toBeNull()
  })
})
