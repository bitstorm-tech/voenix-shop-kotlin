import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import {
  useAdminArticleListFilters,
  WITHOUT_CATEGORY,
  WITHOUT_SUBCATEGORY,
} from '../useAdminArticleListFilters'
import type { AdminArticleCategoryDto } from '@/stores/admin/articleCategories'
import type { AdminArticleListItemDto } from '@/stores/admin/articles'
import type { AdminArticleSubcategoryDto } from '@/stores/admin/articleSubcategories'
import { createAdminArticleListItem } from '@/testing/adminArticle'

function category(id: number, name: string, position: number): AdminArticleCategoryDto {
  return { id, name, description: null, position, active: true }
}

function subcategory(
  id: number,
  articleCategory: AdminArticleCategoryDto,
  position: number,
): AdminArticleSubcategoryDto {
  return { id, articleCategory, name: `Sub ${id}`, description: null, position, active: true }
}

const mugs = category(1, 'Mugs', 1)
const shirts = category(2, 'Shirts', 2)
const categories = [mugs, shirts]
const subcategories = [
  subcategory(10, mugs, 1),
  subcategory(11, mugs, 2),
  subcategory(20, shirts, 1),
]

function article(
  id: number,
  overrides: Partial<AdminArticleListItemDto> = {},
): AdminArticleListItemDto {
  return createAdminArticleListItem({
    id,
    position: id,
    name: `Article ${id}`,
    categoryId: mugs.id,
    categoryName: mugs.name,
    ...overrides,
  })
}

const articles = [
  article(1, { subcategoryId: 10, subcategoryName: 'Sub 10' }),
  article(2, { active: false }),
  article(3, { categoryId: null, categoryName: null, active: false }),
  article(4, {
    name: 'Sunset Shirt',
    categoryId: shirts.id,
    categoryName: shirts.name,
    subcategoryId: 20,
    subcategoryName: 'Sub 20',
  }),
]

interface SetupOverrides {
  articles?: AdminArticleListItemDto[]
  categories?: AdminArticleCategoryDto[]
  subcategories?: AdminArticleSubcategoryDto[]
}

async function setup(
  query: Record<string, string | string[]> = {},
  overrides: SetupOverrides = {},
) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/admin/articles',
        name: 'admin-articles',
        component: defineComponent({ render: () => null }),
      },
    ],
  })
  await router.push({ name: 'admin-articles', query })
  await router.isReady()

  let filters!: ReturnType<typeof useAdminArticleListFilters>
  mount(
    defineComponent({
      setup() {
        filters = useAdminArticleListFilters({
          articles: () => overrides.articles ?? articles,
          categories: () => overrides.categories ?? categories,
          subcategories: () => overrides.subcategories ?? subcategories,
        })
        return () => h('div')
      },
    }),
    { global: { plugins: [router] } },
  )

  return { filters, router }
}

function filteredIds(filters: ReturnType<typeof useAdminArticleListFilters>) {
  return filters.filteredArticles.value.map((item) => item.id)
}

