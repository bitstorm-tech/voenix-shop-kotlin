import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import { useAdminPromptListFilters, WITHOUT_SUBCATEGORY } from '../useAdminPromptListFilters'
import type {
  AdminPromptCategoryDto,
  AdminPromptSubcategoryDto,
} from '@/stores/admin/promptCategories'
import type { AdminPromptListItemDto } from '@/stores/admin/prompts'

function category(id: number, name: string, position: number): AdminPromptCategoryDto {
  return { id, name, position, active: true }
}

function subcategory(
  id: number,
  category: AdminPromptCategoryDto,
  position: number,
): AdminPromptSubcategoryDto {
  return {
    id,
    categoryId: category.id,
    name: `Sub ${id}`,
    description: null,
    position,
    active: true,
  }
}

const people = category(1, 'People', 1)
const places = category(2, 'Places', 2)
const categories = [people, places]
const subcategories = [
  subcategory(10, people, 1),
  subcategory(11, people, 2),
  subcategory(20, places, 1),
]

function prompt(
  id: number,
  overrides: Partial<AdminPromptListItemDto> = {},
): AdminPromptListItemDto {
  return {
    id,
    position: id,
    title: `Prompt ${id}`,
    categoryId: people.id,
    categoryName: people.name,
    subcategoryId: null,
    subcategoryName: null,
    exampleImageFilename: null,
    llm: null,
    active: true,
    archived: false,
    price: null,
    ...overrides,
  }
}

const prompts = [
  prompt(1, { subcategoryId: 10, subcategoryName: 'Sub 10' }),
  prompt(2, { active: false }),
  prompt(3, { subcategoryId: 11, subcategoryName: 'Sub 11', archived: true }),
  prompt(4, {
    title: 'Sunset Beach',
    categoryId: places.id,
    categoryName: places.name,
    subcategoryId: 20,
    subcategoryName: 'Sub 20',
  }),
]

interface SetupOverrides {
  prompts?: AdminPromptListItemDto[]
  categories?: AdminPromptCategoryDto[]
  subcategories?: AdminPromptSubcategoryDto[]
}

