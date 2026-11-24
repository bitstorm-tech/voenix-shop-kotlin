import { describe, expect, test } from 'bun:test'
import { healthRoutes } from '@/platform/http/health'
import { jsonOf, testApp } from '@test-support/http'

describe('healthRoutes', () => {
  test('GET /api/health answers 200 ok without calling the readiness probe', async () => {
    let called = false
    const app = testApp(
      healthRoutes(() => {
        called = true
        return Promise.reject(new Error('database down'))
      }),
    )
    const response = await app.request('/api/health')
    expect(response.status).toBe(200)
    expect(response.headers.get('Cache-Control')).toBe('no-store')
    expect(await jsonOf(response)).toEqual({ status: 'ok' })
    expect(called).toBe(false)
  })

  test('GET /api/ready answers 200 ready when the probe succeeds', async () => {
    const app = testApp(healthRoutes(() => Promise.resolve()))
    const response = await app.request('/api/ready')
    expect(response.status).toBe(200)
    expect(response.headers.get('Cache-Control')).toBe('no-store')
    expect(await jsonOf(response)).toEqual({ status: 'ready' })
  })

  test('GET /api/ready answers 503 unavailable when the probe rejects', async () => {
    const app = testApp(healthRoutes(() => Promise.reject(new Error('connection refused'))))
    const response = await app.request('/api/ready')
    expect(response.status).toBe(503)
    expect(response.headers.get('Cache-Control')).toBe('no-store')
    expect(await jsonOf(response)).toEqual({ status: 'unavailable' })
  })

  test('GET /api/ready answers 503 unavailable when the probe exceeds the timeout', async () => {
    const app = testApp(
      healthRoutes(
        () =>
          new Promise<void>(() => {
            // Intentionally never settles to exercise the timeout path.
          }),
        { timeoutMs: 25 },
      ),
    )
    const response = await app.request('/api/ready')
    expect(response.status).toBe(503)
    expect(await jsonOf(response)).toEqual({ status: 'unavailable' })
  })
})
