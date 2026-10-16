import { describe, expect, test } from 'bun:test'
import { Hono } from 'hono'
import { z } from 'zod'
import { MAX_REQUEST_BODY_BYTES } from '@/platform/http/app'
import { formBody, integerParam, jsonBody, respondResult } from '@/platform/http/respond'
import { conflict, invalid, notFound, ok } from '@/platform/operation'
import { jsonOf, testApp } from '@test-support/http'

const widgetInput = z.strictObject({
  label: z.string({ error: 'Label is required' }).min(1, 'Label is required'),
  size: z.strictObject({ width: z.number({ error: 'Width is required' }) }).optional(),
})
const WIDGET_RESPONSES = { notFound: 'Widget not found', conflict: 'Widget already exists' }

function probeApp() {
  const routes = new Hono()
  routes.get('/probe/boom', () => {
    throw new Error('a detail the client must not see')
  })
  routes.post('/probe/widgets', jsonBody(widgetInput), (c) => c.json(c.req.valid('json'), 201))
  routes.get('/probe/widgets/:id', integerParam('id', 'Invalid widget id'), (c) =>
    c.json({ id: c.req.valid('param').id }),
  )
  routes.post('/probe/upload', async (c) => c.text(String((await c.req.arrayBuffer()).byteLength)))
  routes.post('/probe/form', formBody, async (c) =>
    c.json([...(await c.req.formData()).keys()], 201),
  )
  routes.get('/probe/result/:kind', (c) => {
    const kind = c.req.param('kind')
    const result =
      kind === 'ok'
        ? ok({ label: 'green' })
        : kind === 'notFound'
          ? notFound
          : kind === 'conflict'
            ? conflict
            : invalid({ label: ['Label is required'] })
    return respondResult(c, result, WIDGET_RESPONSES)
  })
  routes.get('/probe/unreachable-conflict', (c) =>
    respondResult(c, conflict, { notFound: 'Widget not found', conflict: null }),
  )
  return testApp(routes)
}

const postJson = (app: Hono, path: string, body: string, contentType = 'application/json') =>
  app.request(path, { method: 'POST', headers: { 'content-type': contentType }, body })

describe('the error answer', () => {
  test('an unhandled exception answers 500 without describing itself', async () => {
    const response = await probeApp().request('/probe/boom')
    expect(response.status).toBe(500)
    expect(await jsonOf(response)).toEqual({ message: 'Internal server error', errors: {} })
  })

  test('an unknown path answers 404', async () => {
    expect((await probeApp().request('/probe/nowhere')).status).toBe(404)
  })
})

describe('a JSON body', () => {
  test('a valid body reaches the handler trimmed of nothing but checked', async () => {
    const response = await postJson(probeApp(), '/probe/widgets', '{"label":"green"}')
    expect(response.status).toBe(201)
    expect(await jsonOf(response)).toEqual({ label: 'green' })
  })

  test('a rejected body answers 400 Validation failed with one entry per field', async () => {
    const response = await postJson(probeApp(), '/probe/widgets', '{"label":"","size":{}}')
    expect(response.status).toBe(400)
    expect(await jsonOf(response)).toEqual({
      message: 'Validation failed',
      errors: { label: ['Label is required'], 'size.width': ['Width is required'] },
    })
  })

  test('a missing, null, or non-string field answers its required message', async () => {
    for (const body of ['{}', '{"label":null}', '{"label":5}']) {
      const response = await postJson(probeApp(), '/probe/widgets', body)
      expect(response.status).toBe(400)
      expect(await jsonOf(response)).toEqual({
        message: 'Validation failed',
        errors: { label: ['Label is required'] },
      })
    }
  })

  test('an unknown property is rejected under its own name', async () => {
    const response = await postJson(probeApp(), '/probe/widgets', '{"label":"green","Label":"x"}')
    expect(response.status).toBe(400)
    expect(await jsonOf(response)).toEqual({
      message: 'Validation failed',
      errors: { Label: ['Unknown property'] },
    })
  })

  test('an unknown property named like an inherited one answers 400 under its own name', async () => {
    for (const name of ['constructor', 'toString', '__proto__']) {
      const response = await postJson(probeApp(), '/probe/widgets', `{"label":"green","${name}":1}`)
      expect(response.status).toBe(400)
      const answer = (await jsonOf(response)) as { errors: Record<string, unknown> }
      expect(Object.keys(answer.errors)).toEqual([name])
      expect(Object.getOwnPropertyDescriptor(answer.errors, name)?.value).toEqual([
        'Unknown property',
      ])
    }
  })

  test('a body that is not JSON answers 400 Malformed request body', async () => {
    for (const body of ['', '{"label":', 'null', '[]']) {
      const response = await postJson(probeApp(), '/probe/widgets', body)
      expect(response.status).toBe(400)
      const answer = (await jsonOf(response)) as { message: string }
      expect(['Malformed request body', 'Validation failed']).toContain(answer.message)
      if (body === '') expect(answer.message).toBe('Malformed request body')
      if (body === '{"label":') expect(answer.message).toBe('Malformed request body')
    }
  })

  test('a content type other than application/json answers 415', async () => {
    const app = probeApp()
    for (const contentType of ['text/plain', 'text/json', 'application/vnd.voenix+json']) {
      const response = await postJson(app, '/probe/widgets', '{"label":"green"}', contentType)
      expect(response.status).toBe(415)
      expect(await jsonOf(response)).toEqual({ message: 'Unsupported media type', errors: {} })
    }
    const missing = await app.request('/probe/widgets', {
      method: 'POST',
      body: '{"label":"green"}',
    })
    expect(missing.status).toBe(415)
  })
})

