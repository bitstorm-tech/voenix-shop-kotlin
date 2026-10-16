import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { fetchJson } from '@/lib/api'

/** The kinds of finding the backend's operations module records (Operations package guide, D2). */
export type FindingKind =
  | 'PAYMENT_UNRESOLVED'
  | 'PAYMENT_PAID_ORDER_UNPAID'
  | 'PRODUCTION_REQUEST_STALLED'
  | 'JOB_GENERATION_STALLED'
  | 'DELIVERY_STALLED'
  | 'SPOD_SUBMISSION_STALLED'
  | 'SPOD_QUARANTINED'
  | 'SPOD_REMOTE_ATTENTION'
  | 'SHIPMENT_OVERDUE'
  | 'EMAIL_STALLED'

/**
 * One finding as `GET /api/admin/operations/findings` answers it: a row of the pipeline that stands
 * still, the order it belongs to, and since when. `resolvedAt` is present in the resolved list only.
 */
export interface OperationsFinding {
  kind: FindingKind
  subject: { type: 'ORDER' | 'PAYMENT' | 'REQUEST' | 'JOB' | 'DELIVERY' | 'EMAIL'; id: number }
  orderId: number | null
  since: string
  attemptCount: number | null
  lastErrorCode: string | null
  firstSeenAt: string
  alertedAt: string | null
  resolvedAt?: string | null
}

export const useAdminOperationsStore = defineStore('admin-operations', () => {
  const findings = ref<OperationsFinding[]>([])
  const isLoading = shallowRef(false)
  /** The failure of the last load. The view decides how to word it. */
  const error = shallowRef<Error | null>(null)
  /**
   * The number of the most recently issued load. Switching between the open and the resolved list
   * starts a second load before the first has answered; only the newest one may write the state.
   */
  let latestFetchId = 0

  /** The open findings, or with `resolved` the ones resolved within the last seven days. */
  async function fetchFindings(resolved = false): Promise<void> {
    const fetchId = ++latestFetchId
    isLoading.value = true
    error.value = null

    try {
      const loaded = await fetchJson<OperationsFinding[]>(
        `/api/admin/operations/findings${resolved ? '?resolved=true' : ''}`,
      )
      if (fetchId === latestFetchId) findings.value = loaded
    } catch (err) {
      if (fetchId === latestFetchId) {
        findings.value = []
        error.value = err instanceof Error ? err : new Error('Unknown error')
      }
    } finally {
      if (fetchId === latestFetchId) isLoading.value = false
    }
  }

  return { findings, isLoading, error, fetchFindings }
})
