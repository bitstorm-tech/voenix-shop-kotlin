import { errorDetails, type Logger } from '@/platform/logging'

/**
 * How long graceful shutdown waits for open HTTP / keep-alive connections to drain before forcing
 * them closed, leaving headroom inside a container's stop grace period for workers and `db.destroy()`.
 */
export const SERVER_STOP_TIMEOUT_MS = 12_000

/**
 * Builds the process signal handler for `SIGTERM` / `SIGINT`:
 *
 * - Re-entrant signals are ignored while shutdown is already running, so a second signal never
 *   runs `stopServer` or `stopApplication` twice.
 * - `stopServer(false)` is raced against `serverStopTimeoutMs`; if keep-alive or slow connections
 *   do not drain in time, `stopServer(true)` forces them closed.
 * - `stopApplication()` always runs afterwards (stopping background workers, SPOD catalog sync, and
 *   the database pool) before `exit` is invoked.
 */
export function createShutdownHandler({
  stopServer,
  stopApplication,
  exit,
  log,
  serverStopTimeoutMs = SERVER_STOP_TIMEOUT_MS,
}: {
  stopServer: (closeActiveConnections?: boolean) => Promise<void>
  stopApplication: () => Promise<void>
  exit: (code: number) => void
  log: Pick<Logger, 'info' | 'warn' | 'error'>
  serverStopTimeoutMs?: number
}): (signal: string) => Promise<void> {
  let shuttingDown: Promise<void> | null = null

  return (signal: string) => {
    if (shuttingDown !== null) {
      log.info({ signal }, 'Shutdown already in progress, ignoring signal')
      return shuttingDown
    }
    log.info({ signal }, 'Shutting down')
    shuttingDown = (async () => {
      let exitCode = 0
      try {
        await stopServerWithTimeout(stopServer, serverStopTimeoutMs, log)
      } catch (error) {
        exitCode = 1
        log.error({ error: errorDetails(error) }, 'HTTP server shutdown failed')
      }
      try {
        await stopApplication()
      } catch (error) {
        exitCode = 1
        log.error({ error: errorDetails(error) }, 'Application shutdown failed')
      }
      exit(exitCode)
    })()
    return shuttingDown
  }
}

async function stopServerWithTimeout(
  stopServer: (closeActiveConnections?: boolean) => Promise<void>,
  timeoutMs: number,
  log: Pick<Logger, 'warn'>,
): Promise<void> {
  const graceful = Promise.resolve()
    .then(() => stopServer(false))
    .then(() => 'drained' as const)
  // Prevent an unhandled rejection if the timeout wins and `graceful` later rejects.
  graceful.catch(() => undefined)

  let timer: ReturnType<typeof setTimeout> | undefined
  let outcome: 'drained' | 'timed-out'
  try {
    outcome = await Promise.race([
      graceful,
      new Promise<'timed-out'>((resolve) => {
        timer = setTimeout(() => {
          resolve('timed-out')
        }, timeoutMs)
      }),
    ])
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }

  if (outcome === 'timed-out') {
    log.warn({ timeoutMs }, 'HTTP server did not drain in time, forcing active connections closed')
    await stopServer(true)
  }
}
