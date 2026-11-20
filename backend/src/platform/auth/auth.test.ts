import { describe, expect, test } from 'bun:test'
import { Hono } from 'hono'
import {
  createAuth,
  guestCapable,
  requireAdmin,
  requireAdminOrToken,
  requireAuthenticated,
  requireSupplier,
  type UserPrincipal,
} from '@/platform/auth/auth'
import {
  CSRF_COOKIE,
  CSRF_HEADER,
  GUEST_COOKIE,
  SESSION_COOKIE,
  SESSION_DURATION_SECONDS,
} from '@/platform/auth/cookies'
import {
  cookieHeader,
  guestHeaders,
  jsonOf,
  setCookies,
  signedInHeaders,
  TEST_SESSION_SECRET,
  testAuth,
  tokenHeaders,
} from '@test-support/http'
import { createApp } from '@/platform/http/app'

const UNAUTHORIZED = { success: false, message: 'Authentication required', code: null }
const CSRF_PROBLEM = { message: 'Invalid CSRF token', errors: {} }
const MUTATING = ['POST', 'PUT', 'PATCH', 'DELETE']

function probeApp(lookups: number[] = [], auth = testAuth) {
  const routes = new Hono()
  routes.get('/probe/public', (c) => c.text('public'))
  routes.post('/probe/sign-in', async (c) => {
    await auth.signIn(c, 11, ['ADMIN'])
    return c.text('signed in')
  })
  routes.post('/probe/sign-out', (c) => {
    testAuth.signOut(c)
    return c.text('signed out')
  })
  routes.get('/probe/guest-token', async (c) => c.text(await auth.guestTokens.getOrCreate(c)))
  routes.get('/probe/guest-token/try', async (c) =>
    c.text((await testAuth.guestTokens.tryGet(c)) ?? 'none'),
  )

  const account = new Hono()
  account.use(requireAuthenticated)
  account.get('/', (c) => c.text(`account ${String(c.var.principal?.userId)}`))
  account.on(MUTATING, '/', (c) => c.text('written'))
  routes.route('/probe/account', account)

  const admin = new Hono()
  admin.use(requireAdmin)
  admin.get('/', (c) => c.text('admin'))
  admin.on(MUTATING, '/', (c) => c.text('written'))
  routes.route('/probe/admin', admin)

  const tokenAdmin = new Hono()
  tokenAdmin.use(requireAdminOrToken)
  tokenAdmin.get('/', (c) => c.text(`admin ${String(c.var.principal?.userId)}`))
  tokenAdmin.on(MUTATING, '/', (c) => c.text(`written by ${String(c.var.principal?.userId)}`))
  routes.route('/probe/token-admin', tokenAdmin)

  const guest = new Hono()
  guest.use(guestCapable)
  guest.get('/', (c) => c.text('cart'))
  guest.post('/', (c) => c.text('written'))
  routes.route('/probe/guest', guest)

  const supplier = new Hono()
  supplier.use(
    requireSupplier({
      supplierIdOf: (userId) => {
        lookups.push(userId)
        return Promise.resolve(userId === 21 ? 7 : null)
      },
    }),
  )
  supplier.get('/', (c) => c.text(`supplier ${String(c.var.supplierId)}`))
  supplier.post('/', (c) => c.text(`shipped ${String(c.var.supplierId)}`))
  routes.route('/probe/supplier', supplier)

  const app = createApp(auth)
  app.route('/', routes)
  return app
}

/** The session cookie alone, without the CSRF pair `signedInHeaders` mints next to it. */
const sessionOnly = (headers: Record<string, string>) => ({
  Cookie: (headers.Cookie ?? '')
    .split('; ')
    .filter((cookie) => cookie.startsWith(`${SESSION_COOKIE}=`))
    .join(''),
})
const csrfOnly = (headers: Record<string, string>) => ({
  Cookie: (headers.Cookie ?? '')
    .split('; ')
    .filter((cookie) => cookie.startsWith(`${CSRF_COOKIE}=`))
    .join(''),
  [CSRF_HEADER]: headers[CSRF_HEADER] ?? '',
})
const now = () => Math.floor(Date.now() / 1000)
const FORBIDDEN = { success: false, message: 'Admin access required', code: null }

