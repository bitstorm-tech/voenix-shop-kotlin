import { randomBytes, timingSafeEqual } from 'node:crypto'
import { Hono, type Context } from 'hono'
import { deleteCookie, getCookie, setCookie } from 'hono/cookie'
import { createMiddleware } from 'hono/factory'
import {
  authCookies,
  CSRF_COOKIE,
  CSRF_HEADER,
  GUEST_COOKIE,
  SESSION_COOKIE,
  SESSION_DURATION_SECONDS,
  type CsrfSession,
  type UserSession,
} from '@/platform/auth/cookies'
import { apiError } from '@/platform/http/respond'
import { createLogger } from '@/platform/logging'

const log = createLogger('auth')

/** The two role names the platform authorizes against; `CUSTOMER` stays in the account module. */
export const AuthRoles = { ADMIN: 'ADMIN', SUPPLIER: 'SUPPLIER' } as const

/** The validated identity of one request, or `null` for an anonymous one. */
export interface UserPrincipal {
  userId: number
  roles: string[]
}

/** The prefix of every access token; a bearer value without it is refused without a lookup. */
export const ACCESS_TOKEN_PREFIX = 'vnx_'

/**
 * Answers who an access token acts for, with the roles read on this very request, or `null` for
 * an unknown or revoked token, and stamps a token's use. The account module owns the tokens and
 * implements this.
 */
export interface AccessTokens {
  /** Reads only; a refused or anonymous request never writes. */
  principalOf(token: string): Promise<UserPrincipal | null>
  /** Stamps the token's last use; `requireAdminOrToken` calls it once the token passed. */
  markUsed(token: string): Promise<void>
}

/** A bearer token's identity, with the stamp of its use. */
export interface TokenPrincipal extends UserPrincipal {
  markUsed(): Promise<void>
}

declare module 'hono' {
  interface ContextVariableMap {
    /** The session cookie's identity; never a bearer token's. */
    principal: UserPrincipal | null
    /**
     * The bearer token's identity: `undefined` without an `Authorization: Bearer` header, `null`
     * for a value that is no valid token. Only `requireAdminOrToken` reads it.
     */
    tokenPrincipal: TokenPrincipal | null | undefined
    csrfSession: CsrfSession | null
    /** Set by `requireSupplier`: the supplier the caller acts for. */
    supplierId: number
  }
}

export type Auth = ReturnType<typeof createAuth>

/**
 * Everything the application needs to authenticate a request: the middleware that reads the two
 * sealed cookies into `c.var.principal` and `c.var.csrfSession` and renews a session past half its
 * life, the antiforgery route, the login and logout cookie writes, and the guest identity.
 */
