import { setTimeout as sleep } from 'node:timers/promises'
import { renderOperationsAlert, type EmailModule } from '@/modules/email'
import { errorClass, type Logger } from '@/platform/logging'
import type { Alert, Finding, FindingRepository } from './repository'

const SCAN_INTERVAL_MILLIS = 5 * 60_000

/** One owning module's report of its stalled rows at `now`. */
export interface FindingSource {
  findings: (now: Date) => Promise<readonly Finding[]>
}

/**
 * The operations worker: every five minutes it first reconciles the payments under way with Mollie,
 * so a payment resolved now never becomes a finding, then asks the order, production, and email
 * modules for their findings (plain reads, outside any write of this module) and records the
 * answers in one transaction of its own, which also enqueues one `OPERATIONS_ALERT` to `alertEmail`
 * for the new mailed findings. Without an address it logs one `error` line per new mailed finding
 * instead and leaves them unalerted. A `stop` ends the reconciliation between payments and skips
 * collecting and recording for that scan. One instance per database, like the other two workers.
 */
export function createOperationsWorker({
  reconcilePayments,
  sources,
  repository,
  outbox,
  alertEmail,
  frontendBaseUrl,
  logger,
}: {
  reconcilePayments: (now: Date, signal?: AbortSignal) => Promise<void>
  sources: readonly FindingSource[]
  repository: Pick<FindingRepository, 'record'>
  outbox: Pick<EmailModule['outbox'], 'enqueue'>
  alertEmail: string | null
  frontendBaseUrl: string
  logger: Logger
}) {
  const alert: Alert = async (trx, findings) => {
    if (alertEmail === null) {
      for (const finding of findings) {
        if (!finding.isNew) continue
        logger.error(
          { kind: finding.kind, subjectType: finding.subjectType, subjectId: finding.subjectId },
          'An operations finding is not mailed, because no alert address is set',
        )
      }
      return false
    }
    await outbox.enqueue(
      trx,
      renderOperationsAlert({ recipient: alertEmail, frontendBaseUrl, findings }),
    )
    return true
  }

  const runOnce = async (signal?: AbortSignal) => {
    const now = new Date()
    await reconcilePayments(now, signal)
    if (signal?.aborted === true) return
    const findings: Finding[] = []
    for (const source of sources) findings.push(...(await source.findings(now)))
    await repository.record(findings, alert)
  }

  let controller: AbortController | null = null
  let loop: Promise<void> = Promise.resolve()

  return {
    runOnce,

    /** Scans now and then every five minutes, until `stop`. */
    start: () => {
      if (controller !== null) throw new Error('The operations worker is already started')
      const { signal } = (controller = new AbortController())
      loop = (async () => {
        while (!signal.aborted) {
          try {
            await runOnce(signal)
          } catch (error) {
            logger.error({ errorClass: errorClass(error) }, 'Operations worker scan failed')
          }
          await sleep(SCAN_INTERVAL_MILLIS, undefined, { signal }).catch(() => undefined)
        }
      })()
    },

    /** Stops the loop and resolves once the scan in progress is finished. */
    stop: async () => {
      controller?.abort()
      await loop
    },
  }
}
