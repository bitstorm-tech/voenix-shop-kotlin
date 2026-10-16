import { setTimeout as sleep } from 'node:timers/promises'
import { createLogger, errorClass } from '@/platform/logging'
import type { Finding, FindingRepository } from './repository'

const logger = createLogger('operations-worker')
const SCAN_INTERVAL_MILLIS = 5 * 60_000

/** One owning module's report of its stalled rows at `now`. */
export interface FindingSource {
  findings: (now: Date) => Promise<readonly Finding[]>
}

/**
 * The operations worker: every five minutes it asks the order, production, and email modules for
 * their findings (plain reads, outside any write of this module) and records the answers in one
 * transaction of its own. One instance per database, like the other two workers.
 */
export function createOperationsWorker({
  sources,
  repository,
}: {
  sources: readonly FindingSource[]
  repository: Pick<FindingRepository, 'record'>
}) {
  const runOnce = async () => {
    const now = new Date()
    const findings: Finding[] = []
    for (const source of sources) findings.push(...(await source.findings(now)))
    await repository.record(findings)
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
            await runOnce()
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
