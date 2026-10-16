import { beforeEach, describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { createAccountModule } from '@/modules/account'
import { EmailDeliveryError, type RenderedEmail, type UserEmailKind } from '@/modules/email'
import { testDatabase } from '@test-support/database'
import {
  cookieHeader,
  jsonOf,
  setCookies,
  signedInHeaders,
  testApp,
  testAuth,
} from '@test-support/http'

const database = testDatabase()
const FRONTEND = 'http://localhost:5173'

/** The clock of the module; tests move it forward instead of sleeping. */
let current = new Date('2026-07-24T10:00:00Z')
const advance = (milliseconds: number) => {
  current = new Date(current.getTime() + milliseconds)
}
const HOUR = 60 * 60 * 1000

/** The recording mail fake: every accepted mail, or the failure the next sends throw. */
const sent: RenderedEmail<UserEmailKind>[] = []
let failure: (() => Error) | null = null
const userEmails = {
  send: (email: RenderedEmail<UserEmailKind>) => {
    if (failure !== null) return Promise.reject(failure())
    sent.push(email)
    return Promise.resolve()
  },
}

beforeEach(() => {
  failure = null
})

let module: ReturnType<typeof createAccountModule> | undefined
const accounts = () =>
  (module ??= createAccountModule({
    db: database.db,
    auth: testAuth,
    userEmails,
    frontendBaseUrl: FRONTEND,
    now: () => current,
    pbkdf2Iterations: 1_000,
  }))
const app = () => testApp(accounts().routes)

const send = (method: string, path: string, body?: unknown, headers: Record<string, string> = {}) =>
  app().request(path, {
    method,
    headers: { ...headers, 'content-type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
const post = (path: string, body?: unknown, headers?: Record<string, string>) =>
  send('POST', path, body, headers)

/** The query parameters of the last mailed link to `path`; links come from mails, never the database. */
function lastLink(path: string): URLSearchParams {
  for (const mail of sent.toReversed()) {
    const match = new RegExp(`${FRONTEND}${path}\\?\\S+`).exec(mail.text)
    if (match !== null) return new URL(match[0]).searchParams
  }
  throw new Error(`No mail links to ${path}`)
}
const param = (link: URLSearchParams, name: string) => link.get(name) ?? ''

const confirmLink = (link: URLSearchParams) =>
  post('/api/auth/confirm-email', {
    userId: Number(param(link, 'userId')),
    token: param(link, 'token'),
  })

async function registerAndConfirm(email: string, password: string): Promise<number> {
  expect((await post('/api/auth/register', { email, password })).status).toBe(204)
  const link = lastLink('/confirm-email')
  expect((await confirmLink(link)).status).toBe(204)
  return Number(param(link, 'userId'))
}

/** A real login, then the antiforgery token for that session: the headers of a signed-in browser. */
async function signIn(email: string, password: string): Promise<Record<string, string>> {
  const login = await post('/api/auth/login', { email, password })
  expect(login.status).toBe(204)
  const session = cookieHeader(login)
  const antiforgery = await app().request('/api/antiforgery/token', {
    headers: { Cookie: session },
  })
  const { requestToken } = (await antiforgery.json()) as { requestToken: string }
  return { Cookie: `${session}; ${cookieHeader(antiforgery)}`, 'X-XSRF-TOKEN': requestToken }
}

const customer = (userId: number) => signedInHeaders({ userId: userId, roles: ['CUSTOMER'] })

describe('POST /api/auth/register and POST /api/auth/confirm-email', () => {
  test('registers, mails the confirmation link, and the link confirms once', async () => {
    const registered = await post('/api/auth/register', {
      email: ' erika@example.com ',
      password: 'password-1',
    })
    expect(registered.status).toBe(204)
    expect(setCookies(registered, 'voenix.guest')).toEqual([])
    expect(sent.at(-1)?.kind).toBe('ACCOUNT_CONFIRMATION')
    expect(sent.at(-1)?.recipient).toBe('erika@example.com')
    const link = lastLink('/confirm-email')

    expect(
      (await post('/api/auth/login', { email: 'erika@example.com', password: 'password-1' }))
        .status,
    ).toBe(403)
    expect((await confirmLink(link)).status).toBe(204)
    const reused = await confirmLink(link)
    expect(reused.status).toBe(400)
    expect(await jsonOf(reused)).toEqual({
      message: 'Invalid or expired confirmation link',
      errors: {},
      code: 'INVALID_LINK',
    })
  })

  test('a taken address, also in another case, answers 409', async () => {
    await registerAndConfirm('taken@example.com', 'password-1')
    const duplicate = await post('/api/auth/register', {
      email: 'TAKEN@example.com',
      password: 'password-2',
    })
    expect(duplicate.status).toBe(409)
    expect(await jsonOf(duplicate)).toEqual({ message: 'Email already exists', errors: {} })
  })

  test('two concurrent case-variant registrations leave one account and one 409', async () => {
    const statuses = await Promise.all([
      post('/api/auth/register', { email: 'race@example.com', password: 'password-1' }),
      post('/api/auth/register', { email: 'Race@Example.com', password: 'password-2' }),
    ]).then((responses) => responses.map((response) => response.status).sort())
    expect(statuses).toEqual([204, 409])
    const rows = await database.db
      .selectFrom('users')
      .select('id')
      .where(sql<string>`lower(email)`, '=', 'race@example.com')
      .execute()
    expect(rows).toHaveLength(1)
  })

  test('only the latest confirmation link counts, and a link expires after 24 hours', async () => {
    await post('/api/auth/register', { email: 'late@example.com', password: 'password-1' })
    const first = lastLink('/confirm-email')
    expect(
      (await post('/api/auth/resend-confirmation', { email: 'late@example.com' })).status,
    ).toBe(204)
    const second = lastLink('/confirm-email')
    expect((await confirmLink(first)).status).toBe(400)

    advance(24 * HOUR + 1000)
    const expired = await confirmLink(second)
    expect(expired.status).toBe(400)
    expect(((await jsonOf(expired)) as { code: string }).code).toBe('INVALID_LINK')

    await post('/api/auth/resend-confirmation', { email: 'late@example.com' })
    expect((await confirmLink(lastLink('/confirm-email'))).status).toBe(204)
  })

  test('a refused confirmation mail answers 502 and the resend flow recovers', async () => {
    failure = () => new EmailDeliveryError()
    const failed = await post('/api/auth/register', {
      email: 'outage@example.com',
      password: 'password-1',
    })
    expect(failed.status).toBe(502)
    expect(await jsonOf(failed)).toEqual({
      message: 'Confirmation email could not be delivered',
      errors: {},
    })
    failure = null
    await post('/api/auth/resend-confirmation', { email: 'outage@example.com' })
    expect((await confirmLink(lastLink('/confirm-email'))).status).toBe(204)
  })

  test('a mail bug of ours answers a plain 500 that names neither recipient nor cause', async () => {
    failure = () => new Error('renderer bug for bug@example.com')
    const failed = await post('/api/auth/register', {
      email: 'bug@example.com',
      password: 'password-1',
    })
    expect(failed.status).toBe(500)
    expect(await jsonOf(failed)).toEqual({ message: 'Internal server error', errors: {} })
  })
})

describe('POST /api/auth/resend-confirmation', () => {
  test('always answers 204 and mails only an unconfirmed account', async () => {
    await registerAndConfirm('confirmed@example.com', 'password-1')
    const before = sent.length
    for (const email of ['confirmed@example.com', 'nobody@example.com']) {
      expect((await post('/api/auth/resend-confirmation', { email })).status).toBe(204)
    }
    expect(sent.length).toBe(before)
  })
})

describe('POST /api/auth/login and POST /api/auth/logout', () => {
  test('signs in with the session cookie, keeps the guest cookie alone, and logs out', async () => {
    const userId = await registerAndConfirm('login@example.com', 'password-1')
    const login = await post(
      '/api/auth/login',
      { email: ' LOGIN@example.com ', password: 'password-1' },
      { Cookie: 'voenix.guest=an-existing-guest-cookie' },
    )
    expect(login.status).toBe(204)
    expect(setCookies(login, 'voenix.auth')).toHaveLength(1)
    expect(setCookies(login, 'voenix.guest')).toEqual([])

    const headers = await signIn('login@example.com', 'password-1')
    const me = await app().request('/api/auth/me', { headers })
    expect(((await jsonOf(me)) as { id: number }).id).toBe(userId)

    const logout = await post('/api/auth/logout', undefined, headers)
    expect(logout.status).toBe(204)
    const cleared = setCookies(logout, 'voenix.auth')
    expect(cleared).toHaveLength(1)
    expect(cleared[0]).toContain('Max-Age=0')
    expect(setCookies(logout, 'voenix.guest')).toEqual([])
  })

  test('an unknown e-mail and a wrong password answer the same 401', async () => {
    await registerAndConfirm('uniform@example.com', 'password-1')
    const unknown = await post('/api/auth/login', {
      email: 'nobody@example.com',
      password: 'password-1',
    })
    const wrong = await post('/api/auth/login', {
      email: 'uniform@example.com',
      password: 'wrong-password',
    })
    expect(unknown.status).toBe(401)
    expect(wrong.status).toBe(401)
    const body = await unknown.text()
    expect(JSON.parse(body)).toEqual({ message: 'Invalid email or password', errors: {} })
    expect(await wrong.text()).toBe(body)
  })

  test('fifteen failures lock for ten minutes, and a success resets the counter', async () => {
    await registerAndConfirm('locked@example.com', 'password-1')
    const attempt = (password: string) =>
      post('/api/auth/login', { email: 'locked@example.com', password })
    for (let i = 0; i < 14; i++) expect((await attempt('wrong-password')).status).toBe(401)
    const locked = await attempt('wrong-password')
    expect(locked.status).toBe(429)
    expect(await jsonOf(locked)).toEqual({ message: 'Too many failed login attempts', errors: {} })
    expect((await attempt('password-1')).status).toBe(429)

    advance(10 * 60 * 1000 + 1000)
    expect((await attempt('wrong-password')).status).toBe(401)
    expect((await attempt('password-1')).status).toBe(204)
    for (let i = 0; i < 14; i++) await attempt('wrong-password')
    expect((await attempt('password-1')).status).toBe(204)
    expect((await attempt('wrong-password')).status).toBe(401)
  })

  test('a password hash stored by the Kotlin backend still signs in', async () => {
    const { id } = await database.db
      .insertInto('users')
      .values({
        email: 'kotlin@example.com',
        email_confirmed: true,
        password_hash: 'v1$1000$AxQlNkdYaXqLnK2+z+DxAg$jIQE06UtHKRdiMLPTPLfsUpDdV4VpJtlvZcGJ9LCSnQ',
      })
      .returning('id')
      .executeTakeFirstOrThrow()
    await database.db.insertInto('user_roles').values({ user_id: id, role: 'ADMIN' }).execute()
    const headers = await signIn('kotlin@example.com', 'correct horse battery')
    const me = await app().request('/api/auth/me', { headers })
    expect(((await jsonOf(me)) as { roles: string[] }).roles).toEqual(['ADMIN'])
  })
})

describe('POST /api/auth/forgot-password and POST /api/auth/reset-password', () => {
  test('mails a single-use reset link that replaces the password and expires after 24 hours', async () => {
    await registerAndConfirm('reset@example.com', 'password-1')
    const before = sent.length
    expect((await post('/api/auth/forgot-password', { email: 'nobody@example.com' })).status).toBe(
      204,
    )
    expect(sent.length).toBe(before)

    const reset = (link: URLSearchParams, newPassword: string) =>
      post('/api/auth/reset-password', {
        email: param(link, 'email'),
        token: param(link, 'token'),
        newPassword,
      })

    await post('/api/auth/forgot-password', { email: 'reset@example.com' })
    const expiring = lastLink('/reset-password')
    expect(param(expiring, 'email')).toBe('reset@example.com')
    advance(24 * HOUR + 1000)
    const expired = await reset(expiring, 'password-2')
    expect(expired.status).toBe(400)
    expect(await jsonOf(expired)).toEqual({
      message: 'Invalid or expired password reset link',
      errors: {},
      code: 'INVALID_LINK',
    })

    await post('/api/auth/forgot-password', { email: 'reset@example.com' })
    const link = lastLink('/reset-password')
    expect((await reset(link, 'password-2')).status).toBe(204)
    expect(sent.at(-1)?.kind).toBe('PASSWORD_CHANGED')
    expect((await reset(link, 'password-3')).status).toBe(400)
    expect(
      (await post('/api/auth/login', { email: 'reset@example.com', password: 'password-1' }))
        .status,
    ).toBe(401)
    expect(
      (await post('/api/auth/login', { email: 'reset@example.com', password: 'password-2' }))
        .status,
    ).toBe(204)
  })

  test('a provider failure still answers 204', async () => {
    await registerAndConfirm('quiet@example.com', 'password-1')
    failure = () => new EmailDeliveryError()
    expect((await post('/api/auth/forgot-password', { email: 'quiet@example.com' })).status).toBe(
      204,
    )
  })
})

describe('GET /api/auth/me and PUT /api/auth/profile', () => {
  test('answers the profile, replaces it in full, and clears billing with the flag', async () => {
    const userId = await registerAndConfirm('profile@example.com', 'password-1')
    const headers = await customer(userId)
    const me = await app().request('/api/auth/me', { headers })
    expect(me.status).toBe(200)
    expect(await jsonOf(me)).toEqual({
      id: userId,
      email: 'profile@example.com',
      roles: ['CUSTOMER'],
      shippingAddress: null,
      billingAddress: null,
      hasSeparateBillingAddress: false,
      createdAt: current.toISOString(),
    })

    const put = (body: unknown) => send('PUT', '/api/auth/profile', body, headers)
    const updated = await put({
      shippingAddress: { firstName: ' Erika ', country: ' DE ', phone: '   ' },
      hasSeparateBillingAddress: true,
      billingAddress: { firstName: 'Max', city: 'Hamburg' },
    })
    expect(updated.status).toBe(200)
    const empty = {
      firstName: null,
      lastName: null,
      street: null,
      houseNumber: null,
      postalCode: null,
      city: null,
      country: null,
      phone: null,
    }
    expect(await jsonOf(updated)).toMatchObject({
      shippingAddress: { ...empty, firstName: 'Erika', country: 'DE' },
      billingAddress: { ...empty, firstName: 'Max', city: 'Hamburg' },
      hasSeparateBillingAddress: true,
    })

    const cleared = await put({
      shippingAddress: { lastName: 'Musterfrau' },
      hasSeparateBillingAddress: false,
      billingAddress: { firstName: 'Max' },
    })
    expect(await jsonOf(cleared)).toMatchObject({
      shippingAddress: { ...empty, lastName: 'Musterfrau' },
      billingAddress: null,
      hasSeparateBillingAddress: false,
    })
  })

  test('a session whose user is gone answers 401', async () => {
    const headers = await customer(99_999)
    const me = await app().request('/api/auth/me', { headers })
    expect(me.status).toBe(401)
    expect(await jsonOf(me)).toEqual({ message: 'User not found', errors: {} })
    const put = await send('PUT', '/api/auth/profile', { shippingAddress: {} }, headers)
    expect(put.status).toBe(401)
  })
})

describe('POST /api/auth/change-email and POST /api/auth/confirm-change-email', () => {
  test('mails both addresses, confirms the new one, and refuses a stale or foreign link', async () => {
    const userId = await registerAndConfirm('old@example.com', 'password-1')
    await registerAndConfirm('taken-new@example.com', 'password-2')
    const headers = await customer(userId)
    const change = (newEmail: string, currentPassword = 'password-1') =>
      post('/api/auth/change-email', { newEmail, currentPassword }, headers)

    const wrong = await change('new@example.com', 'wrong')
    expect(wrong.status).toBe(401)
    expect(await jsonOf(wrong)).toEqual({ message: 'Invalid password', errors: {} })
    expect((await change('TAKEN-NEW@example.com')).status).toBe(409)

    expect((await change('new@example.com')).status).toBe(204)
    expect(sent.slice(-2).map((mail) => [mail.kind, mail.recipient])).toEqual([
      ['CHANGE_EMAIL_CONFIRMATION', 'new@example.com'],
      ['CHANGE_EMAIL_NOTIFICATION', 'old@example.com'],
    ])
    const link = lastLink('/confirm-change-email')
    const confirm = (newEmail: string) =>
      post('/api/auth/confirm-change-email', {
        userId: Number(param(link, 'userId')),
        newEmail,
        token: param(link, 'token'),
      })
    const foreign = await confirm('different@example.com')
    expect(foreign.status).toBe(400)
    expect(await jsonOf(foreign)).toEqual({
      message: 'Invalid or expired confirmation link',
      errors: {},
      code: 'INVALID_LINK',
    })
    // A refused link is not consumed; the right address still confirms.
    expect((await confirm(param(link, 'newEmail'))).status).toBe(204)
    const me = await app().request('/api/auth/me', { headers })
    expect(((await jsonOf(me)) as { email: string }).email).toBe('new@example.com')
    expect(
      (await post('/api/auth/login', { email: 'old@example.com', password: 'password-1' })).status,
    ).toBe(401)
  })

  test('an address taken meanwhile answers 409 and keeps the link; a link expires after 24 hours', async () => {
    const userId = await registerAndConfirm('mover@example.com', 'password-1')
    const headers = await customer(userId)
    await post(
      '/api/auth/change-email',
      { newEmail: 'contested@example.com', currentPassword: 'password-1' },
      headers,
    )
    const link = lastLink('/confirm-change-email')
    const confirm = () =>
      post('/api/auth/confirm-change-email', {
        userId,
        newEmail: param(link, 'newEmail'),
        token: param(link, 'token'),
      })

    await registerAndConfirm('contested@example.com', 'password-2')
    const conflict = await confirm()
    expect(conflict.status).toBe(409)
    expect(await jsonOf(conflict)).toEqual({ message: 'Email already exists', errors: {} })

    advance(24 * HOUR + 1000)
    expect((await confirm()).status).toBe(400)
  })

  test('a refused confirmation mail answers 502', async () => {
    const userId = await registerAndConfirm('stuck@example.com', 'password-1')
    failure = () => new EmailDeliveryError()
    const failed = await post(
      '/api/auth/change-email',
      { newEmail: 'unreachable@example.com', currentPassword: 'password-1' },
      await customer(userId),
    )
    expect(failed.status).toBe(502)
  })
})

describe('POST /api/auth/change-password', () => {
  test('needs the current password, keeps the session, and survives a failing notice', async () => {
    const headers = await (async () => {
      await registerAndConfirm('rotate@example.com', 'password-1')
      return signIn('rotate@example.com', 'password-1')
    })()
    const change = (currentPassword: string) =>
      post('/api/auth/change-password', { currentPassword, newPassword: 'password-2' }, headers)

    const wrong = await change('wrong')
    expect(wrong.status).toBe(401)
    expect(await jsonOf(wrong)).toEqual({ message: 'Invalid password', errors: {} })

    failure = () => new Error('a rendering bug of ours')
    expect((await change('password-1')).status).toBe(204)
    expect((await app().request('/api/auth/me', { headers })).status).toBe(200)
    failure = null
    expect(
      (await post('/api/auth/login', { email: 'rotate@example.com', password: 'password-2' }))
        .status,
    ).toBe(204)
  })
})

describe('/api/admin/supplier-logins', () => {
  const admin = () => signedInHeaders({ userId: 1, roles: ['ADMIN'] })
  const supplier = async (name: string) => {
    const { rows } = await sql<{
      id: number
    }>`INSERT INTO suppliers (name) VALUES (${name}) RETURNING id`.execute(database.db)
    return rows[0]?.id ?? 0
  }
  const create = async (supplierId: number, email: string) =>
    post('/api/admin/supplier-logins', { supplierId, email }, await admin())
  const listed = async (supplierId: number) => {
    const response = await app().request(
      `/api/admin/supplier-logins?supplierId=${String(supplierId)}`,
      {
        headers: await admin(),
      },
    )
    expect(response.status).toBe(200)
    return ((await response.json()) as { email: string }[]).map((login) => login.email)
  }
  const remove = async (userId: string) =>
    send('DELETE', `/api/admin/supplier-logins/${userId}`, undefined, await admin())

  test('an invited supplier sets a password, signs in, and loses the link when deleted', async () => {
    const alpha = await supplier('Alpha')
    const beta = await supplier('Beta')
    const created = await create(alpha, 'logistik@lieferant.example')
    expect(created.status).toBe(201)
    const view = (await jsonOf(created)) as Record<string, unknown> & { userId: number }
    expect(view).toEqual({
      userId: view.userId,
      email: 'logistik@lieferant.example',
      supplierId: alpha,
      createdAt: current.toISOString(),
    })
    expect(created.headers.get('Location')).toBe(
      `/api/admin/supplier-logins/${String(view.userId)}`,
    )
    expect(await accounts().supplierAccounts.supplierIdOf(view.userId)).toBe(alpha)

    // The address counts as confirmed; only the unknown random password is missing.
    expect(
      (await post('/api/auth/login', { email: 'logistik@lieferant.example', password: 'guess' }))
        .status,
    ).toBe(401)
    expect(sent.at(-1)?.kind).toBe('SUPPLIER_INVITATION')
    const invitation = lastLink('/set-password')
    expect(param(invitation, 'email')).toBe('logistik@lieferant.example')
    const setPassword = await post('/api/auth/reset-password', {
      email: param(invitation, 'email'),
      token: param(invitation, 'token'),
      newPassword: 'supplier-password-1',
    })
    expect(setPassword.status).toBe(204)
    const headers = await signIn('logistik@lieferant.example', 'supplier-password-1')
    const me = await app().request('/api/auth/me', { headers })
    expect(((await jsonOf(me)) as { roles: string[] }).roles).toEqual(['SUPPLIER'])

    expect(await listed(alpha)).toEqual(['logistik@lieferant.example'])
    expect(await listed(beta)).toEqual([])

    expect((await remove(String(view.userId))).status).toBe(204)
    expect(await listed(alpha)).toEqual([])
    expect(await accounts().supplierAccounts.supplierIdOf(view.userId)).toBeNull()
    expect(
      (
        await post('/api/auth/login', {
          email: 'logistik@lieferant.example',
          password: 'supplier-password-1',
        })
      ).status,
    ).toBe(401)
    expect((await remove(String(view.userId))).status).toBe(404)
  })

  test('a taken address, an unknown supplier, and a non-supplier id are refused', async () => {
    const gamma = await supplier('Gamma')
    const customerId = await registerAndConfirm('kundin@example.com', 'password-1')
    expect(await accounts().supplierAccounts.supplierIdOf(customerId)).toBeNull()

    const duplicate = await create(gamma, 'KUNDIN@example.com')
    expect(duplicate.status).toBe(409)
    expect(await jsonOf(duplicate)).toEqual({ message: 'Email already exists', errors: {} })
    expect(await listed(gamma)).toEqual([])

    const unknown = await create(4_711_000, 'zweite@lieferant.example')
    expect(unknown.status).toBe(400)
    expect(await jsonOf(unknown)).toEqual({
      message: 'Validation failed',
      errors: { supplierId: ['Supplier does not exist'] },
    })

    for (const id of [String(customerId), '99999', 'not-a-number']) {
      const response = await remove(id)
      expect(response.status).toBe(404)
      expect(await jsonOf(response)).toEqual({ message: 'Supplier login not found', errors: {} })
    }
  })

  test('a refused invitation answers 502, keeps the login, and forgot-password recovers it', async () => {
    const delta = await supplier('Delta')
    failure = () => new EmailDeliveryError()
    const created = await create(delta, 'ausfall@lieferant.example')
    expect(created.status).toBe(502)
    expect(await jsonOf(created)).toEqual({
      message: 'The supplier login was created, but its invitation email could not be delivered',
      errors: {},
    })
    failure = null
    expect(await listed(delta)).toEqual(['ausfall@lieferant.example'])

    await post('/api/auth/forgot-password', { email: 'ausfall@lieferant.example' })
    const link = lastLink('/reset-password')
    const reset = await post('/api/auth/reset-password', {
      email: param(link, 'email'),
      token: param(link, 'token'),
      newPassword: 'supplier-password-2',
    })
    expect(reset.status).toBe(204)
    expect(
      (
        await post('/api/auth/login', {
          email: 'ausfall@lieferant.example',
          password: 'supplier-password-2',
        })
      ).status,
    ).toBe(204)
  })

  test('an unusable supplierId query answers 400', async () => {
    const response = await app().request('/api/admin/supplier-logins?supplierId=abc', {
      headers: await admin(),
    })
    expect(response.status).toBe(400)
    expect(await jsonOf(response)).toEqual({
      message: 'Validation failed',
      errors: { supplierId: ['A positive supplier id is required'] },
    })
  })
})
