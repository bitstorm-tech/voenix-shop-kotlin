import { afterEach, describe, expect, spyOn, test } from 'bun:test'
import { Client, SdkHttpError, StreamableHTTPClientTransport } from '@modelcontextprotocol/client'
import { Hono } from 'hono'
import { createCountryModule } from '@/modules/country'
import { createMcpModule } from '@/modules/mcp'
import { requireAdmin, requireAdminOrToken } from '@/platform/auth/auth'
import { httpLog } from '@/platform/http/app'
import { formBody } from '@/platform/http/respond'
import { testDatabase } from '@test-support/database'
import { revokeToken, testApp, tokenHeaders } from '@test-support/http'
import { mcpLog } from './routes'

const database = testDatabase()
const FRONTEND = 'http://localhost:5173'

/**
 * The country module, a probe sub-app below `/api/admin/` that opts in (a multipart route, a
 * binary and a text answer), routes behind `requireAdmin` (a sub-app and an inline one, each also
 * below the open sub-app), an unprotected one, and the MCP module over all of them.
 */
function mcpApp(): Hono {
  const open = new Hono()
  open.use(requireAdminOrToken)
  open.post('/upload', formBody, (c) => c.json({ uploaded: true }, 201))
  open.get('/pdf', (c) =>
    c.body(new Uint8Array([37, 80, 68, 70]), 200, { 'Content-Type': 'application/pdf' }),
  )
  open.get('/note', (c) => c.text('a plain note'))
  open.get('/inline-locked', requireAdmin, (c) => c.json(['locked']))
  const locked = new Hono()
  locked.use(requireAdmin)
  locked.get('/', (c) => c.json(['locked']))
  const probe = new Hono()
  probe.route('/api/admin/probe', open)
  probe.route('/api/admin/locked', locked)
  probe.route('/api/admin/probe/locked', locked)
  probe.get('/api/admin/locked-inline', requireAdmin, (c) => c.json(['locked']))
  probe.get('/api/admin/unprotected', (c) => c.json(['nobody checks']))
  const app = testApp(createCountryModule({ db: database.db }).routes, probe)
  app.route('/', createMcpModule({ app, frontendBaseUrl: FRONTEND }).routes)
  return app
}

const clients: Client[] = []
afterEach(async () => {
  await Promise.all(clients.splice(0).map((client) => client.close()))
})

/** An SDK client on `/api/mcp` of `app`, with the bearer header on every request. */
async function connect(app: Hono, headers: Record<string, string> = tokenHeaders()) {
  const client = new Client({ name: 'voenix-test', version: '1.0.0' })
  const transport = new StreamableHTTPClientTransport(new URL('http://localhost/api/mcp'), {
    fetch: async (url, init) => app.request(url, init),
    requestInit: { headers },
  })
  await client.connect(transport)
  clients.push(client)
  return client
}

/** The one text block of a tool result, and whether it is an error. */
async function call(client: Client, name: string, args: Record<string, unknown>) {
  const result = await client.callTool({ name, arguments: args })
  const [block] = result.content as { type: string; text: string }[]
  return { text: block?.text ?? '', isError: result.isError === true }
}

const rawPost = (app: Hono, headers: Record<string, string>, body: unknown) =>
  app.request('/api/mcp', {
    method: 'POST',
    headers: {
      ...headers,
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream',
    },
    body: JSON.stringify(body),
  })

