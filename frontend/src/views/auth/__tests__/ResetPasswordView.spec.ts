import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ResetPasswordView from '@/views/auth/ResetPasswordView.vue'
import { resetApiClientForTests } from '@/lib/api'

const toast = vi.fn()

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (key: string) => key }),
}))

vi.mock('@/composables/useToast', () => ({
  useToast: () => ({ toast }),
}))

/** `/api/auth/reset-password` answers `204` on success and the shared `ApiError` body otherwise. */
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

async function mountResetPasswordView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/reset-password', name: 'reset-password', component: ResetPasswordView },
      { path: '/forgot-password', name: 'forgot-password', component: { template: '<div />' } },
      { path: '/login', name: 'login', component: { template: '<div />' } },
      { path: '/:pathMatch(.*)*', name: 'catch-all', component: { template: '<div />' } },
    ],
  })

  await router.push('/reset-password?email=customer%40example.com&token=link-token')
  await router.isReady()

  const wrapper = mount(ResetPasswordView, { global: { plugins: [router] } })
  await wrapper.find('#newPassword').setValue('password-2')
  await wrapper.find('#confirmPassword').setValue('password-2')
  return wrapper
}

async function submit(wrapper: Awaited<ReturnType<typeof mountResetPasswordView>>) {
  await wrapper.find('form').trigger('submit')
  await new Promise((resolve) => setTimeout(resolve, 0))
  await wrapper.vm.$nextTick()
}

describe('ResetPasswordView', () => {
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

  it('shows the link-specific copy for an INVALID_LINK 400', async () => {
    stubResetPassword(() =>
      jsonResponse(
        { message: 'Invalid or expired password reset link', errors: {}, code: 'INVALID_LINK' },
        400,
      ),
    )
    const wrapper = await mountResetPasswordView()

    await submit(wrapper)

    expect(toast).toHaveBeenCalledWith({
      title: 'auth.resetPassword.errors.invalidLink',
      variant: 'destructive',
    })
  })

  it('falls back to the backend message for a codeless 400', async () => {
    stubResetPassword(() =>
      jsonResponse({ message: 'Validation failed', errors: { newPassword: ['too short'] } }, 400),
    )
    const wrapper = await mountResetPasswordView()

    await submit(wrapper)

    expect(toast).toHaveBeenCalledWith({
      title: 'Validation failed',
      variant: 'destructive',
    })
  })
})