describe('useAdminArticleListFilters', () => {
  it('shows the full list with default criteria when no query is set', async () => {
    const { filters } = await setup()

    expect(filters.criteria.value).toEqual({
      categoryId: null,
      subcategoryId: null,
      status: 'all',
      name: '',
    })
    expect(filteredIds(filters)).toEqual([1, 2, 3, 4])
    expect(filters.hasActiveFilters.value).toBe(false)
  })

  it('filters by category', async () => {
    const { filters } = await setup({ category: '1' })

    expect(filters.criteria.value.categoryId).toBe(1)
    expect(filteredIds(filters)).toEqual([1, 2])
  })

  it('matches articles without a category via the sentinel value', async () => {
    const { filters } = await setup({ category: WITHOUT_CATEGORY })

    expect(filters.criteria.value.categoryId).toBe(WITHOUT_CATEGORY)
    expect(filteredIds(filters)).toEqual([3])
  })

  it('keeps the subcategory unset and without options while without-category is selected', async () => {
    const { filters } = await setup({ category: WITHOUT_CATEGORY, subcategory: '10' })

    expect(filters.criteria.value.subcategoryId).toBeNull()
    expect(filters.subcategoryOptions.value).toEqual([])
    expect(filteredIds(filters)).toEqual([3])
  })

  it('filters by subcategory within the selected category', async () => {
    const { filters } = await setup({ category: '1', subcategory: '10' })

    expect(filters.criteria.value.subcategoryId).toBe(10)
    expect(filteredIds(filters)).toEqual([1])
  })

  it('matches articles without a subcategory via the sentinel value', async () => {
    const { filters } = await setup({ category: '1', subcategory: WITHOUT_SUBCATEGORY })

    expect(filters.criteria.value.subcategoryId).toBe(WITHOUT_SUBCATEGORY)
    expect(filteredIds(filters)).toEqual([2])
  })

  it('filters by the stored active flag', async () => {
    const active = await setup({ status: 'active' })
    expect(filteredIds(active.filters)).toEqual([1, 4])

    const inactive = await setup({ status: 'inactive' })
    expect(filteredIds(inactive.filters)).toEqual([2, 3])
  })

  it('matches names case-insensitively as trimmed substrings', async () => {
    const { filters } = await setup({ name: '  sUnSeT ' })

    expect(filteredIds(filters)).toEqual([4])
  })

  it('combines all filters conjunctively', async () => {
    const { filters } = await setup({
      category: '2',
      subcategory: '20',
      status: 'active',
      name: 'shirt',
    })

    expect(filteredIds(filters)).toEqual([4])
  })

  it('keeps the existing row order when filtering', async () => {
    const { filters } = await setup({ category: '1' })

    expect(filteredIds(filters)).toEqual(
      articles.filter((item) => item.categoryId === 1).map((item) => item.id),
    )
  })

  it('treats unparseable or unknown query values as unset', async () => {
    const nonNumeric = await setup({ category: 'abc' })
    expect(nonNumeric.filters.criteria.value.categoryId).toBeNull()
    expect(filteredIds(nonNumeric.filters)).toEqual([1, 2, 3, 4])
    expect(nonNumeric.filters.hasActiveFilters.value).toBe(false)

    const unknownCategory = await setup({ category: '999' })
    expect(unknownCategory.filters.criteria.value.categoryId).toBeNull()

    const unknownStatus = await setup({ status: 'archived' })
    expect(unknownStatus.filters.criteria.value.status).toBe('all')

    const blankName = await setup({ name: '   ' })
    expect(blankName.filters.criteria.value.name).toBe('')
    expect(blankName.filters.hasActiveFilters.value).toBe(false)
  })

  it('drops a subcategory that does not belong to the selected category', async () => {
    const { filters } = await setup({ category: '1', subcategory: '20' })

    expect(filters.criteria.value.categoryId).toBe(1)
    expect(filters.criteria.value.subcategoryId).toBeNull()
    expect(filteredIds(filters)).toEqual([1, 2])
  })

  it('ignores a subcategory when no category is selected', async () => {
    const { filters } = await setup({ subcategory: '10' })

    expect(filters.criteria.value.subcategoryId).toBeNull()
    expect(filteredIds(filters)).toEqual([1, 2, 3, 4])
  })

  it('keeps an id-based filter applied while reference lists are still empty', async () => {
    const { filters } = await setup({ category: '1' }, { categories: [], subcategories: [] })

    expect(filters.criteria.value.categoryId).toBe(1)
    expect(filteredIds(filters)).toEqual([1, 2])
  })

  it('offers only the selected category subcategories as options', async () => {
    const none = await setup()
    expect(none.filters.subcategoryOptions.value).toEqual([])

    const { filters } = await setup({ category: '1' })
    expect(filters.subcategoryOptions.value.map((item) => item.id)).toEqual([10, 11])
  })

  it('writes criteria changes to the query and omits empty values', async () => {
    const { filters, router } = await setup()

    filters.setCategoryId(1)
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ category: '1' })

    filters.setSubcategoryId(10)
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ category: '1', subcategory: '10' })

    filters.setStatus('inactive')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({
      category: '1',
      subcategory: '10',
      status: 'inactive',
    })

    filters.setStatus('all')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ category: '1', subcategory: '10' })

    filters.setName('shirt')
    await flushPromises()
    expect(router.currentRoute.value.query.name).toBe('shirt')

    filters.setName('   ')
    await flushPromises()
    expect(router.currentRoute.value.query.name).toBeUndefined()
  })

  it('clears the subcategory when the category changes', async () => {
    const { filters, router } = await setup({ category: '1', subcategory: '10' })

    filters.setCategoryId(2)
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ category: '2' })
    expect(filters.criteria.value.subcategoryId).toBeNull()
  })

  it('clears the subcategory when switching to without-category', async () => {
    const { filters, router } = await setup({ category: '1', subcategory: '10' })

    filters.setCategoryId(WITHOUT_CATEGORY)
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ category: WITHOUT_CATEGORY })
    expect(filters.criteria.value.subcategoryId).toBeNull()
  })

  it('clears category and subcategory together when the category is unset', async () => {
    const { filters, router } = await setup({ category: '1', subcategory: WITHOUT_SUBCATEGORY })

    filters.setCategoryId(null)
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({})
  })

  it('resets all filters at once while keeping unrelated query params', async () => {
    const { filters, router } = await setup({
      category: '1',
      subcategory: '10',
      status: 'active',
      name: 'foo',
      other: 'kept',
    })
    expect(filters.hasActiveFilters.value).toBe(true)

    filters.resetFilters()
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ other: 'kept' })
    expect(filters.hasActiveFilters.value).toBe(false)
  })

  it('reports active filters for every filter type', async () => {
    expect((await setup({ category: '1' })).filters.hasActiveFilters.value).toBe(true)
    expect((await setup({ category: WITHOUT_CATEGORY })).filters.hasActiveFilters.value).toBe(true)
    expect(
      (await setup({ category: '1', subcategory: WITHOUT_SUBCATEGORY })).filters.hasActiveFilters
        .value,
    ).toBe(true)
    expect((await setup({ status: 'inactive' })).filters.hasActiveFilters.value).toBe(true)
    expect((await setup({ name: 'x' })).filters.hasActiveFilters.value).toBe(true)
  })
})
