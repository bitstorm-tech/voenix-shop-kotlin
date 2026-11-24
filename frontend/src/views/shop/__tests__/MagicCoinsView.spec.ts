import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import MagicCoinsView from '@/views/shop/MagicCoinsView.vue'
import { SelectableCard } from '@/components/ui/selectable-card'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string) => key,
  }),
}))

async function mountMagicCoinsView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/magic-coins', name: 'magic-coins', component: MagicCoinsView },
      { path: '/products', name: 'products', component: { template: '<div />' } },
    ],
  })

  await router.push('/magic-coins')
  await router.isReady()

  const wrapper = mount(MagicCoinsView, {
    global: {
      plugins: [router],
    },
  })
  await flushPromises()
  return wrapper
}

describe('MagicCoinsView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = input.toString()

        if (url === '/api/magic-coins/balance') {
          return Promise.resolve(
            new Response(JSON.stringify({ balance: 12 }), {
              status: 200,
              headers: { 'Content-Type': 'application/json' },
            }),
          )
        }

        return Promise.resolve(new Response(JSON.stringify({}), { status: 404 }))
      }),
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('shows the current balance and hides purchase packages until real checkout exists', async () => {
    const wrapper = await mountMagicCoinsView()

    expect(wrapper.get('[data-testid="magic-coins-balance"]').text()).toBe('12')
    expect(wrapper.text()).toContain('magicCoins.unavailableTitle')
    expect(wrapper.text()).toContain('magicCoins.unavailableDescription')
    expect(wrapper.findAllComponents(SelectableCard)).toHaveLength(0)
    expect(wrapper.text()).not.toContain('7,90')
  })
})
