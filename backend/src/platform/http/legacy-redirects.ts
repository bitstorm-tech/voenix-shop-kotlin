import { Hono } from 'hono'

/**
 * Permanent `301` redirects from the legacy WordPress URLs to their counterparts in the Vue
 * storefront, matched case-insensitively with or without a trailing slash. Mounted ahead of
 * `serveFrontend` so bots and bookmarks receive a real `301` instead of an HTTP 200 SPA fallback
 * showing the client-side 404 page.
 *
 * Notice `/faq` itself is a live storefront route: only `/faq/` (with a trailing slash) redirects
 * to `/faq`, never `/faq` to itself.
 */
export const LEGACY_REDIRECTS: ReadonlyMap<string, string> = new Map([
  ['/impressum', '/imprint'],
  ['/widerruf', '/returns'],
  ['/versand', '/shipping'],
  ['/zahlung', '/payment'],
  ['/faq', '/faq'],
  ['/ueber-uns', '/about'],
  ['/tassenuebersicht', '/'],
  ['/firmenkunden', '/contact'],
  ['/pdf/agbs.pdf', '/terms'],
])

/**
 * Returns the target path for a legacy WordPress URL, or `null` when the path should continue to
 * the SPA. Never redirects a path to itself.
 */
export function legacyRedirectTarget(pathname: string): string | null {
  const normalized = pathname.length > 1 ? pathname.replace(/\/+$/, '') : pathname
  const target = LEGACY_REDIRECTS.get(normalized.toLowerCase())
  if (target === undefined || pathname === target) return null
  return target
}

/** Middleware-free route table redirecting every legacy WordPress URL with HTTP `301`. */
export function legacyRedirectRoutes(): Hono {
  const routes = new Hono()
  routes.on(['GET', 'HEAD'], '*', (c, next) => {
    const target = legacyRedirectTarget(c.req.path)
    if (target === null) return next()
    const search = new URL(c.req.url).search
    return c.redirect(`${target}${search}`, 301)
  })
  return routes
}
