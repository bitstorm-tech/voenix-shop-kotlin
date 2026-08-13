import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SetPasswordView from '@/views/auth/SetPasswordView.vue'
import { resetApiClientForTests } from '@/lib/api'

const toast = vi.fn()

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (key: string) => key }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast }),
}))

/** The invitation link posts to the very same endpoint as the reset link. */
function stubResetPassword(response: () => Response) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)

      if (path === '/api/auth/reset-password') {
        return response()
      }

      throw new Error(`Unexpected request: ${path}`)
    }),
  )
}

function jsonResponse(body: unknown, status: number) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/set-password', name: 'set-password', component: SetPasswordView },
      { path: '/forgot-password', name: 'forgot-password', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', name: 'catch-all', component: { template: '<div />' } },
    ],
  })
}

async function mountSetPasswordView(query = '?email=packing%40acme.example&token=link-token') {
  const router = createTestRouter()

  await router.push(`/set-password${query}`)
  await router.isReady()

  return mount(SetPasswordView, { global: { plugins: [router] } })
}

async function submit(wrapper: Awaited<ReturnType<typeof mountSetPasswordView>>) {
  await wrapper.find('#newPassword').setValue('password-2')
  await wrapper.find('#confirmPassword').setValue('password-2')
  await wrapper.find('form').trigger('submit')
  await new Promise((resolve) => setTimeout(resolve, 0))
  await wrapper.vm.$nextTick()
}

describe('SetPasswordView', () => {
  beforeEach(() => {
    resetApiClientForTests()
    setActivePinia(createPinia())
    toast.mockClear()
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('sets the password through the reset endpoint and confirms it', async () => {
    stubResetPassword(() => new Response(null, { status: 204 }))
    const wrapper = await mountSetPasswordView()

    await submit(wrapper)

    expect(wrapper.text()).toContain('auth.setPassword.success.title')
    expect(toast).not.toHaveBeenCalled()
  })

  it('shows the invitation-specific copy for an INVALID_LINK 400', async () => {
    stubResetPassword(() =>
      jsonResponse(
        { message: 'Invalid or expired password reset link', errors: {}, code: 'INVALID_LINK' },
        400,
      ),
    )
    const wrapper = await mountSetPasswordView()

    await submit(wrapper)

    expect(toast).toHaveBeenCalledWith({
      title: 'auth.setPassword.errors.invalidLink',
      variant: 'destructive',
    })
  })

  it('falls back to the backend message for a codeless 400', async () => {
    stubResetPassword(() =>
      jsonResponse({ message: 'Validation failed', errors: { newPassword: ['too short'] } }, 400),
    )
    const wrapper = await mountSetPasswordView()

    await submit(wrapper)

    expect(toast).toHaveBeenCalledWith({ title: 'Validation failed', variant: 'destructive' })
  })

  it('offers the forgot-password path instead of a form when the link is incomplete', async () => {
    const wrapper = await mountSetPasswordView('?email=packing%40acme.example')

    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.text()).toContain('auth.setPassword.invalid.title')
  })
})
