import { Hono, type Context } from 'hono'
import { bodyLimit } from 'hono/body-limit'
import { HTTPException } from 'hono/http-exception'
import { routePath } from 'hono/route'
import { secureHeaders } from 'hono/secure-headers'
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
 * Pages and endpoints whose URLs carry secret tokens (order access tokens, confirmation/reset
 * links). They get `Referrer-Policy: no-referrer` so clicking any link on them never leaks the URL.
 */
const NO_REFERRER_EXACT_PATHS = new Set([
  '/confirm-email',
  '/reset-password',
  '/confirm-change-email',
  '/set-password',
])

const isNoReferrerPath = (path: string): boolean =>
  NO_REFERRER_EXACT_PATHS.has(path) ||
  path.startsWith('/order/') ||
  path.startsWith('/api/order-lookup/')

/**
 * The application with everything that applies to every route: the security headers, the request
 * log, the error answer, the body limit, and the authentication middleware with its antiforgery
 * route. The composition root mounts the module sub-apps on it with `app.route('/', module.routes)`.
 *
 * `https` enables HSTS and `upgrade-insecure-requests` (mirrors `settings.auth.secureCookies`: true
 * everywhere outside local HTTP development).
 */
export function createApp(auth: Auth, { https = false }: { https?: boolean } = {}): Hono {
  const app = new Hono()
  app.onError((error, c) => {
    // Hono's own refusals (a malformed form body, for example) keep their status and message.
    if (error instanceof HTTPException) return c.json(apiError(error.message), error.status)
    httpLog.error({ error: errorDetails(error) }, 'Unhandled server error')
    return c.json(apiError('Internal server error'), 500)
  })
  // Installed before `secureHeaders` so its post-`next()` override runs after `secureHeaders` sets
  // the global `Referrer-Policy`.
  app.use('*', async (c, next) => {
    await next()
    if (isNoReferrerPath(c.req.path)) {
      c.res.headers.set('Referrer-Policy', 'no-referrer')
    }
  })
  app.use(
    '*',
    secureHeaders({
      strictTransportSecurity: https ? 'max-age=31536000; includeSubDomains' : false,
      xContentTypeOptions: 'nosniff',
      xFrameOptions: 'DENY',
      referrerPolicy: 'strict-origin-when-cross-origin',
      contentSecurityPolicy: {
        defaultSrc: ["'self'"],
        baseUri: ["'self'"],
        objectSrc: ["'none'"],
        frameAncestors: ["'none'"],
        formAction: ["'self'"],
        // `index.html` runs a small inline script to apply the saved dark/light theme before first
        // paint; Vue/Tailwind/Reka-UI and sanitized iubenda HTML rely on inline styles.
        scriptSrc: ["'self'", "'unsafe-inline'"],
        styleSrc: ["'self'", "'unsafe-inline'"],
        imgSrc: ["'self'", 'data:', 'blob:'],
        fontSrc: ["'self'", 'data:'],
        connectSrc: ["'self'", 'data:', 'blob:'],
        workerSrc: ["'self'", 'blob:'],
        manifestSrc: ["'self'"],
        ...(https ? { upgradeInsecureRequests: [] } : {}),
      },
    }),
  )
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
