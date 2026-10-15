import { describe, expect, test } from 'bun:test'
import { Hono } from 'hono'
import { createClientIpRateLimiter } from '@/platform/ratelimit/client-ip-rate-limiter'
import { jsonOf, testApp } from '@test-support/http'

const IP = '203.0.113.7'
const OTHER_IP = '203.0.113.8'
const THIRD_IP = '203.0.113.9'
const FOURTH_IP = '203.0.113.10'
const start = Date.parse('2026-08-04T10:00:00Z')
const seconds = (count: number) => start + count * 1000

const limiter = (
  options: {
    limit?: number
    windowSeconds?: number
    maxTrackedIps?: number
    trustForwardedFor?: boolean
  } = {},
) => createClientIpRateLimiter({ trustForwardedFor: false, peerAddress: () => IP, ...options })

describe('the counting', () => {
  test('the requests up to the limit pass and the next one waits for the end of the window', () => {
    const { retryAfterSeconds } = limiter({ limit: 3 })
    expect(retryAfterSeconds(IP, seconds(0))).toBeNull()
    expect(retryAfterSeconds(IP, seconds(1))).toBeNull()
    expect(retryAfterSeconds(IP, seconds(2))).toBeNull()
    expect(retryAfterSeconds(IP, seconds(3))).toBe(3597)
  })

  test('a refused request does not push the end of the window further away', () => {
    const { retryAfterSeconds } = limiter({ limit: 1 })
    expect(retryAfterSeconds(IP, seconds(0))).toBeNull()
    expect(retryAfterSeconds(IP, seconds(10))).toBe(3590)
    expect(retryAfterSeconds(IP, seconds(20))).toBe(3580)
  })

  test('the next window starts fresh once the first one is over', () => {
    const { retryAfterSeconds } = limiter({ limit: 1 })
    expect(retryAfterSeconds(IP, seconds(0))).toBeNull()
    expect(retryAfterSeconds(IP, seconds(0))).toBe(3600)
    expect(retryAfterSeconds(IP, seconds(3600))).toBeNull()
  })

  test('every IP is counted on its own', () => {
    const { retryAfterSeconds } = limiter({ limit: 1 })
    expect(retryAfterSeconds(IP, seconds(0))).toBeNull()
    expect(retryAfterSeconds(OTHER_IP, seconds(0))).toBeNull()
    expect(retryAfterSeconds(IP, seconds(0))).toBe(3600)
  })

  test('the wait is never below one second', () => {
    const { retryAfterSeconds } = limiter({ limit: 1, windowSeconds: 2 })
    expect(retryAfterSeconds(IP, seconds(0))).toBeNull()
    expect(retryAfterSeconds(IP, start + 1500)).toBe(1)
  })

  test('an address the full map has no room for is refused, a tracked one keeps its allowance', () => {
    const { retryAfterSeconds } = limiter({ limit: 3, maxTrackedIps: 2 })
    expect(retryAfterSeconds(IP, seconds(0))).toBeNull()
    expect(retryAfterSeconds(OTHER_IP, seconds(60))).toBeNull()
    expect(retryAfterSeconds(THIRD_IP, seconds(120))).toBe(3600)
    expect(retryAfterSeconds(IP, seconds(130))).toBeNull()
  })

  test('a full map makes room again once a tracked window has ended', () => {
    const { retryAfterSeconds } = limiter({ limit: 3, maxTrackedIps: 2 })
    expect(retryAfterSeconds(IP, seconds(0))).toBeNull()
    expect(retryAfterSeconds(OTHER_IP, seconds(600))).toBeNull()
    expect(retryAfterSeconds(THIRD_IP, seconds(3660))).toBeNull()
    expect(retryAfterSeconds(FOURTH_IP, seconds(3900))).toBe(3600)
    expect(retryAfterSeconds(FOURTH_IP, seconds(4260))).toBeNull()
  })
})

describe('the middleware', () => {
  const limitedApp = (options: { limit?: number; trustForwardedFor?: boolean } = {}) => {
    const routes = new Hono()
    let handled = 0
    routes.get('/limited', limiter({ ...options }).middleware, (c) => {
      handled += 1
      return c.text('generated')
    })
    routes.get('/unlimited', (c) => c.text('fine'))
    return { app: testApp(routes), handled: () => handled }
  }

  test('the 21st request of an IP is refused with 429 and Retry-After before the handler runs', async () => {
    const { app, handled } = limitedApp()
    for (let request = 1; request <= 20; request += 1) {
      expect((await app.request('/limited')).status).toBe(200)
    }
    const refused = await app.request('/limited')
    expect(refused.status).toBe(429)
    expect(await jsonOf(refused)).toEqual({ message: 'Too many requests', errors: {} })
    const retryAfter = Number(refused.headers.get('Retry-After'))
    expect(retryAfter).toBeGreaterThanOrEqual(1)
    expect(retryAfter).toBeLessThanOrEqual(3600)
    expect(handled()).toBe(20)
    expect((await app.request('/unlimited')).status).toBe(200)
  })

  test('a trusted forwarded header separates the clients behind the proxy, and only its last entry counts', async () => {
    const { app } = limitedApp({ limit: 1, trustForwardedFor: true })
    const get = (forwardedFor: string) =>
      app.request('/limited', { headers: { 'X-Forwarded-For': forwardedFor } })
    expect((await get('198.51.100.1, 203.0.113.20')).status).toBe(200)
    expect((await get('198.51.100.2, 203.0.113.20')).status).toBe(429)
    expect((await get('203.0.113.21')).status).toBe(200)
  })

  test('an untrusted forwarded header is ignored', async () => {
    const { app } = limitedApp({ limit: 1 })
    const get = (forwardedFor: string) =>
      app.request('/limited', { headers: { 'X-Forwarded-For': forwardedFor } })
    expect((await get('203.0.113.20')).status).toBe(200)
    expect((await get('203.0.113.21')).status).toBe(429)
  })
})
