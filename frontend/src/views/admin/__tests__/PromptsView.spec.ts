import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import PromptsView from '../PromptsView.vue'
import { useAdminPromptCategoriesStore } from '@/stores/admin/promptCategories'
import {
  PromptNotFoundError,
  PromptOrderConflictError,
  useAdminPromptsStore,
  type AdminPromptListItemDto,
} from '@/stores/admin/prompts'

const toast = vi.fn()
vi.mock('@/composables/useToast', () => ({ useToast: () => ({ toast }) }))

const messages = {
  admin: {
    prompts: {
      title: 'All Prompts',
      reload: 'Reload',
      add: 'New Prompt',
      loadFailed: 'Failed to load prompts.',
      loading: 'Loading prompts...',
      empty: 'No prompts found.',
      errors: {
        orderChangedTitle: 'Prompt order changed',
        orderChangedDescription: 'Reloaded conflict order.',
        reorderMissingTitle: 'Prompt no longer exists',
        reorderMissingDescription: 'Reloaded order without the missing prompt.',
        reorderFailedTitle: 'Failed to reorder prompts',
        reorderFailedDescription: 'Reloaded failed order.',
      },
    },
  },
}

const prompt: AdminPromptListItemDto = {
  id: 1,
  position: 1,
  title: 'Prompt 1',
  categoryId: 1,
  categoryName: 'People',
  subcategoryId: null,
  subcategoryName: null,
  exampleImageFilename: null,
  llm: null,
  active: true,
  archived: false,
  price: null,
}

async function mountView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/admin/prompts', name: 'admin-prompts', component: PromptsView },
      {
        path: '/admin/prompts/new',
        name: 'admin-prompt-new',
        component: { template: '<div />' },
      },
      {
        path: '/admin/prompts/:id/edit',
        name: 'admin-prompt-edit',
        component: { template: '<div />' },
      },
    ],
  })
  await router.push('/admin/prompts')
  await router.isReady()

  const wrapper = mount(PromptsView, {
    global: {
      plugins: [router, createI18n({ legacy: false, locale: 'en', messages: { en: messages } })],
      stubs: {
        AdminPageHeader: { template: '<div><slot name="actions" /></div>' },
        AdminPromptsFilterBar: { template: '<div />' },
        AdminPromptsTable: {
          emits: ['edit', 'reorderPrompts'],
          template:
            '<div><button data-testid="edit" @click="$emit(\'edit\', { id: 1 })">edit</button><button data-testid="reorder" @click="$emit(\'reorderPrompts\', 2, 1)">reorder</button></div>',
        },
        Alert: { template: '<div><slot /></div>' },
        Button: { template: '<button><slot /></button>' },
        Card: { template: '<div><slot /></div>' },
      },
    },
  })
  return { wrapper, router }
}

describe('PromptsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    toast.mockReset()

    const promptsStore = useAdminPromptsStore()
    promptsStore.prompts = [prompt]
    vi.spyOn(promptsStore, 'fetchPrompts').mockResolvedValue()

    const categoriesStore = useAdminPromptCategoriesStore()
    vi.spyOn(categoriesStore, 'fetchCategories').mockResolvedValue()
    vi.spyOn(categoriesStore, 'fetchSubcategories').mockResolvedValue()
  })

  it('navigates row edits to the route-level editor', async () => {
    const { wrapper, router } = await mountView()
    await flushPromises()

    await wrapper.get('[data-testid="edit"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('admin-prompt-edit')
    expect(router.currentRoute.value.params.id).toBe('1')
  })

  it('offers the primary New Prompt route action', async () => {
    const { wrapper } = await mountView()
    await flushPromises()

    const link = wrapper.get('a[href="/admin/prompts/new"]')
    expect(link.text()).toContain('New Prompt')
  })

  it('does not reload after a successful authoritative reorder', async () => {
    const store = useAdminPromptsStore()
    const fetchPrompts = vi.mocked(store.fetchPrompts)
    const reorderPrompts = vi.spyOn(store, 'reorderPrompts').mockResolvedValue([prompt])
    const { wrapper } = await mountView()
    await flushPromises()
    fetchPrompts.mockClear()

    await wrapper.get('[data-testid="reorder"]').trigger('click')
    await flushPromises()

    expect(reorderPrompts).toHaveBeenCalledWith(2, 1)
    expect(fetchPrompts).not.toHaveBeenCalled()
    expect(toast).not.toHaveBeenCalled()
  })

  it('shows conflict feedback and reloads after a stale reorder', async () => {
    const store = useAdminPromptsStore()
    const fetchPrompts = vi.mocked(store.fetchPrompts)
    vi.spyOn(store, 'reorderPrompts').mockRejectedValue(new PromptOrderConflictError('stale'))
    const { wrapper } = await mountView()
    await flushPromises()
    fetchPrompts.mockClear()

    await wrapper.get('[data-testid="reorder"]').trigger('click')
    await flushPromises()

    expect(toast).toHaveBeenCalledWith({
      title: 'Prompt order changed',
      description: 'Reloaded conflict order.',
      variant: 'destructive',
    })
    expect(fetchPrompts).toHaveBeenCalledOnce()
  })

  it('shows dedicated feedback and reloads when a moved prompt is gone', async () => {
    const store = useAdminPromptsStore()
    const fetchPrompts = vi.mocked(store.fetchPrompts)
    vi.spyOn(store, 'reorderPrompts').mockRejectedValue(new PromptNotFoundError('Prompt not found'))
    const { wrapper } = await mountView()
    await flushPromises()
    fetchPrompts.mockClear()

    await wrapper.get('[data-testid="reorder"]').trigger('click')
    await flushPromises()

    expect(toast).toHaveBeenCalledWith({
      title: 'Prompt no longer exists',
      description: 'Reloaded order without the missing prompt.',
      variant: 'destructive',
    })
    expect(fetchPrompts).toHaveBeenCalledOnce()
  })

  it('shows general feedback and reloads after another reorder failure', async () => {
    const store = useAdminPromptsStore()
    const fetchPrompts = vi.mocked(store.fetchPrompts)
    vi.spyOn(store, 'reorderPrompts').mockRejectedValue(new Error('offline'))
    const { wrapper } = await mountView()
    await flushPromises()
    fetchPrompts.mockClear()

    await wrapper.get('[data-testid="reorder"]').trigger('click')
    await flushPromises()

    expect(toast).toHaveBeenCalledWith({
      title: 'Failed to reorder prompts',
      description: 'Reloaded failed order.',
      variant: 'destructive',
    })
    expect(fetchPrompts).toHaveBeenCalledOnce()
  })
})
