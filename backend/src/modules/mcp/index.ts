import type { Hono } from 'hono'
import { mcpRoutes } from './routes'

/**
 * The MCP module: `/api/mcp`, where an AI client administers the catalog and master data with an
 * access token through three generic tools over the admin routes that opt in with
 * `requireAdminOrToken`. It imports no other module and owns no table: it reads the routes of the
 * composed `app`, so the composition root creates it last, after every route is mounted.
 * `frontendBaseUrl` names the one browser origin the endpoint accepts.
 */
export function createMcpModule({ app, frontendBaseUrl }: { app: Hono; frontendBaseUrl: string }) {
  return { routes: mcpRoutes(app, frontendBaseUrl) }
}
