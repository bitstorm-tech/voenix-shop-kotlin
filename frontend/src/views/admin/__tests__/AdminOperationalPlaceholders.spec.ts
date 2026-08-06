import { mount } from '@vue/test-utils'
import type { Component } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import IssuesView from '../IssuesView.vue'

async function mountView(component: Component) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/admin', component: { template: '<div />' } }],
  })

  await router.push('/admin')
  await router.isReady()

  return mount(component, {
    global: {
      plugins: [router],
    },
  })
}

describe('Admin operational placeholder views', () => {
  it.each([
    {
      component: IssuesView,
      title: 'Issues',
    },
  ])('renders $title with a back action', async (testCase) => {
    const wrapper = await mountView(testCase.component)

    expect(wrapper.find('h1').text()).toBe(testCase.title)
    expect(wrapper.find('a[href="/admin"]').text()).toContain('Back to overview')
  })
})
