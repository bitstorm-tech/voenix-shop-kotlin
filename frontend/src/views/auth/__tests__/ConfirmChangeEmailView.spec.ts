import { flushPromises, mount } from '@vue/test-utils'
import type { Component } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AuthStatus from '@/components/auth/AuthStatus.vue'
import ConfirmChangeEmailView from '@/views/auth/ConfirmChangeEmailView.vue'
import { resetApiClientForTests } from '@/lib/api'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (key: string) => key }),
}))

function jsonResponse(body: unknown, status: number) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function stubEndpoint(endpoint: string, response: () => Response) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)

      if (path === endpoint) {
        return response()
      }

      throw new Error(`Unexpected request: ${path}`)
    }),
  )
}

async function mountAt(component: Component, path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/confirm', name: 'confirm', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', name: 'catch-all', component: { template: '<div />' } },
    ],
  })

  await router.push(path)
  await router.isReady()

  const wrapper = mount(component, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

const invalidLinkBody = {
  message: 'Invalid or expired confirmation link',
  errors: {},
  code: 'INVALID_LINK',
}

describe('ConfirmChangeEmailView', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('shows the link-specific copy for an INVALID_LINK 400', async () => {
    stubEndpoint('/api/auth/confirm-change-email', () => jsonResponse(invalidLinkBody, 400))

    const wrapper = await mountAt(
      ConfirmChangeEmailView,
      '/confirm?userId=1&newEmail=new%40example.com&token=stale',
    )

    expect(wrapper.findComponent(AuthStatus).props('message')).toBe(
      'auth.confirmChangeEmail.error.invalidLink',
    )
  })

  it('keeps the generic copy for a codeless failure', async () => {
    stubEndpoint('/api/auth/confirm-change-email', () =>
      jsonResponse({ message: 'Email already exists', errors: {} }, 409),
    )

    const wrapper = await mountAt(
      ConfirmChangeEmailView,
      '/confirm?userId=1&newEmail=new%40example.com&token=stale',
    )

    expect(wrapper.findComponent(AuthStatus).props('message')).toBe(
      'auth.confirmChangeEmail.error.message',
    )
  })
})
