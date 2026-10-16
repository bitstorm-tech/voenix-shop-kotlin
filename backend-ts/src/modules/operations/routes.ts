import { Hono } from 'hono'
import { createMiddleware } from 'hono/factory'
import { requireAdmin } from '@/platform/auth/auth'
import type { FindingRepository } from './repository'

/** A findings answer names orders and their state; no cache may keep it. */
const noStore = createMiddleware(async (c, next) => {
  c.header('Cache-Control', 'no-store')
  await next()
})

/**
 * The admin findings list: the open findings, or with `?resolved=true` the ones resolved within
 * the last seven days, which carry `resolvedAt` as well. Any other value of `resolved` is the open
 * list.
 */
export function operationsRoutes(findings: FindingRepository): Hono {
  const admin = new Hono()
  admin.use(requireAdmin, noStore)
  admin.get('/findings', async (c) => {
    const resolved = c.req.query('resolved') === 'true'
    const rows = await findings.list(resolved)
    return c.json(
      rows.map((row) => ({
        kind: row.kind,
        subject: { type: row.subject_type, id: row.subject_id },
        orderId: row.order_id,
        since: row.since,
        attemptCount: row.attempt_count,
        lastErrorCode: row.last_error_code,
        firstSeenAt: row.first_seen_at,
        alertedAt: row.alerted_at,
        ...(resolved ? { resolvedAt: row.resolved_at } : {}),
      })),
    )
  })

  const routes = new Hono()
  routes.route('/api/admin/operations', admin)
  return routes
}
