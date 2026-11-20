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
 * an unknown or revoked token. The account module owns the tokens and implements this.
 */
export interface AccessTokens {
  principalOf(token: string): Promise<UserPrincipal | null>
}

declare module 'hono' {
  interface ContextVariableMap {
    /** The session cookie's identity; never a bearer token's. */
    principal: UserPrincipal | null
    /**
     * The bearer token's identity: `undefined` without an `Authorization: Bearer` header, `null`
     * for a value that is no valid token. Only `requireAdminOrToken` reads it.
     */
    tokenPrincipal: UserPrincipal | null | undefined
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
    const session = await cookies.session.open(getCookie(c, SESSION_COOKIE))
    const valid = session !== null && session.expiresAtEpochSeconds > now ? session : null
    c.set('principal', valid === null ? null : { userId: valid.userId, roles: valid.roles })
    c.set('csrfSession', await cookies.csrf.open(getCookie(c, CSRF_COOKIE)))
    const bearer = /^Bearer(?:\s+(.*))?$/i.exec(c.req.header('authorization') ?? '')
    const token = bearer?.[1]?.trim() ?? ''
    c.set(
      'tokenPrincipal',
      bearer === null
        ? undefined
        : token.startsWith(ACCESS_TOKEN_PREFIX)
          ? await accessTokens.principalOf(token)
          : null,
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
  csrfValid: (c: Context) => boolean = userCsrfValid,
) {
  const protection = createMiddleware(async (c, next) => {
    if (c.var.principal !== null && c.var.principal === c.var.tokenPrincipal) {
      return authRefusal(c, 401, 'Authentication required')
    }
    const refusal = await authorize(c)
    if (refusal !== null) return refusal
    if (MUTATING_METHODS.has(c.req.method) && !csrfValid(c)) {
      return c.json(apiError('Invalid CSRF token'), 400)
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
  if (!c.var.principal.roles.includes(AuthRoles.ADMIN))
    return authRefusal(c, 403, 'Admin access required')
  return null
})

/**
 * The exact role `ADMIN`, from an access token or a session. A request that presents a bearer
 * token is decided by that token alone, never by its cookies: an invalid token answers `401`, a
 * token of a non-admin `403`, and a valid one becomes `c.var.principal` and needs no CSRF pair,
 * because a browser never attaches a bearer token on its own. Without a bearer header this is
 * `requireAdmin`. A route opts in only under the rule of the authentication guide.
 */
export const requireAdminOrToken = createMiddleware(async (c, next) => {
  const token = c.var.tokenPrincipal
  if (token === undefined) return requireAdmin(c, next)
  if (token === null) return authRefusal(c, 401, 'Authentication required')
  if (!token.roles.includes(AuthRoles.ADMIN)) return authRefusal(c, 403, 'Admin access required')
  c.set('principal', token)
  await next()
})

/**
 * Guests and signed-in users alike. Mutating requests still need a valid CSRF pair, and a request
 * with a user session must use a token minted for that user.
 */
export const guestCapable = failClosed(() => null, guestCsrfValid)

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
    const supplierId = principal.roles.includes(AuthRoles.SUPPLIER)
      ? await accounts.supplierIdOf(principal.userId)
      : null
    if (supplierId === null) return authRefusal(c, 403, 'Supplier access required')
    c.set('supplierId', supplierId)
    return null
  })
}

function userCsrfValid(c: Context): boolean {
  const principal = c.var.principal
  const csrf = c.var.csrfSession
  return (
    principal !== null &&
    csrf !== null &&
    csrf.userId === principal.userId &&
    headerMatches(c, csrf)
  )
}

function guestCsrfValid(c: Context): boolean {
  const csrf = c.var.csrfSession
  if (csrf === null) return false
  const userId = c.var.principal?.userId ?? null
  if (userId !== null && csrf.userId !== userId) return false
  return headerMatches(c, csrf)
}

function headerMatches(c: Context, csrf: CsrfSession): boolean {
  const supplied = c.req.header(CSRF_HEADER)
  if (supplied === undefined) return false
  const expected = Buffer.from(csrf.token)
  const actual = Buffer.from(supplied)
  return expected.length === actual.length && timingSafeEqual(expected, actual)
}
