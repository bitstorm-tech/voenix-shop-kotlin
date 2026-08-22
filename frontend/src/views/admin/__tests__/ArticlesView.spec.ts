import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ArticlesView from '../ArticlesView.vue'
import type { AdminArticleListItem } from '@/stores/admin/articles'
import { createAdminArticleListItem as article } from '@/testing/adminArticle'
import { createDragEvent } from '@/testing/dragEvent'

const mocks = vi.hoisted(() => {
  class ArticleOrderConflictError extends Error {
    constructor(message: string) {
      super(message)
      this.name = 'ArticleOrderConflictError'
    }
  }

  return {
    toast: vi.fn(),
    storeState: {
      articles: [] as AdminArticleListItem[],
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
    ArticleOrderConflictError,
  }
})

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast: mocks.toast }),
}))

vi.mock('@/stores/admin/articles', () => ({
  useAdminArticlesStore: () => mocks.storeState,
  ArticleOrderConflictError: mocks.ArticleOrderConflictError,
  ARTICLE_TYPE_LABELS: { MUG: 'Mug', TSHIRT: 'T-Shirt' },
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

async function mountArticlesView(query: Record<string, string> = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/admin/articles',
        name: 'admin-articles',
        component: { template: '<div />' },
      },
      {
        path: '/admin/articles/mugs/new',
        name: 'admin-mug-article-new',
        component: { template: '<div />' },
      },
      {
        path: '/admin/articles/tshirts/new',
        name: 'admin-tshirt-article-new',
        component: { template: '<div />' },
      },
      {
        path: '/admin/articles/mugs/:id/edit',
        name: 'admin-mug-article-edit',
        component: { template: '<div />' },
      },
      {
        path: '/admin/articles/tshirts/:id/edit',
        name: 'admin-tshirt-article-edit',
        component: { template: '<div />' },
      },
    ],
  })
  await router.push({ path: '/admin/articles', query })
  await router.isReady()

  const wrapper = mount(ArticlesView, {
    attachTo: document.body,
    global: { plugins: [router] },
  })
  await flushPromises()
  return wrapper
}

async function dragArticle(sourceName: string, targetId: number) {
  const handle = document.body.querySelector(
    `[aria-label="Drag article ${sourceName}"]`,
  ) as HTMLElement | null
  const target = document.body.querySelector(
    `[data-testid="article-drop-${targetId}"]`,
  ) as HTMLElement | null
  expect(handle).toBeTruthy()
  expect(target).toBeTruthy()

  handle?.dispatchEvent(createDragEvent('dragstart'))
  target?.dispatchEvent(createDragEvent('dragover'))
  target?.dispatchEvent(createDragEvent('drop'))
  await flushPromises()
}

