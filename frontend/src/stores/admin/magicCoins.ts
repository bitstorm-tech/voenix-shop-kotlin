import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { fetchJson } from '@/lib/api'

export type AdminMagicCoinRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export interface AdminMagicCoinRequest {
  id: number
  userId: number
  userEmail: string
  userName: string | null
  accountCreatedAt: string | null
  currentBalance: number
  coinsFirstSpentAt: string | null
  coinsLastUpdatedAt: string | null
  paidOrderCount: number
  activeCartItemCount: number
  totalRequestCount: number
  totalApprovedCoins: number
  status: AdminMagicCoinRequestStatus
  grantedCoins: number | null
  blockedUser: boolean
  decidedByEmail: string | null
  createdAt: string
  decidedAt: string | null
  isCurrentlyBlocked: boolean
}

export interface AdminBlockedMagicCoinAccount {
  userId: number
  userEmail: string
  userName: string | null
  accountCreatedAt: string | null
  currentBalance: number
  paidOrderCount: number
  blockedAt: string
  blockedByEmail: string | null
}

export interface ApproveMagicCoinRequestResponse {
  id: number
  status: 'APPROVED'
  grantedCoins: number
  newBalance: number
  decidedAt: string
}

export interface RejectMagicCoinRequestResponse {
  id: number
  status: 'REJECTED'
  blockedUser: boolean
  decidedAt: string
}

export interface ManualMagicCoinGrantResponse {
  userId: number
  userEmail: string
  grantedCoins: number
  newBalance: number
}

export const useAdminMagicCoinsStore = defineStore('adminMagicCoins', () => {
  const requests = ref<AdminMagicCoinRequest[]>([])
  const blocks = ref<AdminBlockedMagicCoinAccount[]>([])
  const isLoadingRequests = shallowRef(false)
  const isLoadingBlocks = shallowRef(false)
  const isSubmittingDecision = shallowRef(false)
  const isGranting = shallowRef(false)
  const unblockingUserId = shallowRef<number | null>(null)
  const requestsError = shallowRef<Error | null>(null)
  const blocksError = shallowRef<Error | null>(null)

  async function fetchRequests(status: 'PENDING' | 'DECIDED') {
    isLoadingRequests.value = true
    requestsError.value = null
    try {
      requests.value = await fetchJson<AdminMagicCoinRequest[]>(
        `/api/admin/magic-coins/requests?status=${status}`,
        { cache: 'no-store' },
      )
    } catch (err) {
      requestsError.value =
        err instanceof Error ? err : new Error('Magic Coin requests could not be loaded.')
    } finally {
      isLoadingRequests.value = false
    }
  }

  async function fetchBlocks() {
    isLoadingBlocks.value = true
    blocksError.value = null
    try {
      blocks.value = await fetchJson<AdminBlockedMagicCoinAccount[]>(
        '/api/admin/magic-coins/blocks',
        { cache: 'no-store' },
      )
    } catch (err) {
      blocksError.value =
        err instanceof Error ? err : new Error('Blocked accounts could not be loaded.')
    } finally {
      isLoadingBlocks.value = false
    }
  }

  async function approveRequest(id: number, amount: number) {
    isSubmittingDecision.value = true
    try {
      const response = await fetchJson<ApproveMagicCoinRequestResponse>(
        `/api/admin/magic-coins/requests/${id}/approve`,
        {
          method: 'POST',
          body: { amount },
        },
      )
      requests.value = requests.value.filter((request) => request.id !== id)
      return response
    } finally {
      isSubmittingDecision.value = false
    }
  }

  async function rejectRequest(id: number, blockUser: boolean) {
    isSubmittingDecision.value = true
    try {
      const response = await fetchJson<RejectMagicCoinRequestResponse>(
        `/api/admin/magic-coins/requests/${id}/reject`,
        {
          method: 'POST',
          body: { blockUser },
        },
      )
      requests.value = requests.value.filter((request) => request.id !== id)
      return response
    } finally {
      isSubmittingDecision.value = false
    }
  }

  async function grantManually(email: string, amount: number) {
    isGranting.value = true
    try {
      return await fetchJson<ManualMagicCoinGrantResponse>('/api/admin/magic-coins/grants', {
        method: 'POST',
        body: { email, amount },
      })
    } finally {
      isGranting.value = false
    }
  }

  async function unblockUser(userId: number) {
    unblockingUserId.value = userId
    try {
      await fetchJson<void>(`/api/admin/magic-coins/blocks/${userId}`, {
        method: 'DELETE',
        responseType: 'void',
      })
      blocks.value = blocks.value.filter((block) => block.userId !== userId)
    } finally {
      unblockingUserId.value = null
    }
  }

  return {
    requests,
    blocks,
    isLoadingRequests,
    isLoadingBlocks,
    isSubmittingDecision,
    isGranting,
    unblockingUserId,
    requestsError,
    blocksError,
    fetchRequests,
    fetchBlocks,
    approveRequest,
    rejectRequest,
    grantManually,
    unblockUser,
  }
})