async function setup(
  query: Record<string, string | string[]> = {},
  overrides: SetupOverrides = {},
) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/admin/prompts',
        name: 'admin-prompts',
        component: defineComponent({ render: () => null }),
      },
    ],
  })
  await router.push({ name: 'admin-prompts', query })
  await router.isReady()

  let filters!: ReturnType<typeof useAdminPromptListFilters>
  mount(
    defineComponent({
      setup() {
        filters = useAdminPromptListFilters({
          prompts: () => overrides.prompts ?? prompts,
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

function filteredIds(filters: ReturnType<typeof useAdminPromptListFilters>) {
  return filters.filteredPrompts.value.map((item) => item.id)
}

describe('useAdminPromptListFilters', () => {
  it('shows the full list with default criteria when no query is set', async () => {
    const { filters } = await setup()

    expect(filters.criteria.value).toEqual({
      categoryId: null,
      subcategoryId: null,
      status: 'all',
      title: '',
    })
    expect(filteredIds(filters)).toEqual([1, 2, 3, 4])
    expect(filters.hasActiveFilters.value).toBe(false)
  })

  it('filters by category', async () => {
    const { filters } = await setup({ category: '1' })

    expect(filters.criteria.value.categoryId).toBe(1)
    expect(filteredIds(filters)).toEqual([1, 2, 3])
  })

  it('filters by subcategory within the selected category', async () => {
    const { filters } = await setup({ category: '1', subcategory: '10' })

    expect(filters.criteria.value.subcategoryId).toBe(10)
    expect(filteredIds(filters)).toEqual([1])
  })

  it('matches prompts without a subcategory via the sentinel value', async () => {
    const { filters } = await setup({ category: '1', subcategory: WITHOUT_SUBCATEGORY })

    expect(filters.criteria.value.subcategoryId).toBe(WITHOUT_SUBCATEGORY)
    expect(filteredIds(filters)).toEqual([2])
  })

  it('filters by each derived status', async () => {
    const active = await setup({ status: 'active' })
    expect(filteredIds(active.filters)).toEqual([1, 4])

    const inactive = await setup({ status: 'inactive' })
    expect(filteredIds(inactive.filters)).toEqual([2])

    const archived = await setup({ status: 'archived' })
    expect(filteredIds(archived.filters)).toEqual([3])
  })

  it('treats an archived-and-active prompt as archived only', async () => {
    const archivedActive = prompt(5, { active: true, archived: true })
    const { filters } = await setup({ status: 'archived' }, { prompts: [archivedActive] })
    expect(filteredIds(filters)).toEqual([5])

    const activeOnly = await setup({ status: 'active' }, { prompts: [archivedActive] })
    expect(filteredIds(activeOnly.filters)).toEqual([])
  })

  it('matches titles case-insensitively as trimmed substrings', async () => {
    const { filters } = await setup({ title: '  sUnSeT ' })

    expect(filteredIds(filters)).toEqual([4])
  })

  it('combines all filters conjunctively', async () => {
    const { filters } = await setup({
      category: '2',
      subcategory: '20',
      status: 'active',
      title: 'beach',
    })

    expect(filteredIds(filters)).toEqual([4])
  })

  it('keeps the existing row order when filtering', async () => {
    const { filters } = await setup({ category: '1' })

    expect(filteredIds(filters)).toEqual(
      prompts.filter((item) => item.categoryId === 1).map((item) => item.id),
    )
  })

  it('treats unparseable or unknown query values as unset', async () => {
    const nonNumeric = await setup({ category: 'abc' })
    expect(nonNumeric.filters.criteria.value.categoryId).toBeNull()
    expect(filteredIds(nonNumeric.filters)).toEqual([1, 2, 3, 4])
    expect(nonNumeric.filters.hasActiveFilters.value).toBe(false)

    const unknownCategory = await setup({ category: '999' })
    expect(unknownCategory.filters.criteria.value.categoryId).toBeNull()

    const unknownStatus = await setup({ status: 'bogus' })
    expect(unknownStatus.filters.criteria.value.status).toBe('all')

    const blankTitle = await setup({ title: '   ' })
    expect(blankTitle.filters.criteria.value.title).toBe('')
    expect(blankTitle.filters.hasActiveFilters.value).toBe(false)
  })

  it('drops a subcategory that does not belong to the selected category', async () => {
    const { filters } = await setup({ category: '1', subcategory: '20' })

    expect(filters.criteria.value.categoryId).toBe(1)
    expect(filters.criteria.value.subcategoryId).toBeNull()
    expect(filteredIds(filters)).toEqual([1, 2, 3])
  })

  it('ignores a subcategory when no category is selected', async () => {
    const { filters } = await setup({ subcategory: '10' })

    expect(filters.criteria.value.subcategoryId).toBeNull()
    expect(filteredIds(filters)).toEqual([1, 2, 3, 4])
  })

  it('keeps an id-based filter applied while reference lists are still empty', async () => {
    const { filters } = await setup({ category: '1' }, { categories: [], subcategories: [] })

    expect(filters.criteria.value.categoryId).toBe(1)
    expect(filteredIds(filters)).toEqual([1, 2, 3])
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

    filters.setStatus('archived')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({
      category: '1',
      subcategory: '10',
      status: 'archived',
    })

    filters.setStatus('all')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ category: '1', subcategory: '10' })

    filters.setTitle('beach')
    await flushPromises()
    expect(router.currentRoute.value.query.title).toBe('beach')

    filters.setTitle('   ')
    await flushPromises()
    expect(router.currentRoute.value.query.title).toBeUndefined()
  })

  it('clears the subcategory when the category changes', async () => {
    const { filters, router } = await setup({ category: '1', subcategory: '10' })

    filters.setCategoryId(2)
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({ category: '2' })
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
      title: 'foo',
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
    expect(
      (await setup({ category: '1', subcategory: WITHOUT_SUBCATEGORY })).filters.hasActiveFilters
        .value,
    ).toBe(true)
    expect((await setup({ status: 'inactive' })).filters.hasActiveFilters.value).toBe(true)
    expect((await setup({ title: 'x' })).filters.hasActiveFilters.value).toBe(true)
  })
})