describe('ArticlesView', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    mocks.toast.mockReset()
    resetStoreState()
  })

  it('renders the All Articles workflow', async () => {
    const wrapper = await mountArticlesView()

    expect(wrapper.find('h1').text()).toBe('All Articles')
    expect(wrapper.text()).toContain('Reload')
    expect(wrapper.text()).toContain('No articles found.')
  })

  it('renders the Article ordering table', async () => {
    mocks.storeState.articles = [article({ name: 'Becher', active: false })]

    const wrapper = await mountArticlesView()

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

    await mountArticlesView()
    await dragArticle('Second', 1)

    expect(mocks.storeState.reorderArticles).toHaveBeenCalledWith('MUG', 2, 1)
    expect(mocks.storeState.fetchArticles).toHaveBeenCalledTimes(1)
    expect(mocks.storeState.articles).toBe(authoritativeArticles)
  })

  it('shows actionable feedback and reloads after an ordering conflict', async () => {
    mocks.storeState.articles = [
      article({ id: 1, position: 1, name: 'First' }),
      article({ id: 2, position: 2, name: 'Second' }),
    ]
    mocks.storeState.reorderArticles.mockRejectedValue(
      new mocks.ArticleOrderConflictError('Article order is stale'),
    )

    await mountArticlesView()
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

    await mountArticlesView()
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

    await mountArticlesView()

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

    const wrapper = await mountArticlesView()

    expect(wrapper.find('[data-testid="article-filter-category"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="article-filter-status"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="article-filter-name"]').exists()).toBe(true)
    expect(mocks.categoriesState.fetchCategories).toHaveBeenCalledTimes(1)
    expect(mocks.subcategoriesState.fetchSubcategories).toHaveBeenCalledTimes(1)
  })

  it('disables drag-and-drop reordering while a filter is active', async () => {
    mocks.storeState.articles = [article({ id: 1, name: 'First', active: false })]

    await mountArticlesView({ status: 'inactive' })

    const handle = document.body.querySelector(
      '[aria-label="Drag article First"]',
    ) as HTMLButtonElement | null
    expect(handle).toBeTruthy()
    expect(handle?.disabled).toBe(true)
    expect(handle?.getAttribute('draggable')).toBe('false')
  })

  it('shows the filtered empty state with a reset offer when no article matches', async () => {
    mocks.storeState.articles = [article({ id: 1, name: 'First' })]

    const wrapper = await mountArticlesView({ name: 'zzz' })

    expect(wrapper.find('[data-testid="article-filter-empty"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('No articles match the active filters.')
    expect(wrapper.find('[data-testid="article-drop-1"]').exists()).toBe(false)
  })

  it('carries the active filter query into the editor and new-article routes', async () => {
    mocks.storeState.articles = [article({ id: 1, name: 'First', active: false })]

    const wrapper = await mountArticlesView({ status: 'inactive' })

    expect(wrapper.find('a[href="/admin/articles/mugs/new?status=inactive"]').exists()).toBe(true)
    expect(wrapper.find('a[href="/admin/articles/tshirts/new?status=inactive"]').exists()).toBe(
      true,
    )
    const editLink = document.body.querySelector('[aria-label="Edit article First"]')
    expect(editLink?.getAttribute('href')).toBe('/admin/articles/mugs/1/edit?status=inactive')
  })

  it('disables drag handles during a reload without announcing that order is saving', async () => {
    mocks.storeState.articles = [article({ id: 1, name: 'First' })]
    mocks.storeState.isLoading = true

    await mountArticlesView()

    const handle = document.body.querySelector(
      '[aria-label="Drag article First"]',
    ) as HTMLButtonElement | null
    expect(handle).toBeTruthy()
    expect(handle?.disabled).toBe(true)
    expect(handle?.getAttribute('draggable')).toBe('false')
    expect(document.body.querySelector('[role="status"]')).toBeNull()
  })
  it('shows the type of every row and links a shirt to the shirt editor', async () => {
    mocks.storeState.articles = [
      article({ id: 1, name: 'Becher' }),
      article({ articleType: 'TSHIRT', id: 7, name: 'Shirt' }),
    ]

    const wrapper = await mountArticlesView()

    expect(wrapper.text()).toContain('Type')
    expect(
      wrapper.findAll('[data-testid="article-type-badge"]').map((badge) => badge.text()),
    ).toEqual(['Mug', 'T-Shirt'])
    const shirtEditLink = document.body.querySelector('[aria-label="Edit article Shirt"]')
    expect(shirtEditLink?.getAttribute('href')).toBe('/admin/articles/tshirts/7/edit')
  })

  it('offers one creation entry per article type', async () => {
    const wrapper = await mountArticlesView()

    expect(wrapper.find('[data-testid="add-mug-article"]').text()).toContain('Add Mug')
    expect(wrapper.find('[data-testid="add-tshirt-article"]').text()).toContain('Add T-Shirt')
  })

  // Positions are per type and so is the backend's reorder route, so a mug cannot take a shirt's
  // place. The drop is refused instead of being sent as a move that does not exist.
  it('refuses a drop across the two type groups', async () => {
    mocks.storeState.articles = [
      article({ id: 1, position: 1, name: 'Mug' }),
      article({ articleType: 'TSHIRT', id: 7, position: 1, name: 'Shirt' }),
    ]

    await mountArticlesView()
    await dragArticle('Shirt', 1)

    expect(mocks.storeState.reorderArticles).not.toHaveBeenCalled()
  })

  it('names the type of a shirt reorder', async () => {
    mocks.storeState.articles = [
      article({ articleType: 'TSHIRT', id: 7, position: 1, name: 'First shirt' }),
      article({ articleType: 'TSHIRT', id: 8, position: 2, name: 'Second shirt' }),
    ]

    await mountArticlesView()
    await dragArticle('Second shirt', 7)

    expect(mocks.storeState.reorderArticles).toHaveBeenCalledWith('TSHIRT', 8, 7)
  })
})