describe('a multipart form body', () => {
  test('a multipart form reaches the handler, which reads the parsed form', async () => {
    const form = new FormData()
    form.append('file', new Blob([new Uint8Array(4)], { type: 'image/png' }), 'a.png')
    form.append('note', 'text')
    const response = await probeApp().request('/probe/form', { method: 'POST', body: form })
    expect(response.status).toBe(201)
    expect(await jsonOf(response)).toEqual(['file', 'note'])
  })

  test('a content type other than multipart/form-data answers 415', async () => {
    const app = probeApp()
    for (const contentType of [
      'application/json',
      'application/x-www-form-urlencoded',
      'text/plain',
    ]) {
      const response = await postJson(app, '/probe/form', '{"file":"x"}', contentType)
      expect(response.status).toBe(415)
      expect(await jsonOf(response)).toEqual({ message: 'Unsupported media type', errors: {} })
    }
    const missing = await app.request('/probe/form', { method: 'POST', body: 'file=x' })
    expect(missing.status).toBe(415)
  })

  test('a body that is not the announced multipart form answers 400 Malformed request body', async () => {
    const response = await postJson(
      probeApp(),
      '/probe/form',
      'not a form',
      'multipart/form-data; boundary=xyz',
    )
    expect(response.status).toBe(400)
    expect(await jsonOf(response)).toEqual({ message: 'Malformed request body', errors: {} })
  })
})

describe('an integer path parameter', () => {
  test('a numeric id reaches the route; zero and negative numbers are left to the operation', async () => {
    const app = probeApp()
    expect(await jsonOf(await app.request('/probe/widgets/42'))).toEqual({ id: 42 })
    expect(await jsonOf(await app.request('/probe/widgets/0'))).toEqual({ id: 0 })
    expect(await jsonOf(await app.request('/probe/widgets/-7'))).toEqual({ id: -7 })
  })

  test('a non-numeric or out-of-range id answers 400 with the route message', async () => {
    const app = probeApp()
    for (const id of ['abc', '99999999999999999999', '1.5']) {
      const response = await app.request(`/probe/widgets/${id}`)
      expect(response.status).toBe(400)
      expect(await jsonOf(response)).toEqual({ message: 'Invalid widget id', errors: {} })
    }
  })
})

describe('respondResult', () => {
  test('maps every variant to its status and body', async () => {
    const app = probeApp()
    const answer = async (kind: string) => {
      const response = await app.request(`/probe/result/${kind}`)
      return { status: response.status, body: await jsonOf(response) }
    }
    expect(await answer('ok')).toEqual({ status: 200, body: { label: 'green' } })
    expect(await answer('notFound')).toEqual({
      status: 404,
      body: { message: 'Widget not found', errors: {} },
    })
    expect(await answer('conflict')).toEqual({
      status: 409,
      body: { message: 'Widget already exists', errors: {} },
    })
    expect(await answer('invalid')).toEqual({
      status: 400,
      body: { message: 'Validation failed', errors: { label: ['Label is required'] } },
    })
  })

  test('a conflict the route declared unreachable is a bug and answers 500', async () => {
    const response = await probeApp().request('/probe/unreachable-conflict')
    expect(response.status).toBe(500)
  })
})

describe('the request body limit', () => {
  const upload = (
    app: Hono,
    body: NonNullable<RequestInit['body']>,
    headers: Record<string, string> = {},
  ) => app.request('/probe/upload', { method: 'POST', body, headers })

  test('a body up to the limit reaches the handler', async () => {
    const response = await upload(probeApp(), new Uint8Array(MAX_REQUEST_BODY_BYTES))
    expect(response.status).toBe(200)
    expect(await response.text()).toBe(String(MAX_REQUEST_BODY_BYTES))
  })

  test('an announced body above the limit is refused before the handler runs', async () => {
    const response = await upload(probeApp(), new Uint8Array(MAX_REQUEST_BODY_BYTES + 1))
    expect(response.status).toBe(413)
    expect(await jsonOf(response)).toEqual({ message: 'Request body too large', errors: {} })
  })

  test('a body that only turns out too large while arriving is refused as well', async () => {
    const chunk = new Uint8Array(1024 * 1024)
    let sent = 0
    const body = new ReadableStream<Uint8Array>({
      pull(controller) {
        if (sent > MAX_REQUEST_BODY_BYTES) {
          controller.close()
          return
        }
        controller.enqueue(chunk)
        sent += chunk.byteLength
      },
    })
    const response = await upload(probeApp(), body, { 'transfer-encoding': 'chunked' })
    expect(response.status).toBe(413)
  })
})
