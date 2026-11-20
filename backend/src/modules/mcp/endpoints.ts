import type { Hono } from 'hono'
import type { RouterRoute } from 'hono/types'
import { z } from 'zod'
import { isSessionProtection, requireAdminOrToken } from '@/platform/auth/auth'
import { formBody } from '@/platform/http/respond'

/** One admin endpoint an access token may call, with the body its route reads. */
export interface AdminEndpoint {
  method: string
  path: string
  body: 'json' | 'multipart' | null
  /** The JSON body's schema, for a `json` endpoint. */
  schema: z.ZodType | null
}

/**
 * The admin endpoints an access token may call, derived from the protection itself so the list
 * cannot drift from it: an entry of `app.routes` below `/api/admin/` (not `ALL`) is listed only
 * when a `requireAdminOrToken` entry covers it and no session protection (`requireAdmin` and its
 * kind, which refuse a token) covers it too. A locked route, and a route whose protection is not
 * recognized, never appears. Sorted by path, then
 * method. Read at call time, so every route mounted by then counts.
 */
export function openAdminEndpoints(app: Hono): AdminEndpoint[] {
  const opened = coveredBy(app.routes.filter((route) => route.handler === requireAdminOrToken))
  const locked = coveredBy(app.routes.filter((route) => isSessionProtection(route.handler)))

  const endpoints = new Map<string, AdminEndpoint>()
  for (const route of app.routes) {
    if (route.method === 'ALL' || !route.path.startsWith('/api/admin/')) continue
    const key = `${route.method} ${route.path}`
    if (!opened(route) || locked(route)) continue
    const endpoint = endpoints.get(key) ?? {
      method: route.method,
      path: route.path,
      body: null,
      schema: null,
    }
    const schema: unknown = (route.handler as { bodySchema?: unknown }).bodySchema
    if (schema instanceof z.ZodType) {
      endpoint.body = 'json'
      endpoint.schema = schema
    } else if (route.handler === formBody) {
      endpoint.body = 'multipart'
    }
    endpoints.set(key, endpoint)
  }
  return [...endpoints.values()].sort(
    (a, b) => compare(a.path, b.path) || compare(a.method, b.method),
  )
}

/**
 * Whether one of these middleware entries runs before a route: a sub-app `use` (`ALL <base>/*`)
 * whose base is its path or a prefix of it at a `/`, or an inline one with the same method and path.
 */
function coveredBy(protections: RouterRoute[]) {
  const bases = protections
    .filter((route) => route.method === 'ALL' && route.path.endsWith('/*'))
    .map((route) => route.path.slice(0, -2))
  const inline = new Set(protections.map((route) => `${route.method} ${route.path}`))
  return (route: RouterRoute) =>
    inline.has(`${route.method} ${route.path}`) ||
    bases.some((base) => route.path === base || route.path.startsWith(`${base}/`))
}

const compare = (a: string, b: string) => (a < b ? -1 : a > b ? 1 : 0)

/** The body's JSON Schema as a client sends it. */
export const jsonSchemaOf = (schema: z.ZodType) =>
  z.toJSONSchema(schema, { io: 'input', unrepresentable: 'any' })
