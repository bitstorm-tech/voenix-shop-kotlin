import { beforeAll, beforeEach, describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { createEmailModule } from '@/modules/email'
import { createGeneratorModule } from '@/modules/generator'
import { CSRF_HEADER, GUEST_COOKIE } from '@/platform/auth/cookies'
import { createClientIpRateLimiter } from '@/platform/ratelimit/client-ip-rate-limiter'
import { testDatabase } from '@test-support/database'
import {
  guestHeaders,
  jsonOf,
  setCookies,
  signedInHeaders,
  testApp,
  testAuth,
} from '@test-support/http'
import { pngBytes } from '@test-support/values'

const database = testDatabase()

let IMAGE: Uint8Array<ArrayBuffer>
let CUT_OUT: Uint8Array<ArrayBuffer>

const ALERT_EMAIL = 'ops@voenix.example'
const FRONTEND_BASE_URL = 'https://shop.voenix.example'

const USER_CREATED_AT = new Date('2026-09-01T10:00:00.000Z')

/** The signed-in visitors and admins of these tests; `magic_coins.user_id` references `users`. */
beforeAll(async () => {
  IMAGE = new Uint8Array(await pngBytes('#336699'))
  CUT_OUT = new Uint8Array(await pngBytes('#993366'))
  for (const id of [1, 7, 8, 9, 10, 12, 13, 14, 15, 16, 17, 18]) {
    await sql`INSERT INTO users (id, email, password_hash, created_at, shipping_first_name, shipping_last_name)
      VALUES (${id}, ${id === 1 ? 'admin@voenix.example' : `customer-${String(id)}@example.com`}, 'hash', ${USER_CREATED_AT}, ${id === 14 ? 'Anna' : null}, ${id === 14 ? 'Muster' : null})`.execute(
      database.db,
    )
  }
})

beforeEach(async () => {
  await sql`TRUNCATE magic_coin_requests, magic_coin_request_blocks, email_jobs RESTART IDENTITY CASCADE`.execute(
    database.db,
  )
})

/** Article 1 is printed wide, article 2 square; prompt 1 has a text. Everything else is unknown. */
const articleCatalog = {
  printFormats: (ids: Iterable<number>) =>
    Promise.resolve(
      new Map(
        [...ids].flatMap((id): [number, '16:9' | '1:1'][] =>
          id === 1 ? [[1, '16:9']] : id === 2 ? [[2, '1:1']] : [],
        ),
      ),
    ),
}
const promptCatalog = {
  composedText: (id: number) => Promise.resolve(id === 1 ? 'Ein Mops im Weltall' : null),
}

const accountsFromDb = () => ({
  findByIds: async (userIds: readonly number[]) => {
    const unique = [...new Set(userIds)]
    if (unique.length === 0) return new Map()
    const rows = await database.db
      .selectFrom('users')
      .select(['id', 'email', 'created_at', 'shipping_first_name', 'shipping_last_name'])
      .where('id', 'in', unique)
      .execute()
    return new Map(
      rows.map((row) => [
        row.id,
        {
          userId: row.id,
          email: row.email,
          name:
            row.shipping_first_name && row.shipping_last_name
              ? `${row.shipping_first_name} ${row.shipping_last_name}`
              : null,
          createdAt: row.created_at,
        },
      ]),
    )
  },
  findByEmail: async (email: string) => {
    const row = await database.db
      .selectFrom('users')
      .select(['id', 'email', 'created_at', 'shipping_first_name', 'shipping_last_name'])
      .where((eb) => eb(eb.fn('lower', ['email']), '=', eb.fn('lower', [eb.val(email)])))
      .executeTakeFirst()
    if (row === undefined) return undefined
    return {
      userId: row.id,
      email: row.email,
      name:
        row.shipping_first_name && row.shipping_last_name
          ? `${row.shipping_first_name} ${row.shipping_last_name}`
          : null,
      createdAt: row.created_at,
    }
  },
})

const emailOutbox = () =>
  createEmailModule({
    db: database.db,
    settings: {
      enabled: false,
      pollIntervalMinutes: 1,
      apiKey: '',
      fromEmail: 'shop@voenix.example',
      fromName: 'Voenix Shop',
    },
    fetch: () => Promise.reject(new Error('no network in this test')),
  }).outbox

/** The module on a limiter of its own, every request coming from one address. */
const app = (
  settings = { dummyMode: true, apiKey: '' },
  fetch: (url: string, init: RequestInit) => Promise<Response> = () =>
    Promise.reject(new Error('no network in this test')),
  rateLimitOptions: {
    limit?: number
    globalLimit?: number
    trustForwardedFor?: boolean
  } = {},
  extras: {
    paidOrderCounts?: (ids: readonly number[]) => Promise<Map<number, number>>
    activeCartItemCounts?: (ids: readonly number[]) => Promise<Map<number, number>>
    alertEmails?: readonly string[]
    requestLimit?: number
  } = {},
) => {
  const module = createGeneratorModule({
    db: database.db,
    settings,
    articleCatalog,
    promptCatalog,
    accounts: accountsFromDb(),
    orders: {
      paidOrderCounts: extras.paidOrderCounts ?? (() => Promise.resolve(new Map())),
    },
    carts: {
      activeCartItemCounts: extras.activeCartItemCounts ?? (() => Promise.resolve(new Map())),
    },
    outbox: emailOutbox(),
    alertEmails: extras.alertEmails ?? [ALERT_EMAIL],
    frontendBaseUrl: FRONTEND_BASE_URL,
    guestTokens: testAuth.guestTokens,
    rateLimit: createClientIpRateLimiter({
      trustForwardedFor: false,
      peerAddress: () => '203.0.113.7',
      ...rateLimitOptions,
    }).middleware,
    requestRateLimit: createClientIpRateLimiter({
      trustForwardedFor: false,
      peerAddress: () => '203.0.113.7',
      limit: extras.requestLimit ?? 10,
      windowSeconds: 3600,
    }).middleware,
    fetch,
  })
  return testApp(module.routes)
}

const upload = ({ promptId = '1', articleId = '1' } = {}) => {
  const form = new FormData()
  form.append('image', new Blob([IMAGE], { type: 'image/png' }), 'cropped.png')
  form.append('promptId', promptId)
  form.append('articleId', articleId)
  return form
}

const generate = (
  target: ReturnType<typeof app>,
  headers: Record<string, string>,
  body: FormData = upload(),
) => target.request('/api/generator/generate', { method: 'POST', headers, body })

const balanceOf = async (userId: number) =>
  (
    await sql<{
      balance: number
    }>`SELECT balance FROM magic_coins WHERE user_id = ${userId}`.execute(database.db)
  ).rows[0]?.balance

const queuedEmails = async () =>
  (await database.db.selectFrom('email_jobs').select('payload').orderBy('id').execute()).map(
    (row) => row.payload,
  )

const jsonPost = async (
  target: ReturnType<typeof app>,
  path: string,
  headers: Record<string, string>,
  body?: unknown,
) =>
  target.request(path, {
    method: 'POST',
    headers: {
      ...headers,
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })

describe('POST /api/generator/generate', () => {
  test('a guest gets the uploaded bytes back in dummy mode, a guest cookie, and pays one coin', async () => {
    const target = app()
    const guest = await guestHeaders()
    const response = await generate(target, guest)
    expect(response.status).toBe(200)
    expect(response.headers.get('content-type')).toBe('image/png')
    expect(response.headers.get('content-disposition')).toBeNull()
    expect(new Uint8Array(await response.arrayBuffer())).toEqual(IMAGE)
    expect(setCookies(response, GUEST_COOKIE)).toHaveLength(1)

    const guestRows = await database.db
      .selectFrom('magic_coins')
      .select('balance')
      .where('guest_session_token', 'is not', null)
      .execute()
    expect(guestRows.map((row) => row.balance)).toContain(9)
    // The public balance endpoint is gone so remaining balances are never exposed.
    expect((await target.request('/api/magic-coins/balance')).status).toBe(404)
  })

  test('a signed-in visitor pays from the account and gets no guest cookie', async () => {
    const response = await generate(app(), await signedInHeaders({ userId: 7, roles: [] }))
    expect(response.status).toBe(200)
    expect(new Uint8Array(await response.arrayBuffer())).toEqual(IMAGE)
    expect(setCookies(response, GUEST_COOKIE)).toEqual([])
    expect(await balanceOf(7)).toBe(9)
  })

  test('an empty balance is a 402 with the code the storefront reads, and nothing is spent', async () => {
    await sql`INSERT INTO magic_coins (user_id, balance) VALUES (8, 0)
      ON CONFLICT (user_id) WHERE user_id IS NOT NULL DO UPDATE SET balance = 0`.execute(
      database.db,
    )
    const response = await generate(app(), await signedInHeaders({ userId: 8, roles: [] }))
    expect(response.status).toBe(402)
    expect(await jsonOf(response)).toEqual({
      message: 'Not enough Magic Coins',
      errors: {},
      code: 'INSUFFICIENT_MAGIC_COINS',
    })
    expect(await balanceOf(8)).toBe(0)
  })

  test('an unknown prompt and an unknown article are 404s of their own, and free', async () => {
    const headers = await signedInHeaders({ userId: 9, roles: [] })
    const prompt = await generate(app(), headers, upload({ promptId: '99' }))
    expect(prompt.status).toBe(404)
    expect(await jsonOf(prompt)).toEqual({ message: 'Prompt not found', errors: {} })
    const article = await generate(app(), headers, upload({ articleId: '99' }))
    expect(article.status).toBe(404)
    expect(await jsonOf(article)).toEqual({ message: 'Article not found', errors: {} })
    expect(await balanceOf(9)).toBeUndefined()
  })

  test('a rejected upload names the field the client has to fix, including non-image bytes', async () => {
    const form = new FormData()
    form.append('promptId', '1')
    const response = await generate(app(), await guestHeaders(), form)
    expect(response.status).toBe(400)
    expect(await jsonOf(response)).toEqual({
      message: 'Validation failed',
      errors: { image: ['An image file is required'] },
    })

    const fake = new FormData()
    fake.append(
      'image',
      new Blob([new Uint8Array([1, 2, 3, 4])], { type: 'image/png' }),
      'fake.png',
    )
    fake.append('promptId', '1')
    fake.append('articleId', '1')
    const fakeResponse = await generate(app(), await guestHeaders(), fake)
    expect(fakeResponse.status).toBe(400)
    expect(await jsonOf(fakeResponse)).toEqual({
      message: 'Validation failed',
      errors: { image: ['Unsupported or invalid image data'] },
    })
  })

  test('outside dummy mode the provider is asked, and its failure is a 502 that refunds the coin', async () => {
    const requested: string[] = []
    const target = app({ dummyMode: false, apiKey: 'key' }, (url) => {
      requested.push(url)
      return Promise.resolve(new Response('busy', { status: 503 }))
    })
    const response = await generate(target, await signedInHeaders({ userId: 10, roles: [] }))
    expect(response.status).toBe(502)
    expect(await jsonOf(response)).toEqual({ message: 'Generator API error', errors: {} })
    expect(requested).toEqual(['https://fal.run/fal-ai/nano-banana-2/edit'])
    expect(await balanceOf(10)).toBe(10)
  })

  test('enforces the global hourly cap across rotating IPs', async () => {
    const target = app({ dummyMode: true, apiKey: '' }, undefined, {
      limit: 20,
      globalLimit: 2,
      trustForwardedFor: true,
    })
    const fromIp = async (ip: string) =>
      generate(target, { ...(await guestHeaders()), 'X-Forwarded-For': ip })

    expect((await fromIp('203.0.113.1')).status).toBe(200)
    expect((await fromIp('203.0.113.2')).status).toBe(200)
    const refused = await fromIp('203.0.113.3')
    expect(refused.status).toBe(429)
    expect(await jsonOf(refused)).toEqual({ message: 'Too many requests', errors: {} })
    expect(Number(refused.headers.get('Retry-After'))).toBeGreaterThanOrEqual(1)
  })

  test('the 21st generation of an IP is refused with Retry-After before anything runs', async () => {
    const target = app()
    for (let call = 0; call < 20; call++) {
      // A fresh guest each time, so every one of the twenty can afford its generation.
      expect((await generate(target, await guestHeaders())).status).toBe(200)
    }
    const refused = await generate(target, await signedInHeaders({ userId: 12, roles: [] }))
    expect(refused.status).toBe(429)
    expect(await jsonOf(refused)).toEqual({ message: 'Too many requests', errors: {} })
    const retryAfter = Number(refused.headers.get('Retry-After'))
    expect(retryAfter).toBeGreaterThanOrEqual(1)
    expect(retryAfter).toBeLessThanOrEqual(3600)
    expect(await balanceOf(12)).toBeUndefined()
  })

  test('requests without a CSRF token are refused before the limiter counts them', async () => {
    const target = app()
    for (let call = 0; call < 25; call++) {
      expect((await generate(target, {})).status).toBe(400)
    }
    expect((await generate(target, await guestHeaders())).status).toBe(200)
  })

  test('a body that is no multipart form is refused before the limiter counts it', async () => {
    const target = app()
    const headers = { ...(await guestHeaders()), 'content-type': 'application/json' }
    for (let call = 0; call < 25; call++) {
      const refused = await target.request('/api/generator/generate', {
        method: 'POST',
        headers,
        body: '{}',
      })
      expect(refused.status).toBe(415)
    }
    expect((await generate(target, await guestHeaders())).status).toBe(200)
  })

  test('a failed balance lookup is a 500, never "no balance"', async () => {
    await sql`ALTER TABLE magic_coins RENAME TO magic_coins_away`.execute(database.db)
    try {
      const generation = await generate(app(), await guestHeaders())
      expect(generation.status).toBe(500)
      expect(await jsonOf(generation)).toEqual({ message: 'Internal server error', errors: {} })
    } finally {
      await sql`ALTER TABLE magic_coins_away RENAME TO magic_coins`.execute(database.db)
    }
  })
})

describe('Customer Magic Coin request routes', () => {
  const seedZeroBalance = async (userId: number) => {
    await sql`INSERT INTO magic_coins (user_id, balance) VALUES (${userId}, 0)
      ON CONFLICT (user_id) WHERE user_id IS NOT NULL DO UPDATE SET balance = 0`.execute(
      database.db,
    )
  }

  test('require authentication and return no-store headers', async () => {
    const target = app()
    expect((await target.request('/api/magic-coins/request-status')).status).toBe(401)
    expect((await target.request('/api/magic-coins/requests', { method: 'POST' })).status).toBe(401)

    const headers = await signedInHeaders({ userId: 13, roles: ['USER'] })
    const status = await target.request('/api/magic-coins/request-status', { headers })
    expect(status.status).toBe(200)
    expect(status.headers.get('Cache-Control')).toBe('no-store')
    expect(await jsonOf(status)).toEqual({ status: 'CAN_REQUEST' })

    const withoutCsrf = Object.fromEntries(
      Object.entries(headers).filter(([key]) => key !== CSRF_HEADER),
    )
    const forged = await target.request('/api/magic-coins/requests', {
      method: 'POST',
      headers: withoutCsrf,
    })
    expect(forged.status).toBe(400)
  })

  test('refuses a request when the user still has Magic Coins available', async () => {
    await sql`DELETE FROM magic_coins WHERE user_id = 13`.execute(database.db)
    const target = app()
    const headers = await signedInHeaders({ userId: 13, roles: ['USER'] })
    const response = await jsonPost(target, '/api/magic-coins/requests', headers)
    expect(response.status).toBe(409)
    expect(await jsonOf(response)).toEqual({
      message: 'You still have Magic Coins available',
      errors: {},
      code: 'MAGIC_COINS_AVAILABLE',
    })
    expect(await queuedEmails()).toEqual([])
  })

  test('creates a pending request, enqueues an admin alert, and prevents duplicates', async () => {
    await seedZeroBalance(14)
    const target = app()
    const headers = await signedInHeaders({ userId: 14, roles: ['USER'] })

    const created = await jsonPost(target, '/api/magic-coins/requests', headers)
    expect(created.status).toBe(201)
    expect(created.headers.get('Cache-Control')).toBe('no-store')
    expect(await jsonOf(created)).toMatchObject({
      id: expect.any(Number) as unknown,
      status: 'PENDING',
    })

    const status = await target.request('/api/magic-coins/request-status', { headers })
    expect(await jsonOf(status)).toEqual({ status: 'PENDING' })

    const duplicate = await jsonPost(target, '/api/magic-coins/requests', headers)
    expect(duplicate.status).toBe(409)
    expect(await jsonOf(duplicate)).toEqual({
      message: 'A Magic Coin request is already pending',
      errors: {},
      code: 'MAGIC_COIN_REQUEST_ALREADY_PENDING',
    })

    const mails = await queuedEmails()
    expect(mails).toHaveLength(1)
    expect(mails[0]).toMatchObject({
      kind: 'MAGIC_COIN_REQUEST_ALERT',
      recipient: ALERT_EMAIL,
      subject: 'Neue Magic-Coins-Anfrage',
    })
    expect(mails[0]?.text).toContain('customer-14@example.com')
    expect(mails[0]?.text).toContain(`${FRONTEND_BASE_URL}/admin/magic-coins`)
  })

  test('a refill request alerts every operations address with a mail of its own', async () => {
    await seedZeroBalance(16)
    const target = app(undefined, undefined, undefined, {
      alertEmails: [ALERT_EMAIL, 'second@voenix.example'],
    })
    const headers = await signedInHeaders({ userId: 16, roles: ['USER'] })

    expect((await jsonPost(target, '/api/magic-coins/requests', headers)).status).toBe(201)
    const recipients = (await queuedEmails()).map((mail) => mail.recipient)
    expect(recipients.toSorted()).toEqual([ALERT_EMAIL, 'second@voenix.example'])
  })

  test('rate limits refill request submissions', async () => {
    await seedZeroBalance(15)
    const target = app(undefined, undefined, undefined, { requestLimit: 1 })
    const headers = await signedInHeaders({ userId: 15, roles: ['USER'] })

    expect((await jsonPost(target, '/api/magic-coins/requests', headers)).status).toBe(201)
    const limited = await jsonPost(target, '/api/magic-coins/requests', headers)
    expect(limited.status).toBe(429)
    expect(Number(limited.headers.get('Retry-After'))).toBeGreaterThanOrEqual(1)
  })
})

describe('Admin Magic Coins routes', () => {
  const seedZeroBalance = async (userId: number) => {
    await sql`INSERT INTO magic_coins (user_id, balance) VALUES (${userId}, 0)
      ON CONFLICT (user_id) WHERE user_id IS NOT NULL DO UPDATE SET balance = 0`.execute(
      database.db,
    )
  }

  test('every admin route requires an admin session', async () => {
    const target = app()
    const user = await signedInHeaders({ userId: 14, roles: ['USER'] })
    expect((await target.request('/api/admin/magic-coins/requests')).status).toBe(401)
    expect(
      (await target.request('/api/admin/magic-coins/requests', { headers: user })).status,
    ).toBe(403)
    expect((await target.request('/api/admin/magic-coins/blocks', { headers: user })).status).toBe(
      403,
    )
    expect(
      (
        await jsonPost(target, '/api/admin/magic-coins/grants', user, {
          email: 'a@b.de',
          amount: 5,
        })
      ).status,
    ).toBe(403)
  })

  test('lists pending requests with verification signals and approves atomically with a customer mail', async () => {
    await seedZeroBalance(14)
    const target = app(undefined, undefined, undefined, {
      paidOrderCounts: () => Promise.resolve(new Map([[14, 2]])),
      activeCartItemCounts: () => Promise.resolve(new Map([[14, 3]])),
    })
    const customer = await signedInHeaders({ userId: 14, roles: ['USER'] })
    const admin = await signedInHeaders({ userId: 1, roles: ['ADMIN'] })

    const created = await jsonPost(target, '/api/magic-coins/requests', customer)
    expect(created.status).toBe(201)
    const requestId = ((await jsonOf(created)) as { id: number }).id

    const list = await target.request('/api/admin/magic-coins/requests?status=PENDING', {
      headers: admin,
    })
    expect(list.status).toBe(200)
    expect(list.headers.get('Cache-Control')).toBe('no-store')
    expect(await jsonOf(list)).toEqual([
      expect.objectContaining({
        id: requestId,
        userId: 14,
        userEmail: 'customer-14@example.com',
        userName: 'Anna Muster',
        accountCreatedAt: USER_CREATED_AT.toISOString(),
        currentBalance: 0,
        paidOrderCount: 2,
        activeCartItemCount: 3,
        totalRequestCount: 1,
        totalApprovedCoins: 0,
        status: 'PENDING',
        grantedCoins: null,
        blockedUser: false,
        decidedByEmail: null,
        isCurrentlyBlocked: false,
      }),
    ])

    const invalid = await jsonPost(
      target,
      `/api/admin/magic-coins/requests/${String(requestId)}/approve`,
      admin,
      { amount: 0 },
    )
    expect(invalid.status).toBe(400)

    const approved = await jsonPost(
      target,
      `/api/admin/magic-coins/requests/${String(requestId)}/approve`,
      admin,
      { amount: 15 },
    )
    expect(approved.status).toBe(200)
    expect(await jsonOf(approved)).toMatchObject({
      id: requestId,
      status: 'APPROVED',
      grantedCoins: 15,
      newBalance: 15,
    })
    expect(await balanceOf(14)).toBe(15)

    // Second approval is a conflict.
    const again = await jsonPost(
      target,
      `/api/admin/magic-coins/requests/${String(requestId)}/approve`,
      admin,
      { amount: 5 },
    )
    expect(again.status).toBe(409)
    expect(await balanceOf(14)).toBe(15)

    // Customer received a mail without the exact coin count.
    const mails = await queuedEmails()
    expect(mails.map((m) => m.kind)).toEqual(['MAGIC_COIN_REQUEST_ALERT', 'MAGIC_COINS_GRANTED'])
    expect(mails[1]).toMatchObject({
      recipient: 'customer-14@example.com',
      subject: 'Du hast neue Magic Coins erhalten',
    })
    expect(mails[1]?.text).not.toContain('15')

    const decided = await target.request('/api/admin/magic-coins/requests?status=DECIDED', {
      headers: admin,
    })
    expect(await jsonOf(decided)).toEqual([
      expect.objectContaining({
        id: requestId,
        status: 'APPROVED',
        grantedCoins: 15,
        decidedByEmail: 'admin@voenix.example',
        totalApprovedCoins: 15,
      }),
    ])
  })

  test('soft reject allows requesting again; reject with blockUser blocks until unblocked', async () => {
    await seedZeroBalance(16)
    const target = app()
    const customer = await signedInHeaders({ userId: 16, roles: ['USER'] })
    const admin = await signedInHeaders({ userId: 1, roles: ['ADMIN'] })

    const first = (
      (await jsonOf(await jsonPost(target, '/api/magic-coins/requests', customer))) as {
        id: number
      }
    ).id

    const softRejected = await jsonPost(
      target,
      `/api/admin/magic-coins/requests/${String(first)}/reject`,
      admin,
      { blockUser: false },
    )
    expect(softRejected.status).toBe(200)
    expect(await jsonOf(softRejected)).toMatchObject({
      id: first,
      status: 'REJECTED',
      blockedUser: false,
    })
    // Rejecting never sends a customer mail.
    expect((await queuedEmails()).filter((m) => m.kind === 'MAGIC_COINS_GRANTED')).toEqual([])

    // Customer can request again after a soft reject.
    const secondRes = await jsonPost(target, '/api/magic-coins/requests', customer)
    expect(secondRes.status).toBe(201)
    const second = ((await jsonOf(secondRes)) as { id: number }).id

    const hardRejected = await jsonPost(
      target,
      `/api/admin/magic-coins/requests/${String(second)}/reject`,
      admin,
      { blockUser: true },
    )
    expect(hardRejected.status).toBe(200)
    expect(await jsonOf(hardRejected)).toMatchObject({
      id: second,
      status: 'REJECTED',
      blockedUser: true,
    })

    // Status is now BLOCKED and submitting returns 403.
    expect(
      await jsonOf(await target.request('/api/magic-coins/request-status', { headers: customer })),
    ).toEqual({ status: 'BLOCKED' })
    const blockedSubmit = await jsonPost(target, '/api/magic-coins/requests', customer)
    expect(blockedSubmit.status).toBe(403)
    expect(await jsonOf(blockedSubmit)).toMatchObject({ code: 'MAGIC_COINS_REQUEST_BLOCKED' })

    // Listed under blocks, then unblocked.
    const blocks = await target.request('/api/admin/magic-coins/blocks', { headers: admin })
    expect(blocks.status).toBe(200)
    expect(await jsonOf(blocks)).toEqual([
      expect.objectContaining({
        userId: 16,
        userEmail: 'customer-16@example.com',
        blockedByEmail: 'admin@voenix.example',
      }),
    ])

    const unblocked = await target.request('/api/admin/magic-coins/blocks/16', {
      method: 'DELETE',
      headers: admin,
    })
    expect(unblocked.status).toBe(204)
    expect(
      (
        await target.request('/api/admin/magic-coins/blocks/16', {
          method: 'DELETE',
          headers: admin,
        })
      ).status,
    ).toBe(404)

    expect(
      await jsonOf(await target.request('/api/magic-coins/request-status', { headers: customer })),
    ).toEqual({ status: 'CAN_REQUEST' })
  })

  test('manual grant credits coins by email, auto-approves any pending request, and mails the customer', async () => {
    await seedZeroBalance(17)
    const target = app()
    const customer = await signedInHeaders({ userId: 17, roles: ['USER'] })
    const admin = await signedInHeaders({ userId: 1, roles: ['ADMIN'] })

    expect((await jsonPost(target, '/api/magic-coins/requests', customer)).status).toBe(201)

    const unknown = await jsonPost(target, '/api/admin/magic-coins/grants', admin, {
      email: 'nobody@example.com',
      amount: 10,
    })
    expect(unknown.status).toBe(404)

    const granted = await jsonPost(target, '/api/admin/magic-coins/grants', admin, {
      email: '  CUSTOMER-17@Example.com ',
      amount: 12,
    })
    expect(granted.status).toBe(200)
    expect(await jsonOf(granted)).toEqual({
      userId: 17,
      userEmail: 'customer-17@example.com',
      grantedCoins: 12,
      newBalance: 12,
    })
    expect(await balanceOf(17)).toBe(12)

    // The open pending request was automatically resolved as APPROVED.
    const pending = await target.request('/api/admin/magic-coins/requests?status=PENDING', {
      headers: admin,
    })
    expect(await jsonOf(pending)).toEqual([])

    const grantedMails = (await queuedEmails()).filter((m) => m.kind === 'MAGIC_COINS_GRANTED')
    expect(grantedMails).toHaveLength(1)
    expect(grantedMails[0]?.recipient).toBe('customer-17@example.com')

    // Granting to a user without a magic_coins row starts from INITIAL_BALANCE (10) + amount.
    await sql`DELETE FROM magic_coins WHERE user_id = 18`.execute(database.db)
    const freshGrant = await jsonPost(target, '/api/admin/magic-coins/grants', admin, {
      email: 'customer-18@example.com',
      amount: 5,
    })
    expect(freshGrant.status).toBe(200)
    expect(await jsonOf(freshGrant)).toMatchObject({ newBalance: 15 })
    expect(await balanceOf(18)).toBe(15)
  })
})

describe('POST /api/admin/images/background-removal', () => {
  const REMOVAL = '/api/admin/images/background-removal'

  // Bun's multipart parser takes a part's type from its file name, so the name carries the type.
  const form = (file?: Blob, name = 'photo.png') => {
    const body = new FormData()
    if (file !== undefined) body.append('file', file, name)
    return body
  }
  const png = (bytes: Uint8Array = IMAGE) => new Blob([bytes], { type: 'image/png' })
  const remove = (
    target: ReturnType<typeof app>,
    headers: Record<string, string>,
    body = form(png()),
  ) => target.request(REMOVAL, { method: 'POST', headers, body })

  /** fal.ai answering a cut-out, with every request it received. */
  const paidApp = (
    answer = () =>
      new Response(
        JSON.stringify({
          image: { url: 'https://cdn.example.com/cut.png', content_type: 'image/png' },
        }),
      ),
  ) => {
    const urls: string[] = []
    const target = app({ dummyMode: false, apiKey: 'key' }, (url) => {
      urls.push(url)
      return Promise.resolve(
        url === 'https://cdn.example.com/cut.png' ? new Response(CUT_OUT) : answer(),
      )
    })
    return { target, urls }
  }

  test('is for admins only', async () => {
    expect((await remove(app(), {})).status).toBe(401)
    expect((await remove(app(), await signedInHeaders({ roles: ['CUSTOMER'] }))).status).toBe(403)
  })

  test('answers the cut-out from fal.ai as raw bytes', async () => {
    const { target, urls } = paidApp()
    const response = await remove(target, await signedInHeaders())
    expect(response.status).toBe(200)
    expect(response.headers.get('Content-Type')).toBe('image/png')
    expect(new Uint8Array(await response.arrayBuffer())).toEqual(CUT_OUT)
    expect(urls).toEqual([
      'https://fal.run/fal-ai/bria/background/remove',
      'https://cdn.example.com/cut.png',
    ])
  })

  test('answers the image unchanged in dummy mode', async () => {
    const response = await remove(app(), await signedInHeaders())
    expect(response.status).toBe(200)
    expect(new Uint8Array(await response.arrayBuffer())).toEqual(IMAGE)
  })

  test('refuses a missing, empty, oversized, corrupt, or non-image file on the file field', async () => {
    const { target, urls } = paidApp()
    for (const body of [
      form(),
      form(png(new Uint8Array())),
      form(png(new Uint8Array(10 * 1024 * 1024 + 1))),
      form(png(new Uint8Array([1, 2, 3, 4]))),
      form(new Blob([IMAGE], { type: 'image/gif' }), 'photo.gif'),
    ]) {
      const response = await remove(target, await signedInHeaders(), body)
      expect(response.status).toBe(400)
      expect(await jsonOf(response)).toMatchObject({
        message: 'Validation failed',
        errors: { file: [expect.any(String)] },
      })
    }
    expect(urls).toEqual([])
  })

  test('a provider failure is a 502', async () => {
    const { target } = paidApp(() => new Response('nope', { status: 500 }))
    const response = await remove(target, await signedInHeaders())
    expect(response.status).toBe(502)
    expect(await jsonOf(response)).toEqual({ message: 'Background removal failed', errors: {} })
  })
})
