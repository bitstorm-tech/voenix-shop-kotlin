import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MagicCoinsView from '@/views/shop/MagicCoinsView.vue'
import { SelectableCard } from '@/components/ui/selectable-card'
import { magicCoinsPlans } from '@/lib/magicCoins'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({
    toast: vi.fn(),
  }),
}))

async function mountMagicCoinsView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/magic-coins', name: 'magic-coins', component: MagicCoinsView },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })

  await router.push('/magic-coins')
  await router.isReady()

  return mount(MagicCoinsView, {
    global: {
      plugins: [router],
    },
  })
}

describe('MagicCoinsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = input.toString()

        if (url === '/api/magic-coins/balance') {
          return Promise.resolve({
            ok: true,
            json: vi.fn().mockResolvedValue({ balance: 12 }),
          } as unknown as Response)
        }

        return Promise.resolve({
          ok: false,
          json: vi.fn().mockResolvedValue({}),
        } as unknown as Response)
      }),
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('uses selectable cards for plan selection', async () => {
    const wrapper = await mountMagicCoinsView()
    const cards = wrapper.findAllComponents(SelectableCard)

    expect(cards).toHaveLength(magicCoinsPlans.length)
    expect(cards[1]!.attributes('data-state')).toBe('selected')

    await cards[0]!.trigger('click')

    const updatedCards = wrapper.findAllComponents(SelectableCard)
    expect(updatedCards[0]!.attributes('data-state')).toBe('selected')
    expect(updatedCards[1]!.attributes('data-state')).toBe('unselected')
  })
})