/** An auth whose token lookup records every token it is asked about and knows one admin token. */
function recordingAuth(asked: string[], known: Record<string, UserPrincipal> = {}) {
  return createAuth({
    sessionSecret: TEST_SESSION_SECRET,
    secureCookies: false,
    accessTokens: {
      principalOf: (token) => {
        asked.push(token)
        return Promise.resolve(known[token] ?? null)
      },
    },
  })
}
const accessTokens = { principalOf: () => Promise.resolve(null) }

describe('the authenticated protection', () => {
  test('an anonymous request is refused with the auth answer', async () => {
    const app = probeApp()
    for (const method of ['GET', ...MUTATING]) {
      const response = await app.request('/probe/account', { method })
      expect(response.status).toBe(401)
      expect(response.headers.get('content-type')).toStartWith('application/json')
      expect(await jsonOf(response)).toEqual(UNAUTHORIZED)
    }
  })

  test('any signed-in user passes a read without a role requirement', async () => {
    const response = await probeApp().request('/probe/account', {
      headers: await signedInHeaders({ userId: 21, roles: ['CUSTOMER'] }),
    })
    expect(response.status).toBe(200)
    expect(await response.text()).toBe('account 21')
  })

  test('mutating methods need the CSRF pair while a read does not', async () => {
    const app = probeApp()
    const headers = await signedInHeaders({ roles: ['CUSTOMER'] })
    for (const method of MUTATING) {
      const withoutHeader = await app.request('/probe/account', {
        method,
        headers: sessionOnly(headers),
      })
      expect(withoutHeader.status).toBe(400)
      expect(await jsonOf(withoutHeader)).toEqual(CSRF_PROBLEM)
      const invalidHeader = await app.request('/probe/account', {
        method,
        headers: { ...headers, [CSRF_HEADER]: 'invalid' },
      })
      expect(invalidHeader.status).toBe(400)
      const valid = await app.request('/probe/account', { method, headers })
      expect(valid.status).toBe(200)
      expect(await valid.text()).toBe('written')
    }
  })
})

describe('the admin protection', () => {
  test('requires the exact role ADMIN', async () => {
    const app = probeApp()
    const status = async (roles: string[]) =>
      (await app.request('/probe/admin', { headers: await signedInHeaders({ roles }) })).status
    const forbidden = await app.request('/probe/admin', {
      headers: await signedInHeaders({ roles: ['CUSTOMER'] }),
    })
    expect(forbidden.status).toBe(403)
    expect(await jsonOf(forbidden)).toEqual({
      success: false,
      message: 'Admin access required',
      code: null,
    })
    expect(await status(['admin'])).toBe(403)
    expect(await status(['ADMIN'])).toBe(200)
    expect(await status(['CUSTOMER', 'ADMIN'])).toBe(200)
    expect((await app.request('/probe/admin')).status).toBe(401)
  })

  test('requires the CSRF pair for every mutating method', async () => {
    const app = probeApp()
    const headers = await signedInHeaders()
    for (const method of MUTATING) {
      expect(
        (await app.request('/probe/admin', { method, headers: sessionOnly(headers) })).status,
      ).toBe(400)
      expect((await app.request('/probe/admin', { method, headers })).status).toBe(200)
    }
  })
})

