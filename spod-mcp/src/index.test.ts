import { afterEach, describe, expect, test } from 'bun:test'
import { Client } from '@modelcontextprotocol/client'
import { InMemoryTransport } from '@modelcontextprotocol/server'
import { createSpodMcpServer, loadSpodMcpConfig, type Fetch, type SpodMcpConfig } from './index'

const STAGING_CONFIG: SpodMcpConfig = {
  environment: 'STAGING',
  accessToken: 'spod-secret-token',
  allowWrite: true,
  timeoutSeconds: 10,
}

const PRODUCTION_READONLY_CONFIG: SpodMcpConfig = {
  environment: 'PRODUCTION',
  accessToken: 'spod-prod-token',
  allowWrite: false,
  timeoutSeconds: 10,
}

interface RecordedRequest {
  url: string
  method: string
  headers: Record<string, string>
  body: string | null
  redirect: RequestInit['redirect']
}

const closers: (() => Promise<void>)[] = []
afterEach(async () => {
  await Promise.all(closers.splice(0).map((close) => close()))
})

async function connect(
  config: SpodMcpConfig,
  responder: (request: RecordedRequest) => Response | Promise<Response> = () =>
    Response.json({ ok: true }),
) {
  const requests: RecordedRequest[] = []
  const waits: number[] = []
  let clock = 1000
  const fetchStub: Fetch = async (url, init) => {
    const headers: Record<string, string> = {}
    new Headers(init.headers).forEach((value, key) => {
      headers[key] = value
    })
    const recorded: RecordedRequest = {
      url,
      method: init.method ?? 'GET',
      headers,
      body: typeof init.body === 'string' ? init.body : null,
      redirect: init.redirect,
    }
    requests.push(recorded)
    return responder(recorded)
  }

  const server = createSpodMcpServer(config, {
    fetch: fetchStub,
    now: () => clock,
    sleep: (millis) => {
      waits.push(millis)
      clock += millis
      return Promise.resolve()
    },
  })
  const [clientTransport, serverTransport] = InMemoryTransport.createLinkedPair()
  const client = new Client({ name: 'spod-mcp-test', version: '1.0.0' })
  await Promise.all([server.connect(serverTransport), client.connect(clientTransport)])
  closers.push(async () => {
    await Promise.all([client.close(), server.close()])
  })
  return {
    client,
    requests,
    waits,
    advanceClock: (millis: number) => {
      clock += millis
    },
  }
}

async function call(client: Client, name: string, args: Record<string, unknown>) {
  const result = await client.callTool({ name, arguments: args })
  const [block] = result.content as { type: string; text: string }[]
  return { text: block?.text ?? '', isError: result.isError === true }
}

describe('loadSpodMcpConfig', () => {
  test('defaults to STAGING with writes enabled and 30 s timeout', () => {
    expect(loadSpodMcpConfig({ SPOD_ACCESS_TOKEN: '  tok-123  ' })).toEqual({
      environment: 'STAGING',
      accessToken: 'tok-123',
      allowWrite: true,
      timeoutSeconds: 30,
    })
  })

  test('disables writes on PRODUCTION unless SPOD_ALLOW_PRODUCTION_WRITE is true', () => {
    expect(
      loadSpodMcpConfig({
        SPOD_ACCESS_TOKEN: 'tok',
        SPOD_ENVIRONMENT: 'production',
      }),
    ).toEqual({
      environment: 'PRODUCTION',
      accessToken: 'tok',
      allowWrite: false,
      timeoutSeconds: 30,
    })

    expect(
      loadSpodMcpConfig({
        SPOD_ACCESS_TOKEN: 'tok',
        SPOD_ENVIRONMENT: 'PRODUCTION',
        SPOD_ALLOW_PRODUCTION_WRITE: 'true',
        SPOD_TIMEOUT_SECONDS: '45',
      }),
    ).toEqual({
      environment: 'PRODUCTION',
      accessToken: 'tok',
      allowWrite: true,
      timeoutSeconds: 45,
    })
  })

  test('rejects missing token, unknown environment, bad flag, and out-of-range timeout', () => {
    expect(() => loadSpodMcpConfig({})).toThrow('SPOD_ACCESS_TOKEN')
    expect(() => loadSpodMcpConfig({ SPOD_ACCESS_TOKEN: '   ' })).toThrow('SPOD_ACCESS_TOKEN')
    expect(() =>
      loadSpodMcpConfig({ SPOD_ACCESS_TOKEN: 'tok', SPOD_ENVIRONMENT: 'LOCAL' }),
    ).toThrow('SPOD_ENVIRONMENT')
    expect(() =>
      loadSpodMcpConfig({ SPOD_ACCESS_TOKEN: 'tok', SPOD_ALLOW_PRODUCTION_WRITE: 'yes' }),
    ).toThrow('SPOD_ALLOW_PRODUCTION_WRITE')
    expect(() =>
      loadSpodMcpConfig({ SPOD_ACCESS_TOKEN: 'tok', SPOD_TIMEOUT_SECONDS: '0' }),
    ).toThrow('SPOD_TIMEOUT_SECONDS')
    expect(() =>
      loadSpodMcpConfig({ SPOD_ACCESS_TOKEN: 'tok', SPOD_TIMEOUT_SECONDS: '121' }),
    ).toThrow('SPOD_TIMEOUT_SECONDS')
  })
})

