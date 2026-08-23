import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TshirtArticlesView from '../TshirtArticlesView.vue'
import type { AdminArticleListItemDto } from '@/stores/admin/articles'
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

vi.mock('@/stores/admin/tshirtArticles', () => ({
  useAdminTshirtArticlesStore: () => mocks.storeState,
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

async function mountTshirtArticlesView(query: Record<string, string> = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/admin/articles/tshirts',
        name: 'admin-tshirt-articles',
        component: { template: '<div />' },
      },
      {
        path: '/admin/articles/tshirts/new',
        name: 'admin-tshirt-article-new',
        component: { template: '<div />' },
      },
      {
        path: '/admin/articles/tshirts/:id/edit',
        name: 'admin-tshirt-article-edit',
        component: { template: '<div />' },
      },
    ],
  })
  await router.push({ path: '/admin/articles/tshirts', query })
  await router.isReady()

  const wrapper = mount(TshirtArticlesView, {
    attachTo: document.body,
    global: { plugins: [router] },
  })
  await flushPromises()
  return wrapper
}

describe('TshirtArticlesView', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    mocks.toast.mockReset()
    resetStoreState()
  })

  it('renders the t-shirt list workflow', async () => {
    const wrapper = await mountTshirtArticlesView()

    expect(wrapper.find('h1').text()).toBe('T-Shirts')
    expect(wrapper.text()).toContain('Reload')
    expect(wrapper.text()).toContain('No T-shirts found.')
    expect(wrapper.find('[data-testid="add-tshirt-article"]').text()).toContain('Add T-Shirt')
  })

  it('links every row to the t-shirt editor and carries the filter query along', async () => {
    mocks.storeState.articles = [article({ id: 7, name: 'Shirt', active: false })]

    const wrapper = await mountTshirtArticlesView({ status: 'inactive' })

    expect(wrapper.find('a[href="/admin/articles/tshirts/new?status=inactive"]').exists()).toBe(
      true,
    )
    const editLink = document.body.querySelector('[aria-label="Edit article Shirt"]')
    expect(editLink?.getAttribute('href')).toBe('/admin/articles/tshirts/7/edit?status=inactive')
  })

  it('delegates a reorder to the t-shirt store', async () => {
    mocks.storeState.articles = [
      article({ id: 7, position: 1, name: 'First shirt' }),
      article({ id: 8, position: 2, name: 'Second shirt' }),
    ]

    await mountTshirtArticlesView()
    await dragArticle('Second shirt', 7)

    expect(mocks.storeState.reorderArticles).toHaveBeenCalledWith(8, 7)
  })
})