describe('the admin-or-token protection', () => {
  test('a valid admin token reads and writes without a CSRF pair and acts as its user', async () => {
    const app = probeApp()
    const headers = tokenHeaders({ userId: 31, roles: ['CUSTOMER', 'ADMIN'] })
    const read = await app.request('/probe/token-admin', { headers })
    expect(read.status).toBe(200)
    expect(await read.text()).toBe('admin 31')
    for (const method of MUTATING) {
      const write = await app.request('/probe/token-admin', { method, headers })
      expect(write.status).toBe(200)
      expect(await write.text()).toBe('written by 31')
    }
  })

  test('a token of a user without the role ADMIN is forbidden', async () => {
    const response = await probeApp().request('/probe/token-admin', {
      headers: tokenHeaders({ userId: 32, roles: ['CUSTOMER'] }),
    })
    expect(response.status).toBe(403)
    expect(await jsonOf(response)).toEqual(FORBIDDEN)
  })

  test('an invalid bearer value is refused, even next to a valid admin session', async () => {
    const asked: string[] = []
    const app = probeApp([], recordingAuth(asked))
    const session = await signedInHeaders()
    for (const authorization of ['Bearer vnx_unknown', 'Bearer not-ours', 'Bearer', 'bearer  ']) {
      for (const method of ['GET', 'POST']) {
        const response = await app.request('/probe/token-admin', {
          method,
          headers: { ...session, Authorization: authorization },
        })
        expect(response.status).toBe(401)
        expect(await jsonOf(response)).toEqual(UNAUTHORIZED)
      }
    }
    // Only a value with the prefix reaches the lookup.
    expect(asked).toEqual(['vnx_unknown', 'vnx_unknown'])
  })

  test('without a bearer header it is the admin protection', async () => {
    const asked: string[] = []
    const app = probeApp([], recordingAuth(asked))
    expect((await app.request('/probe/token-admin')).status).toBe(401)
    const customer = await app.request('/probe/token-admin', {
      headers: await signedInHeaders({ roles: ['CUSTOMER'] }),
    })
    expect(customer.status).toBe(403)
    const admin = await signedInHeaders({ userId: 33 })
    const basic = { ...admin, Authorization: 'Basic dXNlcjpwYXNz' }
    expect(await (await app.request('/probe/token-admin', { headers: basic })).text()).toBe(
      'admin 33',
    )
    for (const method of MUTATING) {
      const withoutPair = await app.request('/probe/token-admin', {
        method,
        headers: sessionOnly(admin),
      })
      expect(withoutPair.status).toBe(400)
      expect(await jsonOf(withoutPair)).toEqual(CSRF_PROBLEM)
      expect((await app.request('/probe/token-admin', { method, headers: admin })).status).toBe(200)
    }
    expect(asked).toEqual([])
  })

  test('a valid admin token is worth nothing on every other protection', async () => {
    const lookups: number[] = []
    const app = probeApp(lookups)
    const token = tokenHeaders({ userId: 21, roles: ['ADMIN', 'SUPPLIER', 'CUSTOMER'] })
    for (const path of ['/probe/admin', '/probe/account', '/probe/supplier']) {
      for (const method of ['GET', 'POST']) {
        const response = await app.request(path, { method, headers: token })
        expect(response.status).toBe(401)
      }
    }
    expect(lookups).toEqual([])
    expect((await app.request('/probe/guest', { headers: token })).status).toBe(200)
    const write = await app.request('/probe/guest', { method: 'POST', headers: token })
    expect(write.status).toBe(400)
    expect(await jsonOf(write)).toEqual(CSRF_PROBLEM)
    // Next to a customer session the session decides, exactly as without the header.
    const customer = await signedInHeaders({ userId: 22, roles: ['CUSTOMER'] })
    const withSession = await app.request('/probe/admin', { headers: { ...customer, ...token } })
    expect(withSession.status).toBe(403)
  })
})

describe('the CSRF token', () => {
  test('is bound to the user it was minted for', async () => {
    const app = probeApp()
    const alice = await signedInHeaders({ userId: 11 })
    const bob = await signedInHeaders({ userId: 12 })
    const mixed = {
      Cookie: `${sessionOnly(bob).Cookie}; ${csrfOnly(alice).Cookie}`,
      [CSRF_HEADER]: alice[CSRF_HEADER] ?? '',
    }
    const response = await app.request('/probe/admin', { method: 'POST', headers: mixed })
    expect(response.status).toBe(400)
    expect(await jsonOf(response)).toEqual(CSRF_PROBLEM)
  })

  test('the antiforgery endpoint mints a token on its canonical path and a replacement invalidates the previous one', async () => {
    const app = probeApp()
    const session = sessionOnly(await signedInHeaders())
    const first = await app.request('/api/antiforgery/token', { headers: session })
    expect(first.status).toBe(200)
    expect(first.headers.get('content-type')).toStartWith('application/json')
    const body = (await jsonOf(first)) as { requestToken: string }
    expect(Object.keys(body)).toEqual(['requestToken'])
    expect(body.requestToken).not.toBe('')
    const firstHeaders = {
      Cookie: `${session.Cookie}; ${cookieHeader(first)}`,
      [CSRF_HEADER]: body.requestToken,
    }
    expect(
      (await app.request('/probe/admin', { method: 'POST', headers: firstHeaders })).status,
    ).toBe(200)

    const second = await app.request('/api/antiforgery/token', { headers: session })
    const replacement = (await jsonOf(second)) as { requestToken: string }
    expect(replacement.requestToken).not.toBe(body.requestToken)
    const stale = {
      Cookie: `${session.Cookie}; ${cookieHeader(second)}`,
      [CSRF_HEADER]: body.requestToken,
    }
    expect((await app.request('/probe/admin', { method: 'POST', headers: stale })).status).toBe(400)

    expect((await app.request('/API/ANTIFORGERY/TOKEN')).status).toBe(404)
    expect((await app.request('/api/antiforgery/token/')).status).toBe(404)
  })
})

