import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchJson } from '@/lib/api'

/**
 * One login that may sign in for a supplier, as `SupplierLoginView` sends it
 * (`backend/modules/account/src/shop/voenix/account/SupplierLoginView.kt`).
 *
 * It carries no credential or lockout state on purpose: an administrator manages *who* may sign in
 * for a supplier, not how that login is doing.
 */
export interface SupplierLogin {
  userId: number
  email: string
  supplierId: number
  createdAt: string
}

/** `409`: the address already belongs to an account — a customer's or another supplier's. */
export class SupplierLoginEmailTakenError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'SupplierLoginEmailTakenError'
  }
}

/**
 * `400` with a `supplierId` field error: the supplier the login was to be created for does not
 * exist (any more). The dialog was opened from a list row, so this means the list is stale.
 */
export class SupplierLoginUnknownSupplierError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'SupplierLoginUnknownSupplierError'
  }
}

/**
 * `502`: the login **was** created, only its invitation mail did not go out. This is not a failed
 * create and must never be worded as one — a retry would answer `409`, because the login exists.
 *
 * There is no resend endpoint (decision of ticket T2). The invited person recovers through "Forgot
 * password" on the login page, or the administrator deletes the login and creates it again.
 */
export class SupplierLoginInvitationNotDeliveredError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'SupplierLoginInvitationNotDeliveredError'
  }
}

/** `404` of the delete route: the id names no supplier login — unknown, or not a supplier at all. */
export class SupplierLoginNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'SupplierLoginNotFoundError'
  }
}

/**
 * The `ApiError` → store error mapping of the create route. A plain `400` — an unusable e-mail
 * address — is *not* mapped: its field errors belong at the form field, so the original
 * {@link ApiError} is passed through unchanged.
 */
export function toCreateLoginError(error: unknown): Error {
  const message = error instanceof Error ? error.message : 'Unknown error'

  if (!(error instanceof ApiError)) {
    return error instanceof Error ? error : new Error(message)
  }

  if (error.status === 409) {
    return new SupplierLoginEmailTakenError(message)
  }

  if (error.status === 502) {
    return new SupplierLoginInvitationNotDeliveredError(message)
  }

  if (error.status === 400 && error.fieldErrors.supplierId !== undefined) {
    return new SupplierLoginUnknownSupplierError(error.fieldErrors.supplierId[0] ?? message)
  }

  return error
}

/**
 * The account lifecycle of a supplier's logins, kept apart from `stores/admin/suppliers.ts`: that
 * store owns supplier master data, this one owns who may sign in for it. They share nothing but the
 * supplier id.
 */
export const useAdminSupplierLoginsStore = defineStore('admin-supplier-logins', () => {
  const logins = ref<SupplierLogin[]>([])
  /** The supplier the current {@link logins} belong to, or `null` before the first load. */
  const loadedSupplierId = shallowRef<number | null>(null)
  const isLoading = shallowRef(false)
  const error = shallowRef<Error | null>(null)
  const isCreating = shallowRef(false)
  const deletingUserId = shallowRef<number | null>(null)

  /** The logins of one supplier. The backend answers a bare array. */
  async function fetchLogins(supplierId: number): Promise<SupplierLogin[]> {
    isLoading.value = true
    error.value = null

    try {
      const loaded = await fetchJson<SupplierLogin[]>(
        `/api/admin/supplier-logins?supplierId=${supplierId}`,
      )
      logins.value = loaded
      loadedSupplierId.value = supplierId
      return loaded
    } catch (err) {
      logins.value = []
      loadedSupplierId.value = supplierId
      error.value = err instanceof Error ? err : new Error('Unknown error')
      return []
    } finally {
      isLoading.value = false
    }
  }

  /**
   * Creates a login and mails its invitation. A `502` throws
   * {@link SupplierLoginInvitationNotDeliveredError} *after* the login was written, so the caller
   * has to reload the list in that case as well.
   */
  async function createLogin(supplierId: number, email: string): Promise<SupplierLogin> {
    isCreating.value = true

    try {
      const created = await fetchJson<SupplierLogin>('/api/admin/supplier-logins', {
        method: 'POST',
        body: { supplierId, email: email.trim() },
      })
      logins.value = [...logins.value, created]
      return created
    } catch (err) {
      throw toCreateLoginError(err)
    } finally {
      isCreating.value = false
    }
  }

  /** Deletes a login. The deletion *is* the revocation and takes effect on the next request. */
  async function deleteLogin(userId: number): Promise<void> {
    deletingUserId.value = userId

    try {
      await fetchJson<void>(`/api/admin/supplier-logins/${userId}`, {
        method: 'DELETE',
        responseType: 'void',
      })
      logins.value = logins.value.filter((login) => login.userId !== userId)
    } catch (err) {
      throw err instanceof ApiError && err.status === 404
        ? new SupplierLoginNotFoundError(err.message)
        : err
    } finally {
      deletingUserId.value = null
    }
  }

  return {
    logins,
    loadedSupplierId,
    isLoading,
    error,
    isCreating,
    deletingUserId,
    fetchLogins,
    createLogin,
    deleteLogin,
  }
})
