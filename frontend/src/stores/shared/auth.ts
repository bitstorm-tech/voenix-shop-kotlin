import { ref, computed } from 'vue'
import { defineStore } from 'pinia'

import { clearApiClientCache } from '@/lib/api'
import { normalizeAddress, type Address, type AddressInput } from '@/stores/shop/checkout'
import { useMagicCoinsStore } from '@/stores/shop/magicCoins'

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

interface AuthActionResult {
  success: boolean
  message: string
  code?: string
}

const postAuth = async (
  path: string,
  payload: Record<string, unknown>,
  options: {
    logLabel: string
    networkErrorMessage: string
    defaultMessage?: string
  },
): Promise<AuthActionResult> => {
  try {
    const response = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })

    const data = (await response.json()) as Partial<AuthActionResult>

    return {
      success: data.success ?? response.ok,
      message: data.message ?? options.defaultMessage ?? '',
      code: data.code,
    }
  } catch (error) {
    console.error(`${options.logLabel} error:`, error)
    return {
      success: false,
      message: options.networkErrorMessage,
    }
  }
}

export const useAuthStore = defineStore('auth', () => {
  // State
  const user = ref<User | null>(null)
  const authReady = ref(false)

  // Computed
  const isAuthenticated = computed(() => !!user.value)
  const isAdmin = computed(() => user.value?.roles.includes('ADMIN') ?? false)
  const isCustomer = computed(() => user.value?.roles.includes('CUSTOMER') ?? false)

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
      const response = await fetch('/api/auth/me')
      if (response.ok) {
        setUser(normalizeUser((await response.json()) as ApiUser))
      } else {
        setUser(null)
      }
    } catch {
      setUser(null)
    }
  }

  const login = async (email: string, password: string): Promise<AuthActionResult> => {
    const result = await postAuth(
      '/api/auth/login',
      { email, password },
      {
        logLabel: 'Login',
        networkErrorMessage: 'Login failed. Please try again.',
        defaultMessage: 'Login failed',
      },
    )

    if (result.success) {
      clearApiClientCache()
      await fetchCurrentUser()
      const coins = useMagicCoinsStore()
      coins.invalidate()
      await coins.fetchBalance()
    }

    return result
  }

  const logout = async () => {
    try {
      await fetch('/api/auth/logout', { method: 'POST' })
    } catch (error) {
      console.error('Logout error:', error)
    }
    clearApiClientCache()
    setUser(null)
    const coins = useMagicCoinsStore()
    coins.invalidate()
    await coins.fetchBalance()
  }

  const hasRole = (role: string): boolean => {
    return user.value?.roles.includes(role) ?? false
  }

  const confirmEmail = async (userId: number, token: string): Promise<AuthActionResult> => {
    return postAuth(
      '/api/auth/confirm-email',
      { userId, token },
      {
        logLabel: 'Email confirmation',
        networkErrorMessage: 'Email confirmation failed. Please try again.',
      },
    )
  }

  const resendConfirmation = async (email: string): Promise<AuthActionResult> => {
    return postAuth(
      '/api/auth/resend-confirmation',
      { email },
      {
        logLabel: 'Resend confirmation',
        networkErrorMessage: 'Failed to resend confirmation email. Please try again.',
      },
    )
  }

  const forgotPassword = async (email: string): Promise<AuthActionResult> => {
    return postAuth(
      '/api/auth/forgot-password',
      { email },
      {
        logLabel: 'Forgot password',
        networkErrorMessage: 'Failed to request password reset. Please try again.',
      },
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
      {
        logLabel: 'Reset password',
        networkErrorMessage: 'Failed to reset password. Please try again.',
      },
    )
  }

  const register = async (email: string, password: string): Promise<AuthActionResult> => {
    return postAuth(
      '/api/auth/register',
      { email, password },
      {
        logLabel: 'Registration',
        networkErrorMessage: 'Registration failed. Please try again.',
      },
    )
  }

  const updateProfile = async (
    data: Record<string, unknown>,
  ): Promise<{ success: boolean; message: string }> => {
    try {
      const response = await fetch('/api/auth/profile', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
      })

      const responseData = await response.json()

      if (response.ok) {
        setUser(normalizeUser(responseData as ApiUser))
        return { success: true, message: 'Profile updated' }
      }

      return {
        success: false,
        message: responseData.message ?? `HTTP ${response.status}`,
      }
    } catch (error) {
      console.error('Profile update error:', error)
      return { success: false, message: 'Profile update failed. Please try again.' }
    }
  }

  const changeEmail = async (
    newEmail: string,
    currentPassword: string,
  ): Promise<AuthActionResult> => {
    return postAuth(
      '/api/auth/change-email',
      { newEmail, currentPassword },
      {
        logLabel: 'Change email',
        networkErrorMessage: 'Failed to change email. Please try again.',
      },
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
      {
        logLabel: 'Confirm change email',
        networkErrorMessage: 'Email change confirmation failed. Please try again.',
      },
    )
  }

  const changePassword = async (
    currentPassword: string,
    newPassword: string,
  ): Promise<AuthActionResult> => {
    return postAuth(
      '/api/auth/change-password',
      { currentPassword, newPassword },
      {
        logLabel: 'Change password',
        networkErrorMessage: 'Failed to change password. Please try again.',
      },
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