export function createAuth({
  sessionSecret,
  secureCookies,
  accessTokens,
}: {
  sessionSecret: string
  /** `Secure` on every cookie; from the settings, never the request, which a proxy or client sets. */
  secureCookies: boolean
  accessTokens: AccessTokens
}) {
  const cookies = authCookies(sessionSecret)
  /** Browser-session cookies: no Max-Age. */
  const sessionCookieOptions = {
    path: '/',
    httpOnly: true,
    sameSite: 'Lax' as const,
    secure: secureCookies,
  }

  const writeSession = async (c: Context, session: UserSession) => {
    setCookie(c, SESSION_COOKIE, await cookies.session.seal(session), sessionCookieOptions)
  }

  const middleware = createMiddleware(async (c, next) => {
    const now = epochSeconds()
    const rawSessionCookie = getCookie(c, SESSION_COOKIE)
    const session = await cookies.session.open(rawSessionCookie)
    if (rawSessionCookie !== undefined && rawSessionCookie !== '' && session === null) {
      log.warn('Session cookie could not be opened')
    }
    const valid = session !== null && session.expiresAtEpochSeconds > now ? session : null
    c.set('principal', valid === null ? null : { userId: valid.userId, roles: valid.roles })
    c.set('csrfSession', await cookies.csrf.open(getCookie(c, CSRF_COOKIE)))
    const bearer = /^Bearer(?:\s+(.*))?$/i.exec(c.req.header('authorization') ?? '')
    const token = bearer?.[1]?.trim() ?? ''
    const tokenPrincipal =
      bearer === null
        ? undefined
        : token.startsWith(ACCESS_TOKEN_PREFIX)
          ? await accessTokens.principalOf(token)
          : null
    c.set(
      'tokenPrincipal',
      tokenPrincipal && { ...tokenPrincipal, markUsed: () => accessTokens.markUsed(token) },
    )
    // Sliding renewal: once more than half of the lifetime has elapsed, any request renews it.
    if (valid !== null && now - valid.issuedAtEpochSeconds > valid.expiresAtEpochSeconds - now) {
      await writeSession(c, {
        ...valid,
        issuedAtEpochSeconds: now,
        expiresAtEpochSeconds: now + SESSION_DURATION_SECONDS,
      })
    }
    await next()
  })

  const routes = new Hono()
  routes.get('/api/antiforgery/token', async (c) => {
    const token = randomBytes(32).toString('base64url')
    const csrf: CsrfSession = { token, userId: c.var.principal?.userId ?? null }
    setCookie(c, CSRF_COOKIE, await cookies.csrf.seal(csrf), sessionCookieOptions)
    return c.json({ requestToken: token })
  })

  return {
    middleware,
    routes,
    /** Writes a fresh 24-hour session; the one production caller is the account module's login. */
    signIn: (c: Context, userId: number, roles: string[]) => {
      const now = epochSeconds()
      return writeSession(c, {
        userId,
        roles,
        issuedAtEpochSeconds: now,
        expiresAtEpochSeconds: now + SESSION_DURATION_SECONDS,
      })
    },
    signOut: (c: Context) => {
      deleteCookie(c, SESSION_COOKIE, { path: '/' })
    },
    /**
     * The identity of a visitor without an account. `tryGet` never sets a cookie, so a read path
     * does not turn a passing visitor into a stored guest; `getOrCreate` issues one when needed. A
     * tampered cookie counts as missing.
     */
    guestTokens: {
      tryGet: (c: Context) => cookies.guest.open(getCookie(c, GUEST_COOKIE)),
      getOrCreate: async (c: Context) => {
        const existing = await cookies.guest.open(getCookie(c, GUEST_COOKIE))
        if (existing !== null) return existing
        const token = randomBytes(48).toString('base64')
        setCookie(c, GUEST_COOKIE, await cookies.guest.seal(token), {
          path: '/api',
          httpOnly: true,
          sameSite: 'Lax',
          secure: secureCookies,
          maxAge: 30 * 24 * 60 * 60,
        })
        return token
      },
    },
  }
}

const epochSeconds = () => Math.floor(Date.now() / 1000)

// --- Route protections -------------------------------------------------------------------------

/**
 * A protection answers before the route handler and fails closed: the handler runs only when
 * `authorize` passed and, for a mutating method, the CSRF check passed as well. A principal that
 * `requireAdminOrToken` took from a bearer token counts as anonymous here, so a session protection
 * below an open one (a nested sub-app, an inline guard) stays closed to the token.
 */
function failClosed(
  authorize: (c: Context) => Promise<Response | null> | Response | null,
  csrfFailure: (c: Context) => CsrfFailureReason | null = userCsrfFailure,
) {
  const protection = createMiddleware(async (c, next) => {
    if (c.var.principal !== null && c.var.principal === c.var.tokenPrincipal) {
      log.warn(
        { userId: c.var.principal.userId },
        'Request refused: access token not allowed on session-only route',
      )
      return authRefusal(c, 401, 'Authentication required')
    }
    const refusal = await authorize(c)
    if (refusal !== null) return refusal
    if (MUTATING_METHODS.has(c.req.method)) {
      const reason = csrfFailure(c)
      if (reason !== null) {
        log.warn(
          { userId: c.var.principal?.userId ?? null, reason },
          'Request refused: invalid CSRF token',
        )
        return c.json(apiError('Invalid CSRF token'), 400)
      }
    }
    await next()
  })
  sessionProtections.add(protection)
  return protection
}

/** Every protection `failClosed` built: each one is closed to an access token. */
const sessionProtections = new WeakSet<object>()

/** Whether a route handler is a protection that refuses an access token; the MCP endpoint list reads it. */
export const isSessionProtection = (handler: object) => sessionProtections.has(handler)

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

/** The one shape every authentication and authorization refusal answers with. */
const authRefusal = (c: Context, status: 401 | 403, message: string) =>
  c.json({ success: false, message, code: null }, status)

