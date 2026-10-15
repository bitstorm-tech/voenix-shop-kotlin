import { afterAll, beforeAll, describe, expect, test } from 'bun:test'
import { mkdir, mkdtemp, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createApplication } from '@/composition'
import { composedApplication, composedSettings, type ComposedApplication } from '@/test-fixtures'
import { testDatabase } from '@test-support/database'
import { guestHeaders, jsonOf } from '@test-support/http'

/**
 * The composition root against the test database: every module's routes are mounted (one smoke
 * request each), the workers start and stop cleanly, and the built frontend is served with the
 * SPA fallback and the cache rules when a dist directory is configured.
 */

let composed: ComposedApplication | undefined
// Registered before `testDatabase()` so the pool closes before the database is dropped.
afterAll(async () => {
  await composed?.stop()
})
const database = testDatabase()
beforeAll(async () => {
  composed = await composedApplication(database.settings)
  composed.start()
})

const request = (path: string, init?: RequestInit) => {
  if (composed === undefined) throw new Error('The application exists only inside a test')
  return composed.app.request(path, init)
}

describe('createApplication', () => {
  test('every module answers on its routes', async () => {
    const countries = await request('/api/countries')
    expect(countries.status).toBe(200)
    expect(await jsonOf(countries)).toEqual(
      expect.arrayContaining([expect.objectContaining({ countryCode: 'DE' })]),
    )
    expect((await request('/api/antiforgery/token')).status).toBe(200)
    expect((await request('/api/articles/mugs')).status).toBe(200)
    expect((await request('/api/prompts')).status).toBe(200)
    expect((await request('/api/cart')).status).toBe(200)
    expect((await request('/api/orders')).status).toBe(200)
    expect((await request('/api/magic-coins/balance')).status).toBe(200)
    expect((await request('/api/order-lookup/nobody')).status).toBe(404)
    expect((await request('/api/images/public/100/nobody.png')).status).toBe(404)
    expect((await request('/api/images/guest/100/1')).status).toBe(404)
    for (const path of [
      '/api/admin/prices',
      '/api/admin/suppliers',
      '/api/admin/promotions',
      '/api/auth/me',
      '/api/supplier/me',
      '/api/admin/production/destinations',
      '/api/admin/orders/1/production-pdfs',
    ]) {
      expect((await request(path)).status).toBe(401)
    }
    // The checkout is mounted and guest-capable: a mutation without a CSRF pair is refused.
    expect((await request('/api/checkout', { method: 'POST' })).status).toBe(400)
    const retry = await request('/api/checkout/orders/1/payment', {
      method: 'POST',
      headers: await guestHeaders(),
    })
    expect(retry.status).toBe(404)
    // Without a dist directory nothing but the API answers.
    expect((await request('/')).status).toBe(404)
  })

  test('stop ends the workers and the pool, and a second application starts on the same database', async () => {
    const root = await mkdtemp(join(tmpdir(), 'voenix-second-'))
    const second = await createApplication(composedSettings(database.settings, root), {
      fetch: () => Promise.reject(new Error('The composed tests reach no network')),
    })
    second.start()
    expect((await second.app.request('/api/countries')).status).toBe(200)
    await second.stop()
    // The pool is gone: the route's query fails and the app's `onError` answers it.
    expect((await second.app.request('/api/countries')).status).toBe(500)
  })

  test('a dist directory without an index page refuses to start', async () => {
    const root = await mkdtemp(join(tmpdir(), 'voenix-nodist-'))
    const failure = await createApplication(
      composedSettings(database.settings, root, { FRONTEND_DIST_PATH: join(root, 'dist') }),
    ).then(
      () => 'resolved',
      (error: unknown) => (error instanceof Error ? error.message : String(error)),
    )
    expect(failure).toContain('there is no index.html in it')
  })
})

describe('the built frontend', () => {
  let frontend: Awaited<ReturnType<typeof createApplication>> | undefined
  afterAll(async () => {
    await frontend?.stop()
  })
  beforeAll(async () => {
    const root = await mkdtemp(join(tmpdir(), 'voenix-dist-'))
    const dist = join(root, 'dist')
    await mkdir(join(dist, 'assets'), { recursive: true })
    await writeFile(join(dist, 'index.html'), '<!doctype html>frontend')
    await writeFile(join(dist, 'sw.js'), 'self.skipWaiting()')
    await writeFile(join(dist, 'assets', 'app-abc123.js'), "console.log('frontend')")
    await writeFile(join(dist, 'assets', 'app-abc123.css'), 'body{}')
    frontend = await createApplication(
      composedSettings(database.settings, root, { FRONTEND_DIST_PATH: dist }),
    )
  })
  const page = (path: string, init?: RequestInit) => {
    if (frontend === undefined) throw new Error('The application exists only inside a test')
    return frontend.app.request(path, init)
  }

  test('the index page is served at the root and never cached', async () => {
    const response = await page('/')
    expect(response.status).toBe(200)
    expect(await response.text()).toBe('<!doctype html>frontend')
    expect(response.headers.get('Cache-Control')).toBe('no-cache, no-store')
    expect(response.headers.get('Content-Type')).toContain('text/html')
  })

  test('a frontend router URL falls back to the index page', async () => {
    const response = await page('/admin/orders')
    expect(response.status).toBe(200)
    expect(await response.text()).toBe('<!doctype html>frontend')
    expect(response.headers.get('Cache-Control')).toBe('no-cache, no-store')
    expect((await page('/admin/orders', { method: 'HEAD' })).status).toBe(200)
  })

  test('content-hashed assets are cached for a year, the service worker never', async () => {
    for (const path of ['/assets/app-abc123.js', '/assets/app-abc123.css']) {
      const response = await page(path)
      expect(response.status).toBe(200)
      expect(response.headers.get('Cache-Control')).toBe('max-age=31536000, public, immutable')
    }
    const worker = await page('/sw.js')
    expect(await worker.text()).toBe('self.skipWaiting()')
    expect(worker.headers.get('Cache-Control')).toBe('no-cache, no-store')
  })

  test('the API is not swallowed by the fallback', async () => {
    const countries = await page('/api/countries')
    expect(countries.status).toBe(200)
    expect(countries.headers.get('Content-Type')).toContain('application/json')
    expect((await page('/api/nowhere')).status).toBe(404)
  })
})