describe('the guest-capable protection', () => {
  test('guests read without any session and write with a guest CSRF pair', async () => {
    const app = probeApp()
    expect((await app.request('/probe/guest')).status).toBe(200)
    const withoutToken = await app.request('/probe/guest', { method: 'POST' })
    expect(withoutToken.status).toBe(400)
    expect(await jsonOf(withoutToken)).toEqual(CSRF_PROBLEM)
    const guest = await guestHeaders()
    expect(
      (
        await app.request('/probe/guest', {
          method: 'POST',
          headers: { ...guest, [CSRF_HEADER]: 'invalid' },
        })
      ).status,
    ).toBe(400)
    expect((await app.request('/probe/guest', { method: 'POST', headers: guest })).status).toBe(200)
  })

  test('a signed-in user must use a token minted for that user', async () => {
    const app = probeApp()
    const customer = await signedInHeaders({ userId: 21, roles: ['CUSTOMER'] })
    expect((await app.request('/probe/guest', { method: 'POST', headers: customer })).status).toBe(
      200,
    )
    const guestPair = await guestHeaders()
    const withGuestToken = {
      ...guestPair,
      Cookie: `${sessionOnly(customer).Cookie}; ${guestPair.Cookie ?? ''}`,
    }
    expect(
      (await app.request('/probe/guest', { method: 'POST', headers: withGuestToken })).status,
    ).toBe(400)
    const other = await signedInHeaders({ userId: 99, roles: ['CUSTOMER'] })
    const foreign = {
      Cookie: `${sessionOnly(customer).Cookie}; ${csrfOnly(other).Cookie}`,
      [CSRF_HEADER]: other[CSRF_HEADER] ?? '',
    }
    expect((await app.request('/probe/guest', { method: 'POST', headers: foreign })).status).toBe(
      400,
    )
  })
})

describe('the supplier protection', () => {
  test('refuses anonymous callers and users without the role, without asking the account module', async () => {
    const lookups: number[] = []
    const app = probeApp(lookups)
    expect((await app.request('/probe/supplier')).status).toBe(401)
    for (const roles of [['CUSTOMER'], ['ADMIN']]) {
      const response = await app.request('/probe/supplier', {
        headers: await signedInHeaders({ userId: 21, roles }),
      })
      expect(response.status).toBe(403)
      expect(await jsonOf(response)).toEqual({
        success: false,
        message: 'Supplier access required',
        code: null,
      })
    }
    expect(lookups).toEqual([])
  })

  test('the role without a live link is refused like a missing role; a linked login reaches the handler', async () => {
    const lookups: number[] = []
    const app = probeApp(lookups)
    const revoked = await app.request('/probe/supplier', {
      headers: await signedInHeaders({ userId: 22, roles: ['SUPPLIER'] }),
    })
    expect(revoked.status).toBe(403)
    const linked = await signedInHeaders({ userId: 21, roles: ['SUPPLIER'] })
    const read = await app.request('/probe/supplier', { headers: linked })
    expect(await read.text()).toBe('supplier 7')
    expect(
      (await app.request('/probe/supplier', { method: 'POST', headers: sessionOnly(linked) }))
        .status,
    ).toBe(400)
    const write = await app.request('/probe/supplier', { method: 'POST', headers: linked })
    expect(await write.text()).toBe('shipped 7')
    // The link is resolved before the CSRF check, so the refused write looked it up as well.
    expect(lookups).toEqual([22, 21, 21, 21])
  })
})