describe('createSpodMcpServer', () => {
  test('exposes spod_read and spod_write on STAGING', async () => {
    const { client } = await connect(STAGING_CONFIG)
    const { tools } = await client.listTools()
    expect(
      tools
        .map((tool) => ({ name: tool.name, annotations: tool.annotations }))
        .sort((a, b) => a.name.localeCompare(b.name)),
    ).toEqual([
      { name: 'spod_read', annotations: { readOnlyHint: true } },
      { name: 'spod_write', annotations: { destructiveHint: true } },
    ])
    expect(client.getInstructions()).toContain(
      'Environment: STAGING (https://rest.spod-staging.com)',
    )
    expect(client.getInstructions()).toContain(
      'Write access (spod_write) is enabled against STAGING.',
    )
  })

  test('hides spod_write on PRODUCTION unless explicitly allowed', async () => {
    const readonly = await connect(PRODUCTION_READONLY_CONFIG)
    expect((await readonly.client.listTools()).tools.map((tool) => tool.name)).toEqual([
      'spod_read',
    ])
    expect(readonly.client.getInstructions()).toContain('SPOD_ALLOW_PRODUCTION_WRITE=true')

    const writable = await connect({ ...PRODUCTION_READONLY_CONFIG, allowWrite: true })
    expect((await writable.client.listTools()).tools.map((tool) => tool.name).sort()).toEqual([
      'spod_read',
      'spod_write',
    ])
    expect(writable.client.getInstructions()).toContain('ENABLED against PRODUCTION')
  })

  test('spod_read sends X-SPOD-ACCESS-TOKEN without redirects and pretty-prints JSON', async () => {
    const { client, requests } = await connect(STAGING_CONFIG, () =>
      Response.json({ count: 2, items: [{ id: '1' }] }),
    )
    const answer = await call(client, 'spod_read', { path: '/articles?limit=10&offset=0' })
    expect(answer.isError).toBe(false)
    expect(answer.text).toBe(
      `HTTP 200\n${JSON.stringify({ count: 2, items: [{ id: '1' }] }, null, 2)}`,
    )
    expect(requests).toHaveLength(1)
    expect(requests[0]).toEqual({
      url: 'https://rest.spod-staging.com/articles?limit=10&offset=0',
      method: 'GET',
      headers: {
        'x-spod-access-token': 'spod-secret-token',
        accept: 'application/json',
      },
      body: null,
      redirect: 'manual',
    })
  })

  test('spod_read returns raw error body on 4xx/5xx and refuses redirects', async () => {
    const errSession = await connect(STAGING_CONFIG, () =>
      Response.json({ reason: 'NO_ORDER_ITEMS' }, { status: 422 }),
    )
    const errAnswer = await call(errSession.client, 'spod_read', { path: '/orders/missing' })
    expect(errAnswer.isError).toBe(true)
    expect(errAnswer.text).toContain('HTTP 422')
    expect(errAnswer.text).toContain('NO_ORDER_ITEMS')

    const redirectSession = await connect(
      STAGING_CONFIG,
      () => new Response(null, { status: 302, headers: { location: 'https://evil.example/' } }),
    )
    const redirectAnswer = await call(redirectSession.client, 'spod_read', { path: '/articles' })
    expect(redirectAnswer.isError).toBe(true)
    expect(redirectAnswer.text).toBe('HTTP 302\nRedirect refused (redirects are never followed)')
  })

  test('spod_read rejects paths leaving the SPOD origin without calling fetch', async () => {
    const { client, requests } = await connect(STAGING_CONFIG)
    for (const badPath of [
      'https://evil.example/articles',
      '//evil.example/articles',
      '/\\evil.example/articles',
      'articles',
    ]) {
      const answer = await call(client, 'spod_read', { path: badPath })
      expect(answer.isError).toBe(true)
      expect(answer.text).toContain('Refused:')
    }
    expect(requests).toHaveLength(0)
  })

  test('spod_read summarizes binary answers, handles 204, and caps oversized bodies', async () => {
    const binary = await connect(
      STAGING_CONFIG,
      () =>
        new Response(new Uint8Array([137, 80, 78, 71]), {
          status: 200,
          headers: { 'content-type': 'image/png' },
        }),
    )
    const binaryAnswer = await call(binary.client, 'spod_read', { path: '/some-image' })
    expect(binaryAnswer.isError).toBe(false)
    expect(binaryAnswer.text).toBe(
      'HTTP 200\nimage/png, 4 bytes: binary responses are not returned through MCP',
    )

    const empty = await connect(STAGING_CONFIG, () => new Response(null, { status: 204 }))
    expect(await call(empty.client, 'spod_read', { path: '/empty' })).toEqual({
      text: 'HTTP 204',
      isError: false,
    })

    const huge = await connect(
      STAGING_CONFIG,
      () =>
        new Response(new Uint8Array(2 * 1024 * 1024 + 1), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
    )
    const hugeAnswer = await call(huge.client, 'spod_read', { path: '/huge' })
    expect(hugeAnswer.isError).toBe(true)
    expect(hugeAnswer.text).toContain('exceeded')
  })

  test('spod_read reports timeout and network errors without leaking the token', async () => {
    const timedOut = await connect(STAGING_CONFIG, () => {
      const error = new Error('timed out with spod-secret-token')
      error.name = 'TimeoutError'
      return Promise.reject(error)
    })
    const timeoutAnswer = await call(timedOut.client, 'spod_read', { path: '/articles' })
    expect(timeoutAnswer).toEqual({ text: 'Request timed out after 10 s', isError: true })

    const failed = await connect(STAGING_CONFIG, () =>
      Promise.reject(new TypeError('failed for spod-secret-token')),
    )
    const failedAnswer = await call(failed.client, 'spod_read', { path: '/articles' })
    expect(failedAnswer).toEqual({ text: 'Network error (TypeError)', isError: true })
    expect(failedAnswer.text).not.toContain('spod-secret-token')
  })

  test('spod_write sends JSON bodies and supports bodyless POST/DELETE', async () => {
    const { client, requests } = await connect(STAGING_CONFIG, () =>
      Response.json({ id: '99' }, { status: 201 }),
    )
    const created = await call(client, 'spod_write', {
      method: 'POST',
      path: '/subscriptions',
      body: { eventType: 'Shipment.sent', url: 'https://shop.example/hook' },
    })
    expect(created.isError).toBe(false)
    expect(created.text).toContain('HTTP 201')
    expect(requests[0]).toEqual({
      url: 'https://rest.spod-staging.com/subscriptions',
      method: 'POST',
      headers: {
        'x-spod-access-token': 'spod-secret-token',
        accept: 'application/json',
        'content-type': 'application/json',
      },
      body: JSON.stringify({ eventType: 'Shipment.sent', url: 'https://shop.example/hook' }),
      redirect: 'manual',
    })

    await call(client, 'spod_write', { method: 'DELETE', path: '/subscriptions/99' })
    expect(requests[1]?.method).toBe('DELETE')
    expect(requests[1]?.headers['content-type']).toBeUndefined()
    expect(requests[1]?.body).toBeNull()
  })

  test('paces consecutive requests by 1050 ms', async () => {
    const { client, waits, advanceClock } = await connect(STAGING_CONFIG)
    await call(client, 'spod_read', { path: '/authentication' })
    expect(waits).toEqual([])

    advanceClock(400)
    await call(client, 'spod_read', { path: '/articles' })
    expect(waits).toEqual([650])

    advanceClock(1200)
    await call(client, 'spod_read', { path: '/subscriptions' })
    expect(waits).toEqual([650])
  })
})
