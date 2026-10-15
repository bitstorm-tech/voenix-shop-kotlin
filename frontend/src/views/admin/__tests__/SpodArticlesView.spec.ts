import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SpodArticlesView from '../SpodArticlesView.vue'
import type { AdminSpodArticleListItemDto } from '@/stores/admin/spodArticles'
import { createAdminSpodArticleListItem as article } from '@/testing/adminArticle'
import { dragArticle } from '@/testing/dragEvent'

const mocks = vi.hoisted(() => ({
  toast: vi.fn(),
  storeState: {
    articles: [] as AdminSpodArticleListItemDto[],
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

vi.mock('@/stores/admin/spodArticles', () => ({
  useAdminSpodArticlesStore: () => mocks.storeState,
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

async function mountSpodArticlesView(query: Record<string, string> = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/admin/articles/spod',
        name: 'admin-spod-articles',
        component: { template: '<div />' },
      },
      {
        path: '/admin/articles/spod/:id/edit',
        name: 'admin-spod-article-edit',
        component: { template: '<div />' },
      },
    ],
  })
  await router.push({ path: '/admin/articles/spod', query })
  await router.isReady()

  const wrapper = mount(SpodArticlesView, {
    attachTo: document.body,
    global: { plugins: [router] },
  })
  await flushPromises()
  return wrapper
}

describe('SpodArticlesView', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    mocks.toast.mockReset()
    resetStoreState()
  })

  it('renders the SPOD article list workflow', async () => {
    const wrapper = await mountSpodArticlesView()

    expect(wrapper.find('h1').text()).toBe('SPOD articles')
    expect(wrapper.text()).toContain('Reload')
    expect(wrapper.text()).toContain('No SPOD articles found.')
  })

  // A SPOD article is created by a sync run against the Spreadconnect backoffice.
  it('offers no way to add an article by hand', async () => {
    const wrapper = await mountSpodArticlesView()

    expect(wrapper.find('[data-testid="add-spod-article"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Add SPOD article')
  })

  it('shows the sync stamp of every row and marks the ones the partner dropped', async () => {
    mocks.storeState.articles = [
      article({ id: 7, position: 1, name: 'Listed tee' }),
      article({
        id: 8,
        position: 2,
        name: 'Dropped tee',
        missingAtSpreadconnect: true,
      }),
    ]

    const wrapper = await mountSpodArticlesView()

    const stamps = wrapper.findAll('[data-testid="article-synced-at"]')
    expect(stamps).toHaveLength(2)
    expect(stamps[0]!.text()).toContain('Aug 20, 2026')
    const missing = wrapper.findAll('[data-testid="article-missing"]')
    expect(missing).toHaveLength(1)
    expect(missing[0]!.text()).toBe('Missing at Spreadconnect')
  })

  it('links every row to the SPOD article editor and carries the filter query along', async () => {
    mocks.storeState.articles = [article({ id: 7, name: 'Tee', active: false })]

    await mountSpodArticlesView({ status: 'inactive' })

    const editLink = document.body.querySelector('[aria-label="Edit article Tee"]')
    expect(editLink?.getAttribute('href')).toBe('/admin/articles/spod/7/edit?status=inactive')
  })

  it('delegates a reorder to the SPOD article store', async () => {
    mocks.storeState.articles = [
      article({ id: 7, position: 1, name: 'First tee' }),
      article({ id: 8, position: 2, name: 'Second tee' }),
    ]

    await mountSpodArticlesView()
    await dragArticle('Second tee', 7)

    expect(mocks.storeState.reorderArticles).toHaveBeenCalledWith(8, 7)
  })
})