describe('the session cookie', () => {
  test('an expired session is anonymous and is not renewed', async () => {
    const response = await probeApp().request('/probe/admin', {
      headers: await signedInHeaders({ issuedAt: 1, expiresAt: 2 }),
    })
    expect(response.status).toBe(401)
    expect(setCookies(response, SESSION_COOKIE)).toEqual([])
  })

  test('a session past half its life is renewed by any request, a fresh one is not', async () => {
    const app = probeApp()
    const fresh = await app.request('/probe/public', { headers: await signedInHeaders() })
    expect(setCookies(fresh, SESSION_COOKIE)).toEqual([])
    const renewable = await app.request('/probe/public', {
      headers: await signedInHeaders({ issuedAt: now() - 13 * 3600, expiresAt: now() + 11 * 3600 }),
    })
    expect(renewable.status).toBe(200)
    expect(setCookies(renewable, SESSION_COOKIE)).toHaveLength(1)
  })

  test('sign-in, the antiforgery token, and the guest cookie are Secure exactly when configured', async () => {
    const flags = (cookie: string, secure: boolean) => {
      expect(cookie).toContain('Path=/')
      expect(cookie).toContain('HttpOnly')
      expect(cookie).toContain('SameSite=Lax')
      expect(cookie.includes('Secure')).toBe(secure)
    }
    for (const secure of [false, true]) {
      const app = probeApp(
        [],
        createAuth({ sessionSecret: TEST_SESSION_SECRET, secureCookies: secure, accessTokens }),
      )
      // The scheme and host of the request do not matter: TLS ends at the proxy.
      for (const origin of ['http://localhost', 'https://localhost', 'http://shop.example.com']) {
        const signIn = await app.request(`${origin}/probe/sign-in`, { method: 'POST' })
        const session = setCookies(signIn, SESSION_COOKIE)[0] ?? ''
        flags(session, secure)
        expect(session).not.toContain('Max-Age')
        expect(session).not.toContain('Expires')
        const csrf = setCookies(await app.request(`${origin}/api/antiforgery/token`), CSRF_COOKIE)
        flags(csrf[0] ?? '', secure)
        const guest = setCookies(await app.request(`${origin}/probe/guest-token`), GUEST_COOKIE)
        flags(guest[0] ?? '', secure)
      }
    }
  })

  test('a signed-in cookie round-trips and a sign-out clears it', async () => {
    const app = probeApp()
    const signedIn = await app.request('/probe/sign-in', { method: 'POST' })
    const admin = await app.request('/probe/admin', { headers: { Cookie: cookieHeader(signedIn) } })
    expect(admin.status).toBe(200)
    const signedOut = await app.request('/probe/sign-out', { method: 'POST' })
    expect(setCookies(signedOut, SESSION_COOKIE)[0]).toContain('Max-Age=0')
    expect(SESSION_DURATION_SECONDS).toBe(86_400)
  })
})

describe('guest tokens', () => {
  test('issues a sealed cookie on first contact and reads it back without setting it again', async () => {
    const app = probeApp()
    const first = await app.request('/probe/guest-token')
    const token = await first.text()
    const cookie = setCookies(first, 'voenix.guest')[0] ?? ''
    expect(cookie).not.toContain(token)
    expect(cookie).toContain('HttpOnly')
    expect(cookie).toContain('Path=/api')
    expect(cookie).toContain('Max-Age=2592000')
    expect(cookie).toContain('SameSite=Lax')
    expect(cookie).not.toContain('Secure')
    const second = await app.request('/probe/guest-token', {
      headers: { Cookie: cookieHeader(first) },
    })
    expect(await second.text()).toBe(token)
    expect(setCookies(second, 'voenix.guest')).toEqual([])
  })

  test('a tampered cookie makes a new guest; tryGet never issues a cookie', async () => {
    const app = probeApp()
    const tampered = await app.request('/probe/guest-token', {
      headers: { Cookie: 'voenix.guest=not-a-valid-value' },
    })
    expect(tampered.status).toBe(200)
    expect(setCookies(tampered, 'voenix.guest')).toHaveLength(1)
    const missing = await app.request('/probe/guest-token/try')
    expect(await missing.text()).toBe('none')
    expect(setCookies(missing, 'voenix.guest')).toEqual([])
    const read = await app.request('/probe/guest-token/try', {
      headers: { Cookie: cookieHeader(tampered) },
    })
    expect(await read.text()).toBe(await tampered.text())
    expect(setCookies(read, 'voenix.guest')).toEqual([])
  })
})
