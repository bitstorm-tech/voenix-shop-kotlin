import type { Context } from 'hono'
import { createMiddleware } from 'hono/factory'
import { apiError } from '@/platform/http/respond'

/**
 * Counts requests per client IP in fixed windows and refuses the request past the limit with `429`
 * and a `Retry-After` header. It exists for one endpoint, the paid image generation, which can be
 * used without an account (docs/dev/backend-ts/conventions/rate-limiting.md).
 *
 * The state is in memory and therefore per process. The map is bounded twice: windows that have
 * ended are swept at most once per window, and at most `maxTrackedIps` addresses are remembered,
 * so a caller rotating through addresses cannot grow the map for a whole window. When the map is
 * full, an unknown address is refused for the whole window: this is a cost bound, and evicting an
 * entry would hand the rotation exactly what it wants.
 */
export function createClientIpRateLimiter({
  trustForwardedFor,
  peerAddress,
  limit = 20,
  windowSeconds = 60 * 60,
  maxTrackedIps = 100_000,
}: {
  /**
   * Whether the client IP may be read from `X-Forwarded-For`. Only when exactly one trusted reverse
   * proxy sits in front of this backend; any client can send the header.
   */
  trustForwardedFor: boolean
  /** The peer address of the connection, which no client can fake. */
  peerAddress: (c: Context) => string
  limit?: number
  windowSeconds?: number
  maxTrackedIps?: number
}) {
  const windowMillis = windowSeconds * 1000
  const windows = new Map<string, { startedAt: number; count: number }>()
  let lastSweep = 0

  const dropEndedWindows = (now: number) => {
    for (const [ip, open] of windows) {
      if (now >= open.startedAt + windowMillis) windows.delete(ip)
    }
  }

  /** `null` when the request may proceed, otherwise the seconds the caller has to wait. */
  const retryAfterSeconds = (clientIp: string, now: number): number | null => {
    if (now - lastSweep >= windowMillis) {
      lastSweep = now
      dropEndedWindows(now)
    }
    const existing = windows.get(clientIp)
    if (existing === undefined && windows.size >= maxTrackedIps) {
      dropEndedWindows(now)
      if (windows.size >= maxTrackedIps) return windowSeconds
    }
    const open =
      existing === undefined || now >= existing.startedAt + windowMillis
        ? { startedAt: now, count: 1 }
        : { startedAt: existing.startedAt, count: Math.min(existing.count + 1, limit + 1) }
    windows.set(clientIp, open)
    if (open.count <= limit) return null
    // Always at least one second: a `Retry-After: 0` would invite an immediate retry.
    return Math.max(1, Math.ceil((open.startedAt + windowMillis - now) / 1000))
  }

  /**
   * With the flag enabled the **last** entry of `X-Forwarded-For` counts: every proxy appends the
   * address it saw, so only the last entry was written by the trusted proxy in front of this
   * backend, while the first is whatever the client sent.
   */
  const clientIp = (c: Context): string => {
    if (trustForwardedFor) {
      const forwarded = (c.req.header('x-forwarded-for') ?? '')
        .split(',')
        .map((entry) => entry.trim())
        .filter((entry) => entry !== '')
        .at(-1)
      if (forwarded !== undefined) return forwarded
    }
    return peerAddress(c)
  }

  return {
    retryAfterSeconds,
    /** Install it after the route protection, so a refused request spends no slot of the limit. */
    middleware: createMiddleware(async (c, next) => {
      const wait = retryAfterSeconds(clientIp(c), Date.now())
      if (wait !== null) {
        c.header('Retry-After', String(wait))
        return c.json(apiError('Too many requests'), 429)
      }
      await next()
    }),
  }
}
