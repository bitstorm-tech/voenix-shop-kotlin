import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchJson } from '@/lib/api'

/**
 * One access token as `GET /api/admin/access-tokens` answers it: never the token itself, which
 * leaves the server once, in the answer that creates it (`backend/src/modules/account/routes.ts`).
 */
export interface AccessToken {
  id: number
  name: string
  /** The admin who created the token and as whom it acts. */
  userEmail: string
  createdAt: string
  lastUsedAt: string | null
}

/** The create answer: the row plus the plain token, shown once and never stored here. */
export interface CreatedAccessToken extends AccessToken {
  token: string
}

/** `404` of the revoke route: the token is gone already, revoked by another admin. */
export class AccessTokenNotFoundError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'AccessTokenNotFoundError'
  }
}

const ACCESS_TOKENS_PATH = '/api/admin/access-tokens'

/**
 * The admins' access tokens for AI clients on the MCP endpoint. The store holds the rows only:
 * the plain token of a create is handed to the caller and kept nowhere else, so it is gone once
 * the page that shows it is.
 */
export const useAdminAccessTokensStore = defineStore('admin-access-tokens', () => {
  const tokens = ref<AccessToken[]>([])
  const isLoading = shallowRef(false)
  /** The failure of the last load. The view decides how to word it. */
  const error = shallowRef<Error | null>(null)
  const isCreating = shallowRef(false)
  const revokingId = shallowRef<number | null>(null)

  async function fetchTokens(): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      tokens.value = await fetchJson<AccessToken[]>(ACCESS_TOKENS_PATH)
    } catch (err) {
      tokens.value = []
      error.value = err instanceof Error ? err : new Error('Unknown error')
    } finally {
      isLoading.value = false
    }
  }

  /**
   * Creates a token and puts its row first, the newest-first order of the list. A `400` throws the
   * {@link ApiError} unchanged: its `name` field error belongs at the form field.
   */
  async function createToken(name: string): Promise<CreatedAccessToken> {
    isCreating.value = true
    try {
      const created = await fetchJson<CreatedAccessToken>(ACCESS_TOKENS_PATH, {
        method: 'POST',
        body: { name },
      })
      const { id, name: stored, userEmail, createdAt, lastUsedAt } = created
      tokens.value = [{ id, name: stored, userEmail, createdAt, lastUsedAt }, ...tokens.value]
      return created
    } finally {
      isCreating.value = false
    }
  }

  /** Revokes a token; the next request with it is refused. */
  async function revokeToken(id: number): Promise<void> {
    revokingId.value = id
    try {
      await fetchJson<void>(`${ACCESS_TOKENS_PATH}/${String(id)}`, {
        method: 'DELETE',
        responseType: 'void',
      })
      tokens.value = tokens.value.filter((token) => token.id !== id)
    } catch (err) {
      throw err instanceof ApiError && err.status === 404
        ? new AccessTokenNotFoundError(err.message)
        : err
    } finally {
      revokingId.value = null
    }
  }

  return { tokens, isLoading, error, isCreating, revokingId, fetchTokens, createToken, revokeToken }
})
