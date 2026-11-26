import { Hono } from 'hono'
import { sql, type Kysely } from 'kysely'
import type { Tables } from '@/platform/db/database'
import { createLogger, errorDetails } from '@/platform/logging'

const log = createLogger('health')

class ReadinessTimeoutError extends Error {
  constructor() {
    super('Readiness check timed out')
    this.name = 'ReadinessTimeoutError'
  }
}

/** Maximum time `/api/ready` waits for PostgreSQL before answering `503`. */
export const DEFAULT_READY_TIMEOUT_MS = 2_000

/**
 * Liveness and readiness probes mounted without authentication or rate limiting, ahead of
 * `serveFrontend`:
 *
 * - `GET /api/health` answers `200 { status: "ok" }` as long as the HTTP server is up.
 * - `GET /api/ready` runs `checkReady` (normally `SELECT 1` against PostgreSQL) within
 *   `timeoutMs` and answers `200 { status: "ready" }` or `503 { status: "unavailable" }`.
 */
export function healthRoutes(
  checkReady: () => Promise<void>,
  { timeoutMs = DEFAULT_READY_TIMEOUT_MS }: { timeoutMs?: number } = {},
): Hono {
  const routes = new Hono()
  routes.get('/api/health', (c) => {
    c.header('Cache-Control', 'no-store')
    return c.json({ status: 'ok' }, 200)
  })
  routes.get('/api/ready', async (c) => {
    c.header('Cache-Control', 'no-store')
    let timer: ReturnType<typeof setTimeout> | undefined
    const probe = Promise.resolve().then(checkReady)
    // Prevent an unhandled rejection if the timeout wins before `probe` rejects.
    probe.catch(() => undefined)
    try {
      await Promise.race([
        probe,
        new Promise<never>((_, reject) => {
          timer = setTimeout(() => {
            reject(new ReadinessTimeoutError())
          }, timeoutMs)
        }),
      ])
      return c.json({ status: 'ready' }, 200)
    } catch (error) {
      if (error instanceof ReadinessTimeoutError) {
        log.warn({ timeoutMs }, 'Readiness check timed out')
      } else {
        log.warn({ error: errorDetails(error) }, 'Readiness check failed')
      }
      return c.json({ status: 'unavailable' }, 503)
    } finally {
      if (timer !== undefined) clearTimeout(timer)
    }
  })
  return routes
}

/** The readiness probe of the composed application: one lightweight round-trip to PostgreSQL. */
export function databaseReadyCheck(db: Kysely<Tables>): () => Promise<void> {
  return async () => {
    await sql`SELECT 1`.execute(db)
  }
}
