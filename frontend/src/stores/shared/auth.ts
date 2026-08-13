import { ref, computed } from 'vue'
import { defineStore } from 'pinia'

import { ApiError, clearApiClientCache, fetchJson, type ApiFieldErrors } from '@/lib/api'
import { useCartStore } from '@/stores/shop/cart'
import { normalizeAddress, type Address, type AddressInput } from '@/stores/shop/checkout'
import { useMagicCoinsStore } from '@/stores/shop/magicCoins'
import { useOrdersStore } from '@/stores/shop/orders'

export interface User {
  id: number
  email: string
  roles: string[]
  shippingAddress: Address | null
  billingAddress: Address | null
  hasSeparateBillingAddress: boolean
  createdAt: string
}

type ApiUser = Omit<User, 'shippingAddress' | 'billingAddress'> & {
  shippingAddress: AddressInput | null
  billingAddress: AddressInput | null
}

/**
 * A failed auth call. The Kotlin backend answers the shared `ApiError` body, so the HTTP
 * `status` is the discriminator on most `/api/auth` routes; the three link flows additionally
 * carry the machine-readable `code` {@link INVALID_LINK_CODE}
 * (`docs/dev/backend/account-package.md`). `status` is `null` when the request never reached
 * the backend at all.
 *
 * `message` is the backend's own English text and is meant as a fallback: views should map the
 * statuses they know to localized copy and only fall back to `message` for the rest.
 */
export interface AuthActionError {
  status: number | null
  code: string | null
  message: string
  fieldErrors: ApiFieldErrors
}

export type AuthActionResult = { success: true } | { success: false; error: AuthActionError }

/** `502` means the account operation itself worked but its e-mail could not be delivered. */
export const MAIL_DELIVERY_FAILED_STATUS = 502

/**
 * The `ApiError.code` that `confirm-email`, `reset-password`, and `confirm-change-email` answer
 * for an invalid or expired link — the one case those `400`s can be told apart from a plain
 * input validation failure. It says nothing about *why* the link failed.
 */
export const INVALID_LINK_CODE = 'INVALID_LINK'

function toAuthActionError(error: unknown): AuthActionError {
  if (error instanceof ApiError) {
    return {
      status: error.status,
      code: error.code,
      message: error.message,
      fieldErrors: error.fieldErrors,
    }
  }

  return { status: null, code: null, message: '', fieldErrors: {} }
}

/**
 * Posts to an `/api/auth` route whose success is `204 No Content`: the status *is* the answer,
 * there is no body to read.
 *
 * `anonymous` routes live outside the authenticated subtree and are not CSRF protected
 * (`AccountRoutes.installAnonymousRoutes`), so they skip the antiforgery round trip.
 */
const postAuth = async (
  path: string,
  payload: Record<string, unknown>,
  options: { logLabel: string; anonymous: boolean },
): Promise<AuthActionResult> => {
  try {
    await fetchJson<void>(path, {
      method: 'POST',
      body: payload,
      responseType: 'void',
      skipAntiforgery: options.anonymous,
    })
    return { success: true }
  } catch (error) {
    console.error(`${options.logLabel} error:`, error)
    return { success: false, error: toAuthActionError(error) }
  }
}

/**
 * Refetches the state that belongs to the *current* identity after a transition between two of
 * them. Login, logout and registration each move the browser into another backend context, so
 * every answer loaded for the previous one is stale by definition and is re-asked instead of
 * reused or adjusted locally.
 *
 * The frontend deliberately makes no assumption about *what* the new answer contains — it asks
 * again and shows whatever the backend returns.
 */
async function refetchIdentityScopedState(options: {
  cart?: boolean
  magicCoins?: boolean
  orders?: boolean
}): Promise<void> {
  const refetches: Promise<unknown>[] = []

  if (options.cart) {
    refetches.push(useCartStore().fetchCart())
  }

  if (options.magicCoins) {
    // `invalidate()` drops an in-flight request of the old context; without it the deduplication
    // in the Magic Coins store would hand out that stale answer to this refetch.
    const coins = useMagicCoinsStore()
    coins.invalidate()
    refetches.push(coins.fetchBalance())
  }

  if (options.orders) {
    refetches.push(useOrdersStore().fetchOrders())
  }

  await Promise.all(refetches)
}

