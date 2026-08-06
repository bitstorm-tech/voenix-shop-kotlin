import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import LoginView from '@/views/auth/LoginView.vue'
import ResendConfirmationAlert from '@/components/auth/ResendConfirmationAlert.vue'
import { resetApiClientForTests } from '@/lib/api'

const toast = vi.fn()

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (key: string) => key }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast }),
}))

const apiUser = {
  id: 1,
  email: 'customer@example.com',
  roles: ['CUSTOMER'],
  shippingAddress: null,
  billingAddress: null,
  hasSeparateBillingAddress: false,
  createdAt: '2026-01-01T00:00:00Z',
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** `/api/auth/login` answers `204` on success and the shared `ApiError` body otherwise. */
function stubLogin(loginResponse: () => Response) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input)

      if (path === '/api/auth/login') {
        return loginResponse()
      }

      if (path === '/api/auth/me') {
        return jsonResponse(apiUser)
      }

      if (path === '/api/auth/resend-confirmation') {
        return new Response(null, { status: 204 })
      }

      if (path === '/api/magic-coins/balance') {
        return jsonResponse({ balance: 0 })
      }

      throw new Error(`Unexpected request: ${path}`)
    }),
  )
}

async function mountLoginView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: LoginView },
      { path: '/forgot-password', name: 'forgot-password', component: { template: '<div />' } },
      { path: '/register', name: 'register', component: { template: '<div />' } },
      { path: '/', name: 'home', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', name: 'catch-all', component: { template: '<div />' } },
    ],
  })

  await router.push('/login')
  await router.isReady()

  const wrapper = mount(LoginView, { global: { plugins: [router] } })
  await wrapper.find('#email').setValue('customer@example.com')
  await wrapper.find('#password').setValue('secret')
  return { wrapper, router }
}

async function submitLogin(wrapper: Awaited<ReturnType<typeof mountLoginView>>['wrapper']) {
  await wrapper.find('form').trigger('submit')
  await new Promise((resolve) => setTimeout(resolve, 0))
  await wrapper.vm.$nextTick()
}

describe('LoginView', () => {
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

  it('redirects after a 204 login', async () => {
    stubLogin(() => new Response(null, { status: 204 }))
    const { wrapper, router } = await mountLoginView()

    await submitLogin(wrapper)

    expect(router.currentRoute.value.path).not.toBe('/login')
    expect(toast).not.toHaveBeenCalled()
  })

  it('shows the bad-credentials message on 401', async () => {
    stubLogin(() => jsonResponse({ message: 'Invalid email or password', errors: {} }, 401))
    const { wrapper } = await mountLoginView()

    await submitLogin(wrapper)

    expect(toast).toHaveBeenCalledWith({
      title: 'auth.login.errors.invalid',
      variant: 'destructive',
    })
    expect(wrapper.findComponent(ResendConfirmationAlert).exists()).toBe(false)
  })

  it('offers the resend path on 403 unconfirmed email', async () => {
    stubLogin(() => jsonResponse({ message: 'Email is not confirmed', errors: {} }, 403))
    const { wrapper } = await mountLoginView()

    await submitLogin(wrapper)

    expect(toast).toHaveBeenCalledWith({
      title: 'auth.login.errors.emailNotConfirmed',
      variant: 'destructive',
    })
    const alert = wrapper.findComponent(ResendConfirmationAlert)
    expect(alert.exists()).toBe(true)
    expect(alert.props('email')).toBe('customer@example.com')
  })

  it('shows the lockout message on 429', async () => {
    stubLogin(() => jsonResponse({ message: 'Too many failed login attempts', errors: {} }, 429))
    const { wrapper } = await mountLoginView()

    await submitLogin(wrapper)

    expect(toast).toHaveBeenCalledWith({
      title: 'auth.login.errors.lockedOut',
      variant: 'destructive',
    })
    expect(wrapper.findComponent(ResendConfirmationAlert).exists()).toBe(false)
  })
})
