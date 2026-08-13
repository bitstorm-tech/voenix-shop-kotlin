import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getDefaultAuthenticatedRedirect } from '../authRedirect'

const authStore = vi.hoisted(() => ({
  isAuthenticated: false,
  isAdmin: false,
  isSupplier: false,
}))

vi.mock('@/stores/shared/auth', () => ({
  useAuthStore: () => authStore,
}))

import { guestGuard } from '../guards'

describe('getDefaultAuthenticatedRedirect', () => {
  beforeEach(() => {
    authStore.isAuthenticated = false
    authStore.isAdmin = false
    authStore.isSupplier = false
  })

  it('sends a plain customer to the shop landing page', () => {
    expect(getDefaultAuthenticatedRedirect()).toBe('/')
  })

  it('keeps sending an admin to the shop landing page', () => {
    authStore.isAdmin = true

    expect(getDefaultAuthenticatedRedirect()).toBe('/')
  })

  it('sends a supplier login to its job list', () => {
    authStore.isSupplier = true

    expect(getDefaultAuthenticatedRedirect()).toBe('/supplier/jobs')
  })

  it('keeps the shop landing page for a user who is both admin and supplier', () => {
    authStore.isAdmin = true
    authStore.isSupplier = true

    expect(getDefaultAuthenticatedRedirect()).toBe('/')
  })
})

describe('guestGuard', () => {
  const next = vi.fn()

  beforeEach(() => {
    authStore.isAuthenticated = false
    authStore.isAdmin = false
    authStore.isSupplier = false
    next.mockReset()
  })

  it('allows guests through to auth pages', () => {
    guestGuard({} as never, {} as never, next)

    expect(next).toHaveBeenCalledWith()
  })

  it('redirects authenticated admins to the landing page instead of /admin', () => {
    authStore.isAuthenticated = true
    authStore.isAdmin = true

    guestGuard({} as never, {} as never, next)

    expect(next).toHaveBeenCalledWith('/')
  })

  it('redirects an authenticated supplier login to its job list', () => {
    authStore.isAuthenticated = true
    authStore.isSupplier = true

    guestGuard({} as never, {} as never, next)

    expect(next).toHaveBeenCalledWith('/supplier/jobs')
  })
})
