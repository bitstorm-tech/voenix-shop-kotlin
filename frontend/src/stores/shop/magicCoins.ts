import { shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { ApiError, fetchJson } from '@/lib/api'

interface MagicCoinsBalanceResponse {
  balance: number
}

export const useMagicCoinsStore = defineStore('magicCoins', () => {
  const balance = shallowRef<number | null>(null)
  const isLoading = shallowRef(false)
  const error = shallowRef<string | null>(null)

  let pendingRequest: Promise<void> | null = null
  let activeController: AbortController | null = null

  function invalidate(): void {
    activeController?.abort()
    activeController = null
    pendingRequest = null
  }

  async function fetchBalance(): Promise<void> {
    if (pendingRequest) return pendingRequest

    const controller = new AbortController()
    activeController = controller
    isLoading.value = true
    error.value = null

    pendingRequest = (async () => {
      try {
        const data = await fetchJson<MagicCoinsBalanceResponse>('/api/magic-coins/balance', {
          cache: 'no-store',
          signal: controller.signal,
        })
        if (activeController !== controller) return
        balance.value = data.balance
        error.value = null
      } catch (err) {
        if (activeController !== controller) return
        balance.value = null
        error.value = magicCoinsErrorMessage(err)
      } finally {
        if (activeController === controller) {
          isLoading.value = false
          activeController = null
          pendingRequest = null
        }
      }
    })()

    return pendingRequest
  }

  return {
    balance,
    isLoading,
    error,
    fetchBalance,
    invalidate,
  }
})

function magicCoinsErrorMessage(error: unknown) {
  if (error instanceof ApiError) {
    const hasParsedMessage = Boolean(
      error.details?.detail || error.details?.message || error.details?.title,
    )
    return hasParsedMessage ? error.message : `HTTP ${error.status}`
  }

  return error instanceof Error ? error.message : 'Failed to load Magic Coins'
}