describe('the MCP endpoint', () => {
  test('an admin token lists exactly the three tools with their hints', async () => {
    const client = await connect(mcpApp())
    const { tools } = await client.listTools()
    expect(
      tools
        .map((tool) => ({ name: tool.name, annotations: tool.annotations }))
        .sort((a, b) => a.name.localeCompare(b.name)),
    ).toEqual([
      { name: 'admin_endpoints', annotations: { readOnlyHint: true } },
      { name: 'admin_read', annotations: { readOnlyHint: true } },
      { name: 'admin_write', annotations: { destructiveHint: true } },
    ])
    expect(client.getInstructions()).toContain('Call admin_endpoints first')
  })

  test('refuses a missing token, a non-admin token, a foreign origin, and a GET', async () => {
    const app = mcpApp()
    const ping = { jsonrpc: '2.0', id: 1, method: 'ping' }
    expect((await rawPost(app, {}, ping)).status).toBe(401)
    expect(
      (await rawPost(app, tokenHeaders({ userId: 12, roles: ['CUSTOMER'] }), ping)).status,
    ).toBe(403)
    const foreign = await rawPost(app, { ...tokenHeaders(), Origin: 'https://evil.example' }, ping)
    expect(foreign.status).toBe(403)
    expect((await rawPost(app, { ...tokenHeaders(), Origin: FRONTEND }, ping)).status).toBe(200)
    const get = await app.request('/api/mcp', {
      headers: { ...tokenHeaders(), accept: 'text/event-stream' },
    })
    expect(get.status).toBe(405)
  })

  test('serves a client of the 2025-06-18 revision without a session', async () => {
    const app = mcpApp()
    const headers = tokenHeaders()
    const initialize = await rawPost(app, headers, {
      jsonrpc: '2.0',
      id: 1,
      method: 'initialize',
      params: {
        protocolVersion: '2025-06-18',
        capabilities: {},
        clientInfo: { name: 'legacy', version: '1.0.0' },
      },
    })
    expect(initialize.status).toBe(200)
    expect(await initialize.text()).toContain('"protocolVersion":"2025-06-18"')
    const list = await rawPost(
      app,
      { ...headers, 'mcp-protocol-version': '2025-06-18' },
      { jsonrpc: '2.0', id: 2, method: 'tools/list' },
    )
    expect(list.status).toBe(200)
    expect(await list.text()).toContain('"admin_write"')
  })
})

describe('admin_endpoints', () => {
  test('lists every open endpoint once with its body kind, and no locked or unprotected one', async () => {
    const { text, isError } = await call(await connect(mcpApp()), 'admin_endpoints', {})
    expect(isError).toBe(false)
    expect(text.split('\n')).toEqual([
      'GET /api/admin/countries',
      'POST /api/admin/countries json',
      'DELETE /api/admin/countries/:id',
      'GET /api/admin/countries/:id',
      'PUT /api/admin/countries/:id json',
      'GET /api/admin/probe/note',
      'GET /api/admin/probe/pdf',
      'POST /api/admin/probe/upload multipart',
    ])
  })

  test('with a path, answers the JSON Schema of each JSON body', async () => {
    const { text } = await call(await connect(mcpApp()), 'admin_endpoints', {
      path: '/api/admin/countries',
    })
    const [, schemaText] = text.split('\n\n')[1]?.split(/\n(.*)/s) ?? []
    expect(text.split('\n\n')[1]).toStartWith('POST /api/admin/countries json\n')
    expect(JSON.parse(schemaText ?? '')).toMatchObject({
      type: 'object',
      properties: { name: { type: 'string' }, countryCode: { type: 'string' } },
      additionalProperties: false,
    })
    expect(text).not.toContain('/api/admin/probe')
  })
})

