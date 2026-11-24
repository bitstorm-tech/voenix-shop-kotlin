import { beforeAll, describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { createGeneratorModule } from '@/modules/generator'
import { GUEST_COOKIE } from '@/platform/auth/cookies'
import { createClientIpRateLimiter } from '@/platform/ratelimit/client-ip-rate-limiter'
import { testDatabase } from '@test-support/database'
import {
  cookieHeader,
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

/** The signed-in visitors of these tests; `magic_coins.user_id` references `users`. */
beforeAll(async () => {
  IMAGE = new Uint8Array(await pngBytes('#336699'))
  CUT_OUT = new Uint8Array(await pngBytes('#993366'))
  for (const id of [7, 8, 9, 10, 12, 13]) {
    await sql`INSERT INTO users (id, email, password_hash)
      VALUES (${id}, ${`customer-${String(id)}@example.com`}, 'hash')`.execute(database.db)
  }
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
) =>
  testApp(
    createGeneratorModule({
      db: database.db,
      settings,
      articleCatalog,
      promptCatalog,
      guestTokens: testAuth.guestTokens,
      rateLimit: createClientIpRateLimiter({
        trustForwardedFor: false,
        peerAddress: () => '203.0.113.7',
        ...rateLimitOptions,
      }).middleware,
      fetch,
    }).routes,
  )

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

    const balance = await target.request('/api/magic-coins/balance', {
      headers: { Cookie: cookieHeader(response) },
    })
    expect(await jsonOf(balance)).toEqual({ balance: 9 })
  })

  test('a signed-in visitor pays from the account and gets no guest cookie', async () => {
    const response = await generate(app(), await signedInHeaders({ userId: 7, roles: [] }))
    expect(response.status).toBe(200)
    expect(new Uint8Array(await response.arrayBuffer())).toEqual(IMAGE)
    expect(setCookies(response, GUEST_COOKIE)).toEqual([])
    expect(await balanceOf(7)).toBe(9)
  })

  test('an empty balance is a 402 with the code the storefront reads, and nothing is spent', async () => {
    await sql`INSERT INTO magic_coins (user_id, balance) VALUES (8, 0)`.execute(database.db)
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
    const balance = await app().request('/api/magic-coins/balance', { headers })
    expect(await jsonOf(balance)).toEqual({ balance: 10 })
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
})

describe('GET /api/magic-coins/balance', () => {
  test('answers 10 for a passive visitor without setting a guest cookie or creating a DB row', async () => {
    const before = (
      await sql<{ count: string }>`SELECT COUNT(*) AS count FROM magic_coins`.execute(database.db)
    ).rows[0]?.count
    const first = await app().request('/api/magic-coins/balance')
    expect(first.status).toBe(200)
    expect(first.headers.get('Cache-Control')).toBe('no-store')
    expect(await jsonOf(first)).toEqual({ balance: 10 })
    expect(setCookies(first, GUEST_COOKIE)).toEqual([])
    const after = (
      await sql<{ count: string }>`SELECT COUNT(*) AS count FROM magic_coins`.execute(database.db)
    ).rows[0]?.count
    expect(after).toBe(before)
  })

  test('a signed-in user reads the account balance', async () => {
    await sql`INSERT INTO magic_coins (user_id, balance) VALUES (13, 4)`.execute(database.db)
    const response = await app().request('/api/magic-coins/balance', {
      headers: await signedInHeaders({ userId: 13, roles: [] }),
    })
    expect(await jsonOf(response)).toEqual({ balance: 4 })
  })

  test('a failed balance lookup is a 500, never "no balance"', async () => {
    await sql`ALTER TABLE magic_coins RENAME TO magic_coins_away`.execute(database.db)
    try {
      const balance = await app().request('/api/magic-coins/balance', {
        headers: await signedInHeaders({ userId: 13, roles: [] }),
      })
      expect(balance.status).toBe(500)
      expect(balance.headers.get('Cache-Control')).toBe('no-store')
      const generation = await generate(app(), await guestHeaders())
      expect(generation.status).toBe(500)
      expect(await jsonOf(generation)).toEqual({ message: 'Internal server error', errors: {} })
    } finally {
      await sql`ALTER TABLE magic_coins_away RENAME TO magic_coins`.execute(database.db)
    }
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
