import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import AdminPlaceholderSection from '../AdminPlaceholderSection.vue'

const baseProps = {
  title: 'Overview',
  highlights: ['First planned block'],
}

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/admin', component: { template: '<div />' } }],
  })
}

async function mountPlaceholder(showBackLink?: boolean) {
  const router = createTestRouter()
  await router.push('/admin')
  await router.isReady()

  return mount(AdminPlaceholderSection, {
    props: {
      ...baseProps,
      ...(showBackLink === undefined ? {} : { showBackLink }),
    },
    global: {
      plugins: [router],
    },
  })
}

describe('AdminPlaceholderSection', () => {
  it('renders the back-to-overview action by default', async () => {
    const wrapper = await mountPlaceholder()

    expect(wrapper.find('a[href="/admin"]').text()).toContain('Back to overview')
  })

  it('can hide the back-to-overview action for the overview page itself', async () => {
    const wrapper = await mountPlaceholder(false)

    expect(wrapper.find('a[href="/admin"]').exists()).toBe(false)
    expect(wrapper.find('h1').text()).toBe('Overview')
  })
})
