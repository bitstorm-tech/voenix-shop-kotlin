import type { NavigationGuardNext, RouteLocationNormalized } from 'vue-router'
import { useAuthStore } from '@/stores/shared/auth'
import { getDefaultAuthenticatedRedirect } from './authRedirect'

/**
 * Authentication guard - requires user to be logged in
 */
export const authGuard = (
  to: RouteLocationNormalized,
  from: RouteLocationNormalized,
  next: NavigationGuardNext,
) => {
  const authStore = useAuthStore()

  if (!authStore.isAuthenticated) {
    // Redirect to login with return URL
    next({
      path: '/login',
      query: { redirect: to.fullPath },
    })
  } else {
    next()
  }
}

/**
 * Admin guard - requires user to be logged in AND have admin role
 */
export const adminGuard = (
  to: RouteLocationNormalized,
  from: RouteLocationNormalized,
  next: NavigationGuardNext,
) => {
  const authStore = useAuthStore()

  if (!authStore.isAuthenticated) {
    // Not logged in - redirect to login
    next({
      path: '/login',
      query: { redirect: to.fullPath },
    })
  } else if (!authStore.isAdmin) {
    // Logged in but not admin - redirect to home
    next({
      path: '/',
      query: { error: 'unauthorized' },
    })
  } else {
    next()
  }
}

/**
 * Supplier guard - requires user to be logged in AND have the supplier role
 *
 * An admin alone does not pass: the supplier area answers for exactly one supplier, and the
 * backend resolves *which* one from the caller's own account. Admins have their own all-suppliers
 * view under `/admin`.
 */
export const supplierGuard = (
  to: RouteLocationNormalized,
  from: RouteLocationNormalized,
  next: NavigationGuardNext,
) => {
  const authStore = useAuthStore()

  if (!authStore.isAuthenticated) {
    // Not logged in - redirect to login
    next({
      path: '/login',
      query: { redirect: to.fullPath },
    })
  } else if (!authStore.hasRole('SUPPLIER')) {
    // Logged in but not a supplier login - redirect to home
    next({
      path: '/',
      query: { error: 'unauthorized' },
    })
  } else {
    next()
  }
}

/**
 * Guest guard - only allows unauthenticated users (for login page)
 */
export const guestGuard = (
  to: RouteLocationNormalized,
  from: RouteLocationNormalized,
  next: NavigationGuardNext,
) => {
  const authStore = useAuthStore()

  if (authStore.isAuthenticated) {
    next(getDefaultAuthenticatedRedirect())
  } else {
    next()
  }
}