describe('admin_read and admin_write', () => {
  test('read, create, change, and delete through the real routes', async () => {
    const client = await connect(mcpApp())
    const listed = await call(client, 'admin_read', { path: '/api/admin/countries' })
    expect(listed.isError).toBe(false)
    expect(listed.text).toStartWith('HTTP 200\n[')
    expect(listed.text).toContain('"countryCode":"DE"')

    const created = await call(client, 'admin_write', {
      method: 'POST',
      path: '/api/admin/countries',
      body: { name: 'Mcpland', countryCode: 'MQ' },
    })
    expect(created.text).toStartWith('HTTP 201\n')
    const { id } = JSON.parse(created.text.slice('HTTP 201\n'.length)) as { id: number }
    const changed = await call(client, 'admin_write', {
      method: 'PUT',
      path: `/api/admin/countries/${String(id)}`,
      body: { name: 'Mcp Land', countryCode: 'MQ' },
    })
    expect(changed.text).toContain('"name":"Mcp Land"')
    const deleted = await call(client, 'admin_write', {
      method: 'DELETE',
      path: `/api/admin/countries/${String(id)}`,
    })
    expect(deleted).toEqual({ text: 'HTTP 204', isError: false })
    const gone = await call(client, 'admin_read', { path: `/api/admin/countries/${String(id)}` })
    expect(gone.isError).toBe(true)
    expect(gone.text).toStartWith('HTTP 404\n')
  })

  test('a body the route refuses answers its field errors as an error', async () => {
    const refused = await call(await connect(mcpApp()), 'admin_write', {
      method: 'POST',
      path: '/api/admin/countries',
      body: { name: '', countryCode: 'M1', extra: true },
    })
    expect(refused.isError).toBe(true)
    expect(refused.text).toStartWith('HTTP 400\n')
    expect(JSON.parse(refused.text.slice('HTTP 400\n'.length))).toEqual({
      message: 'Validation failed',
      errors: {
        name: ['Name is required'],
        countryCode: ['Country code must contain only letters'],
        extra: ['Unknown property'],
      },
    })
  })

  test('a locked route answers 401 from the route itself', async () => {
    const client = await connect(mcpApp())
    for (const path of ['/api/admin/locked', '/api/admin/locked-inline']) {
      const locked = await call(client, 'admin_read', { path })
      expect(locked.isError).toBe(true)
      expect(locked.text).toStartWith('HTTP 401\n')
    }
  })

  test('a path outside /api/admin/ is refused and nothing is dispatched', async () => {
    const client = await connect(mcpApp())
    const dispatched = spyOn(mcpLog, 'info')
    try {
      for (const path of [
        '/api/auth/me',
        '/api/admin/../auth/me',
        '/api/admin/%2e%2e/auth/me',
        '//other.host/api/admin/countries',
        'https://other.host/api/admin/countries',
        '/api/admin',
      ]) {
        for (const [name, args] of [
          ['admin_read', { path }],
          ['admin_write', { method: 'DELETE', path }],
        ] as const) {
          const refused = await call(client, name, args)
          expect(refused.isError).toBe(true)
          expect(refused.text).toStartWith('Refused:')
        }
      }
      expect(dispatched).not.toHaveBeenCalled()
    } finally {
      dispatched.mockRestore()
    }
  })

  test('a binary answer is described, a text answer returned', async () => {
    const client = await connect(mcpApp())
    expect(await call(client, 'admin_read', { path: '/api/admin/probe/pdf' })).toEqual({
      text: 'HTTP 200\napplication/pdf, 4 bytes: binary responses are not returned through MCP',
      isError: false,
    })
    expect((await call(client, 'admin_read', { path: '/api/admin/probe/note?x=1' })).text).toBe(
      'HTTP 200\na plain note',
    )
  })

  test('a token revoked between two calls fails the next one with 401', async () => {
    const app = mcpApp()
    const headers = tokenHeaders()
    const client = await connect(app, headers)
    expect((await call(client, 'admin_read', { path: '/api/admin/countries' })).isError).toBe(false)
    revokeToken(headers)
    const failure: unknown = await call(client, 'admin_read', {
      path: '/api/admin/countries',
    }).catch((error: unknown) => error)
    expect(failure).toBeInstanceOf(SdkHttpError)
    expect((failure as SdkHttpError).status).toBe(401)
  })

  test('each dispatched call logs one line without the token', async () => {
    const lines = spyOn(mcpLog, 'info')
    const requests = spyOn(httpLog, 'info')
    try {
      const headers = tokenHeaders({ userId: 41, roles: ['ADMIN'] })
      const client = await connect(mcpApp(), headers)
      await call(client, 'admin_read', { path: '/api/admin/countries?sort=name' })
      expect(lines.mock.calls).toEqual([
        [
          { userId: 41, method: 'GET', path: '/api/admin/countries', status: 200 },
          'MCP admin call',
        ],
      ])
      const token = (headers.Authorization ?? '').replace('Bearer ', '')
      expect(JSON.stringify([lines.mock.calls, requests.mock.calls])).not.toContain(token)
    } finally {
      lines.mockRestore()
      requests.mockRestore()
    }
  })
})
