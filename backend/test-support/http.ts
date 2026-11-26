import { randomBytes } from 'node:crypto'
import type { Hono } from 'hono'
import {
  ACCESS_TOKEN_PREFIX,
  createAuth,
  type SessionAccounts,
  type UserPrincipal,
} from '@/platform/auth/auth'
import {
  authCookies,
  CSRF_COOKIE,
  CSRF_HEADER,
  GUEST_COOKIE,
  SESSION_COOKIE,
  SESSION_DURATION_SECONDS,
} from '@/platform/auth/cookies'
import { createApp } from '@/platform/http/app'
import { createSecretBox } from '@/platform/secret-box'

export const TEST_SESSION_SECRET = 'test-session-secret-with-at-least-32-bytes'
export const TEST_SECRETS_KEY = 'test-production-secrets-key-with-at-least-32-bytes'

/** The secret box of `TEST_SECRETS_KEY`: seals what a fixture writes, opens what a test reads. */
export const testSecrets = createSecretBox(TEST_SECRETS_KEY)

/** The access tokens `testAuth` knows: token → principal, registered by `tokenHeaders`. */
const testAccessTokens = new Map<string, UserPrincipal>()

/**
 * The roles of the users `signedInHeaders` minted a session for, standing in for the account
 * module's database lookup; the last minted session of a user decides.
 */
const testSessionRoles = new Map<number, string[]>()

/** The session lookup of `testAuth`, for a test that builds its own auth. */
export const testSessions: SessionAccounts = {
  principalOf: (userId) => {
    const roles = testSessionRoles.get(userId)
    return Promise.resolve(roles === undefined ? null : { userId, roles })
  },
}

/** The one auth instance every test app and every minted cookie share. */
export const testAuth = createAuth({
  sessionSecret: TEST_SESSION_SECRET,
  secureCookies: false,
  accessTokens: {
    principalOf: (token) => Promise.resolve(testAccessTokens.get(token) ?? null),
    markUsed: () => Promise.resolve(),
  },
  sessions: testSessions,
})
const cookies = authCookies(TEST_SESSION_SECRET)

/** The application with the platform middleware and the given module sub-apps mounted. */
export function testApp(...routes: Hono[]): Hono {
  const app = createApp(testAuth)
  for (const subApp of routes) app.route('/', subApp)
  return app
}

/**
 * The headers of a signed-in client: a session cookie plus a CSRF cookie and header minted for that
 * user. `testAuth` answers `roles` for the user; the composed application reads the user's roles
 * and session version (0 for a fresh user) from its database instead.
 */
export async function signedInHeaders({
  userId = 11,
  roles = ['ADMIN'],
  sessionVersion = 0,
  issuedAt = epochSeconds(),
  expiresAt = issuedAt + SESSION_DURATION_SECONDS,
}: {
  userId?: number
  roles?: string[]
  sessionVersion?: number
  issuedAt?: number
  expiresAt?: number
} = {}): Promise<Record<string, string>> {
  testSessionRoles.set(userId, roles)
  const session = await cookies.session.seal({
    userId,
    sessionVersion,
    issuedAtEpochSeconds: issuedAt,
    expiresAtEpochSeconds: expiresAt,
  })
  const token = newToken()
  const csrf = await cookies.csrf.seal({ token, userId })
  return { Cookie: `${SESSION_COOKIE}=${session}; ${CSRF_COOKIE}=${csrf}`, [CSRF_HEADER]: token }
}

/** Registers a fresh access token for `principal` with `testAuth` and answers its bearer header. */
export function tokenHeaders(
  principal: UserPrincipal = { userId: 11, roles: ['ADMIN'] },
): Record<string, string> {
  const token = `${ACCESS_TOKEN_PREFIX}${newToken()}`
  testAccessTokens.set(token, principal)
  return { Authorization: `Bearer ${token}` }
}

/** Revokes the access token of headers `tokenHeaders` answered. */
export function revokeToken(headers: Record<string, string>): void {
  testAccessTokens.delete((headers.Authorization ?? '').replace(/^Bearer /, ''))
}

/** The headers of a guest that fetched an antiforgery token: a CSRF cookie and header minted for nobody. */
export async function guestHeaders(): Promise<Record<string, string>> {
  const token = newToken()
  const csrf = await cookies.csrf.seal({ token, userId: null })
  return { Cookie: `${CSRF_COOKIE}=${csrf}`, [CSRF_HEADER]: token }
}

/** The `Cookie` pair of a visitor whose guest token is `token`. */
export async function guestCookie(token: string): Promise<string> {
  return `${GUEST_COOKIE}=${await cookies.guest.seal(token)}`
}

const newToken = () => randomBytes(32).toString('base64url')
const epochSeconds = () => Math.floor(Date.now() / 1000)

/** The JSON body of a response as `unknown`, for `expect(…).toEqual(…)`. */
export const jsonOf = (response: Response): Promise<unknown> => response.json()

/** The `Set-Cookie` header values of a response that start with `name=`. */
export const setCookies = (response: Response, name: string): string[] =>
  response.headers.getSetCookie().filter((cookie) => cookie.startsWith(`${name}=`))

/** The `name=value` pairs of a response's `Set-Cookie` headers, joined for a `Cookie` request header. */
export const cookieHeader = (response: Response): string =>
  response.headers
    .getSetCookie()
    .map((cookie) => cookie.split(';')[0] ?? '')
    .join('; ')
