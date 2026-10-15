import { randomBytes } from 'node:crypto'
import type { Hono } from 'hono'
import { createAuth } from '@/platform/auth/auth'
import {
  authCookies,
  CSRF_COOKIE,
  CSRF_HEADER,
  SESSION_COOKIE,
  SESSION_DURATION_SECONDS,
} from '@/platform/auth/cookies'
import { createApp } from '@/platform/http/app'

export const TEST_SESSION_SECRET = 'test-session-secret-with-at-least-32-bytes'

/** The one auth instance every test app and every minted cookie share. */
export const testAuth = createAuth({ sessionSecret: TEST_SESSION_SECRET })
const cookies = authCookies(TEST_SESSION_SECRET)

/** The application with the platform middleware and the given module sub-apps mounted. */
export function testApp(...routes: Hono[]): Hono {
  const app = createApp(testAuth)
  for (const subApp of routes) app.route('/', subApp)
  return app
}

/** The headers of a signed-in client: a session cookie plus a CSRF cookie and header minted for that user. */
export async function signedInHeaders({
  userId = '11',
  roles = ['ADMIN'],
  issuedAt = epochSeconds(),
  expiresAt = issuedAt + SESSION_DURATION_SECONDS,
}: { userId?: string; roles?: string[]; issuedAt?: number; expiresAt?: number } = {}): Promise<
  Record<string, string>
> {
  const session = await cookies.session.seal({
    userId,
    roles,
    issuedAtEpochSeconds: issuedAt,
    expiresAtEpochSeconds: expiresAt,
  })
  const token = newToken()
  const csrf = await cookies.csrf.seal({ token, userId })
  return { Cookie: `${SESSION_COOKIE}=${session}; ${CSRF_COOKIE}=${csrf}`, [CSRF_HEADER]: token }
}

/** The headers of a guest that fetched an antiforgery token: a CSRF cookie and header minted for nobody. */
export async function guestHeaders(): Promise<Record<string, string>> {
  const token = newToken()
  const csrf = await cookies.csrf.seal({ token, userId: null })
  return { Cookie: `${CSRF_COOKIE}=${csrf}`, [CSRF_HEADER]: token }
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