/** Any signed-in user; no role requirement. */
export const requireAuthenticated = failClosed((c) =>
  c.var.principal === null ? authRefusal(c, 401, 'Authentication required') : null,
)

/** The exact role `ADMIN`. */
export const requireAdmin = failClosed((c) => {
  if (c.var.principal === null) return authRefusal(c, 401, 'Authentication required')
  if (!c.var.principal.roles.includes(AuthRoles.ADMIN)) {
    log.warn({ userId: c.var.principal.userId }, 'Request refused: user lacks ADMIN role')
    return authRefusal(c, 403, 'Admin access required')
  }
  return null
})

/**
 * The exact role `ADMIN`, from an access token or a session. A request that presents a bearer
 * token is decided by that token alone, never by its cookies: an invalid token answers `401`, a
 * token of a non-admin `403`, and a valid one is stamped as used, becomes `c.var.principal`, and needs no CSRF pair,
 * because a browser never attaches a bearer token on its own. Without a bearer header this is
 * `requireAdmin`. A route opts in only under the rule of the authentication guide.
 */
export const requireAdminOrToken = createMiddleware(async (c, next) => {
  const token = c.var.tokenPrincipal
  if (token === undefined) return requireAdmin(c, next)
  if (token === null) {
    log.warn('Request refused: invalid or revoked access token')
    return authRefusal(c, 401, 'Authentication required')
  }
  if (!token.roles.includes(AuthRoles.ADMIN)) {
    log.warn({ userId: token.userId }, 'Request refused: access token user lacks ADMIN role')
    return authRefusal(c, 403, 'Admin access required')
  }
  await token.markUsed()
  c.set('principal', token)
  await next()
})

/**
 * Guests and signed-in users alike. Mutating requests still need a valid CSRF pair, and a request
 * with a user session must use a token minted for that user.
 */
export const guestCapable = failClosed(() => null, guestCsrfFailure)

/**
 * Answers which supplier a signed-in user acts for, or `null` when the user is not a supplier
 * login. The account module owns the `users.supplier_id` link and implements this; it is resolved
 * on every request, so a revoked supplier login loses access with the very next request.
 */
export interface SupplierAccounts {
  supplierIdOf(userId: number): Promise<number | null>
}

/**
 * The exact role `SUPPLIER` *and* a live link to a supplier. Both failures answer the same `403`,
 * so a caller cannot tell a missing role from a revoked link. Handlers read the supplier from
 * `c.var.supplierId`.
 */
export function requireSupplier(accounts: SupplierAccounts) {
  return failClosed(async (c) => {
    const principal = c.var.principal
    if (principal === null) return authRefusal(c, 401, 'Authentication required')
    const hasRole = principal.roles.includes(AuthRoles.SUPPLIER)
    const supplierId = hasRole ? await accounts.supplierIdOf(principal.userId) : null
    if (supplierId === null) {
      log.warn(
        { userId: principal.userId, reason: hasRole ? 'no_supplier_link' : 'missing_role' },
        'Request refused: supplier access required',
      )
      return authRefusal(c, 403, 'Supplier access required')
    }
    c.set('supplierId', supplierId)
    return null
  })
}

type CsrfFailureReason = 'missing_cookie' | 'user_mismatch' | 'missing_header' | 'token_mismatch'

function userCsrfFailure(c: Context): CsrfFailureReason | null {
  const principal = c.var.principal
  const csrf = c.var.csrfSession
  if (csrf === null) return 'missing_cookie'
  if (principal === null || csrf.userId !== principal.userId) return 'user_mismatch'
  return headerFailure(c, csrf)
}

function guestCsrfFailure(c: Context): CsrfFailureReason | null {
  const csrf = c.var.csrfSession
  if (csrf === null) return 'missing_cookie'
  const userId = c.var.principal?.userId ?? null
  if (userId !== null && csrf.userId !== userId) return 'user_mismatch'
  return headerFailure(c, csrf)
}

function headerFailure(c: Context, csrf: CsrfSession): CsrfFailureReason | null {
  const supplied = c.req.header(CSRF_HEADER)
  if (supplied === undefined) return 'missing_header'
  const expected = Buffer.from(csrf.token)
  const actual = Buffer.from(supplied)
  return expected.length === actual.length && timingSafeEqual(expected, actual)
    ? null
    : 'token_mismatch'
}
