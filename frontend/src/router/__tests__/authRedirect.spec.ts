import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getDefaultAuthenticatedRedirect } from '../authRedirect'

const authStore = vi.hoisted(() => ({
  isAuthenticated: false,
}))

vi.mock('@/stores/shared/auth', () => ({
  useAuthStore: () => authStore,
}))

import { guestGuard } from '../guards'

describe('getDefaultAuthenticatedRedirect', () => {
  it('sends authenticated users to the shop landing page', () => {
    expect(getDefaultAuthenticatedRedirect()).toBe('/')
  })
})

describe('guestGuard', () => {
  const next = vi.fn()

  beforeEach(() => {
    authStore.isAuthenticated = false
    next.mockReset()
  })

  it('allows guests through to auth pages', () => {
    guestGuard({} as never, {} as never, next)

    expect(next).toHaveBeenCalledWith()
  })

  it('redirects authenticated admins to the landing page instead of /admin', () => {
    authStore.isAuthenticated = true

    guestGuard({} as never, {} as never, next)

    expect(next).toHaveBeenCalledWith('/')
  })
})
