import { Hono, type Context } from 'hono'
import { bodyLimit } from 'hono/body-limit'
import { HTTPException } from 'hono/http-exception'
import { routePath } from 'hono/route'
import type { Auth } from '@/platform/auth/auth'
import { apiError } from '@/platform/http/respond'
import { createLogger, errorDetails, withRequestId } from '@/platform/logging'

/**
 * How many bytes of request body the application accepts at all, on every route: the outer
 * transfer bound, not a feature limit (docs/dev/backend/conventions/request-size-limits.md).
 * It is the 30,000,000 bytes the legacy application's Kestrel refused at.
 */
export const MAX_REQUEST_BODY_BYTES = 30_000_000

/** Exported for the test that reads the request lines. */
export const httpLog = createLogger('http')

/**
 * The application with everything that applies to every route: the request log, the error
 * answer, the body limit, and the authentication middleware with its antiforgery route. The
 * composition root mounts the module sub-apps on it with `app.route('/', module.routes)`.
 */
export function createApp(auth: Auth): Hono {
  const app = new Hono()
  app.onError((error, c) => {
    // Hono's own refusals (a malformed form body, for example) keep their status and message.
    if (error instanceof HTTPException) return c.json(apiError(error.message), error.status)
    httpLog.error({ error: errorDetails(error) }, 'Unhandled server error')
    return c.json(apiError('Internal server error'), 500)
  })
  // One line per API request with the route pattern (`/api/order-lookup/:token`), never the real
  // path: webhook secrets and order link tokens are path segments. After `next()` the pattern is the
  // one of the route that answered; a request no API route matched logs a catch-all (`/*`). The
  // static frontend files would drown the API lines. Every line logged while the request is
  // served carries its short random id, so an error line can be matched with its request line.
  app.use('/api/*', (c: Context, next) =>
    withRequestId(crypto.randomUUID().slice(0, 8), async () => {
      const started = performance.now()
      await next()
      const milliseconds = Math.round(performance.now() - started)
      httpLog.info(
        `${c.req.method} ${routePath(c)} ${String(c.res.status)} ${String(milliseconds)}ms`,
      )
    }),
  )
  app.use(
    bodyLimit({
      maxSize: MAX_REQUEST_BODY_BYTES,
      onError: (c) => c.json(apiError('Request body too large'), 413),
    }),
  )
  app.use(auth.middleware)
  app.route('/', auth.routes)
  return app
}
