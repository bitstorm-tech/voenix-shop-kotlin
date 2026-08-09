import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import RegisterView from '@/views/auth/RegisterView.vue'
import ResendConfirmationAlert from '@/components/auth/ResendConfirmationAlert.vue'
import { resetApiClientForTests } from '@/lib/api'

const toast = vi.fn()

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (key: string) => key }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast }),
}))

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function stubRegister(registerResponse: () => Response) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input)

    if (path === '/api/auth/register') {
      return registerResponse()
    }

    if (path === '/api/auth/resend-confirmation') {
      return new Response(null, { status: 204 })
    }

    if (path === '/api/auth/me') {
      return jsonResponse({ message: 'Authentication required', errors: {} }, 401)
    }

    if (path === '/api/magic-coins/balance') {
      return jsonResponse({ balance: 0 })
    }

    throw new Error(`Unexpected request: ${path}`)
  })

  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

async function mountRegisterView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/register', name: 'register', component: RegisterView },
      { path: '/login', name: 'login', component: { template: '<div />' } },
    ],
  })

  await router.push('/register')
  await router.isReady()

  const wrapper = mount(RegisterView, { global: { plugins: [router] } })
  await wrapper.find('#email').setValue('new@example.com')
  await wrapper.find('#password').setValue('secret123')
  await wrapper.find('#confirmPassword').setValue('secret123')
  return wrapper
}

async function submitRegistration(wrapper: Awaited<ReturnType<typeof mountRegisterView>>) {
  await wrapper.find('form').trigger('submit')
  await new Promise((resolve) => setTimeout(resolve, 0))
  await wrapper.vm.$nextTick()
}

describe('RegisterView', () => {
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

  it('shows the check-your-inbox state after a 204 registration', async () => {
    stubRegister(() => new Response(null, { status: 204 }))
    const wrapper = await mountRegisterView()

    await submitRegistration(wrapper)

    expect(wrapper.text()).toContain('auth.register.success.title')
    expect(toast).not.toHaveBeenCalled()
  })

  it('offers the resend retry path when the confirmation mail is undeliverable (502)', async () => {
    const fetchMock = stubRegister(() =>
      jsonResponse({ message: 'Confirmation email could not be delivered', errors: {} }, 502),
    )
    const wrapper = await mountRegisterView()

    await submitRegistration(wrapper)

    expect(toast).toHaveBeenCalledWith({
      title: 'auth.register.errors.mailDeliveryFailed',
      variant: 'destructive',
    })

    const alert = wrapper.findComponent(ResendConfirmationAlert)
    expect(alert.exists()).toBe(true)

    // The account exists, so registering again would only answer 409 — the retry is a resend.
    await alert.find('button').trigger('click')
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(fetchMock.mock.calls.map(([input]) => String(input))).toContain(
      '/api/auth/resend-confirmation',
    )
  })

  it('shows the taken-address message on 409 without offering a resend', async () => {
    stubRegister(() => jsonResponse({ message: 'Email already exists', errors: {} }, 409))
    const wrapper = await mountRegisterView()

    await submitRegistration(wrapper)

    expect(toast).toHaveBeenCalledWith({
      title: 'auth.register.errors.emailTaken',
      variant: 'destructive',
    })
    expect(wrapper.findComponent(ResendConfirmationAlert).exists()).toBe(false)
  })
})
