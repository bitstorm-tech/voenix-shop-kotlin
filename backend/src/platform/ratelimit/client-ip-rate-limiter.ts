import type { Context } from 'hono'
import { createMiddleware } from 'hono/factory'
import { apiError } from '@/platform/http/respond'
import { createLogger } from '@/platform/logging'

const log = createLogger('ratelimit')

type RefusalScope = 'global' | 'maxTrackedIps' | 'ip'

/**
 * Counts requests per client IP in fixed windows and refuses the request past the limit with `429`
 * and a `Retry-After` header. When `globalLimit` is set, also enforces a process-wide cap across
 * all client IPs in the same window duration so IP rotation cannot bypass the cost bound
 * (docs/dev/backend/conventions/rate-limiting.md).
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
  globalLimit,
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
  /** Optional process-wide maximum across all client IPs per window. */
  globalLimit?: number
}) {
  const windowMillis = windowSeconds * 1000
  const windows = new Map<string, { startedAt: number; count: number }>()
  let globalWindow: { startedAt: number; count: number } | null = null
  let lastSweep = 0

  const dropEndedWindows = (now: number) => {
    for (const [ip, open] of windows) {
      if (now >= open.startedAt + windowMillis) windows.delete(ip)
    }
  }

  /** Always at least one second: a `Retry-After: 0` would invite an immediate retry. */
  const remainingSeconds = (startedAt: number, now: number) =>
    Math.max(1, Math.ceil((startedAt + windowMillis - now) / 1000))

  /** `null` when the request may proceed, otherwise the refusal scope and wait in seconds. */
  const evaluate = (
    clientIp: string,
    now: number,
  ): { scope: RefusalScope; retryAfter: number } | null => {
    if (globalLimit !== undefined) {
      if (globalWindow !== null && now >= globalWindow.startedAt + windowMillis) {
        globalWindow = null
      }
      if (globalWindow !== null && globalWindow.count >= globalLimit) {
        return { scope: 'global', retryAfter: remainingSeconds(globalWindow.startedAt, now) }
      }
    }
    if (now - lastSweep >= windowMillis) {
      lastSweep = now
      dropEndedWindows(now)
    }
    const existing = windows.get(clientIp)
    if (existing === undefined && windows.size >= maxTrackedIps) {
      dropEndedWindows(now)
      if (windows.size >= maxTrackedIps) {
        return { scope: 'maxTrackedIps', retryAfter: windowSeconds }
      }
    }
    const active =
      existing !== undefined && now < existing.startedAt + windowMillis ? existing : undefined
    if (active !== undefined && active.count >= limit) {
      return { scope: 'ip', retryAfter: remainingSeconds(active.startedAt, now) }
    }
    windows.set(
      clientIp,
      active === undefined
        ? { startedAt: now, count: 1 }
        : { startedAt: active.startedAt, count: active.count + 1 },
    )
    if (globalLimit !== undefined) {
      globalWindow =
        globalWindow === null
          ? { startedAt: now, count: 1 }
          : { startedAt: globalWindow.startedAt, count: globalWindow.count + 1 }
    }
    return null
  }

  /** `null` when the request may proceed, otherwise the seconds the caller has to wait. */
  const retryAfterSeconds = (clientIp: string, now: number): number | null =>
    evaluate(clientIp, now)?.retryAfter ?? null

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
      const refused = evaluate(clientIp(c), Date.now())
      if (refused !== null) {
        if (refused.scope === 'global') {
          log.warn(
            { scope: refused.scope, limit: globalLimit, retryAfterSeconds: refused.retryAfter },
            'Global rate limit exceeded',
          )
        } else if (refused.scope === 'maxTrackedIps') {
          log.warn(
            { scope: refused.scope, maxTrackedIps, retryAfterSeconds: refused.retryAfter },
            'Rate limiter IP table full; refusing untracked client',
          )
        } else {
          log.warn(
            { scope: refused.scope, limit, retryAfterSeconds: refused.retryAfter },
            'Client rate limit exceeded',
          )
        }
        c.header('Retry-After', String(refused.retryAfter))
        return c.json(apiError('Too many requests'), 429)
      }
      await next()
    }),
  }
}