export const useAuthStore = defineStore('auth', () => {
  // State
  const user = ref<User | null>(null)
  const authReady = ref(false)

  // Computed
  const isAuthenticated = computed(() => !!user.value)
  const isAdmin = computed(() => user.value?.roles.includes('ADMIN') ?? false)
  const isCustomer = computed(() => user.value?.roles.includes('CUSTOMER') ?? false)
  /**
   * A supplier login, i.e. a user the backend linked to a supplier (`users.supplier_id`). It opens
   * the `/supplier` area; whether a *particular* request is allowed is still decided per request by
   * the backend, which resolves the link freshly instead of trusting the cookie's roles.
   */
  const isSupplier = computed(() => user.value?.roles.includes('SUPPLIER') ?? false)

  function normalizeUser(apiUser: ApiUser): User {
    return {
      ...apiUser,
      shippingAddress: apiUser.shippingAddress ? normalizeAddress(apiUser.shippingAddress) : null,
      billingAddress: apiUser.billingAddress ? normalizeAddress(apiUser.billingAddress) : null,
    }
  }

  function setUser(nextUser: User | null) {
    const previousUserId = user.value?.id ?? null
    const nextUserId = nextUser?.id ?? null

    user.value = nextUser

    if (previousUserId !== nextUserId) {
      clearApiClientCache()
    }
  }

  // Actions
  const fetchCurrentUser = async () => {
    try {
      setUser(normalizeUser(await fetchJson<ApiUser>('/api/auth/me')))
    } catch {
      // `401` is the normal answer for a visitor without a session.
      setUser(null)
    }
  }

  const login = async (email: string, password: string): Promise<AuthActionResult> => {
    const result = await postAuth(
      '/api/auth/login',
      { email, password },
      { logLabel: 'Login', anonymous: true },
    )

    if (result.success) {
      // `fetchCurrentUser` hands the signed-in user to `setUser`, which clears the cache on the
      // identity change — nothing has to be dropped here.
      await fetchCurrentUser()
      // The signed-in customer sees their own Magic Coins balance. The guest balance is not
      // theirs — it stays on the guest identity and is reachable again after a logout.
      await refetchIdentityScopedState({ cart: true, magicCoins: true, orders: true })
    }

    return result
  }

  const logout = async () => {
    try {
      await fetchJson<void>('/api/auth/logout', { method: 'POST', responseType: 'void' })
    } catch (error) {
      console.error('Logout error:', error)
    }
    clearApiClientCache()
    setUser(null)
    // The backend keeps the guest cookie across a logout, but the anonymous context it addresses
    // is a different identity than the customer who just left, so cart and balance are re-asked.
    // The order list is not: it exists for signed-in customers only and is reset instead.
    useOrdersStore().$reset()
    await refetchIdentityScopedState({ cart: true, magicCoins: true })
  }

  const hasRole = (role: string): boolean => {
    return user.value?.roles.includes(role) ?? false
  }

  const confirmEmail = async (userId: number, token: string): Promise<AuthActionResult> => {
    return postAuth(
      '/api/auth/confirm-email',
      { userId, token },
      { logLabel: 'Email confirmation', anonymous: true },
    )
  }

  const resendConfirmation = async (email: string): Promise<AuthActionResult> => {
    return postAuth(
      '/api/auth/resend-confirmation',
      { email },
      { logLabel: 'Resend confirmation', anonymous: true },
    )
  }

  const forgotPassword = async (email: string): Promise<AuthActionResult> => {
    return postAuth(
      '/api/auth/forgot-password',
      { email },
      { logLabel: 'Forgot password', anonymous: true },
    )
  }

  const resetPassword = async (
    email: string,
    token: string,
    newPassword: string,
  ): Promise<AuthActionResult> => {
    return postAuth(
      '/api/auth/reset-password',
      { email, token, newPassword },
      { logLabel: 'Reset password', anonymous: true },
    )
  }

  const register = async (email: string, password: string): Promise<AuthActionResult> => {
    const result = await postAuth(
      '/api/auth/register',
      { email, password },
      { logLabel: 'Registration', anonymous: true },
    )

    if (result.success) {
      // A registration signs nobody in, but it changes what the backend will answer for this
      // browser's identity-scoped state, so the cart must come from a fresh read rather than from
      // what was on screen.
      await refetchIdentityScopedState({ cart: true })
    }

    return result
  }

  /** The one auth mutation with a body: `200` answers the updated `AccountProfile`. */
  const updateProfile = async (data: Record<string, unknown>): Promise<AuthActionResult> => {
    try {
      const profile = await fetchJson<ApiUser>('/api/auth/profile', {
        method: 'PUT',
        body: data,
      })
      setUser(normalizeUser(profile))
      return { success: true }
    } catch (error) {
      console.error('Profile update error:', error)
      return { success: false, error: toAuthActionError(error) }
    }
  }

  const changeEmail = async (
    newEmail: string,
    currentPassword: string,
  ): Promise<AuthActionResult> => {
    return postAuth(
      '/api/auth/change-email',
      { newEmail, currentPassword },
      { logLabel: 'Change email', anonymous: false },
    )
  }

  const confirmChangeEmail = async (
    userId: number,
    newEmail: string,
    token: string,
  ): Promise<AuthActionResult> => {
    return postAuth(
      '/api/auth/confirm-change-email',
      { userId, newEmail, token },
      { logLabel: 'Confirm change email', anonymous: true },
    )
  }

  const changePassword = async (
    currentPassword: string,
    newPassword: string,
  ): Promise<AuthActionResult> => {
    return postAuth(
      '/api/auth/change-password',
      { currentPassword, newPassword },
      { logLabel: 'Change password', anonymous: false },
    )
  }

  // Initialize: check session via cookie
  const authReadyPromise = fetchCurrentUser().finally(() => {
    authReady.value = true
  })

  // Clean up old localStorage data from mock auth
  if (typeof window !== 'undefined' && typeof localStorage !== 'undefined') {
    localStorage.removeItem('user')
    localStorage.removeItem('token')
  }

  return {
    // State
    user,
    authReady,
    authReadyPromise,
    // Computed
    isAuthenticated,
    isAdmin,
    isCustomer,
    isSupplier,
    // Actions
    login,
    logout,
    hasRole,
    register,
    confirmEmail,
    resendConfirmation,
    forgotPassword,
    resetPassword,
    fetchCurrentUser,
    updateProfile,
    changeEmail,
    confirmChangeEmail,
    changePassword,
  }
})
