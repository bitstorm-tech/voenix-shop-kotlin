import { describe, expect, test } from 'bun:test'
import { Hono } from 'hono'
import { legacyRedirectRoutes } from '@/platform/http/legacy-redirects'

function appWithFallback() {
  const app = new Hono()
  app.route('/', legacyRedirectRoutes())
  app.get('*', (c) => c.text(`spa:${c.req.path}`))
  return app
}

describe('legacyRedirectRoutes', () => {
  test('redirects every legacy WordPress URL with 301, with and without trailing slash', async () => {
    const app = appWithFallback()
    const cases: readonly [string, string][] = [
      ['/impressum', '/imprint'],
      ['/impressum/', '/imprint'],
      ['/widerruf', '/returns'],
      ['/widerruf/', '/returns'],
      ['/versand', '/shipping'],
      ['/versand/', '/shipping'],
      ['/zahlung', '/payment'],
      ['/zahlung/', '/payment'],
      ['/faq/', '/faq'],
      ['/ueber-uns', '/about'],
      ['/ueber-uns/', '/about'],
      ['/tassenuebersicht', '/'],
      ['/tassenuebersicht/', '/'],
      ['/firmenkunden', '/about'],
      ['/firmenkunden/', '/about'],
      ['/pdf/AGBs.pdf', '/terms'],
      ['/pdf/AGBs.pdf/', '/terms'],
      ['/pdf/agbs.pdf', '/terms'],
    ]

    for (const [from, to] of cases) {
      const response = await app.request(from)
      expect(response.status).toBe(301)
      expect(response.headers.get('Location')).toBe(to)
    }
  })

  test('preserves query parameters and supports HEAD requests', async () => {
    const app = appWithFallback()
    const get = await app.request('/impressum/?utm_source=google')
    expect(get.status).toBe(301)
    expect(get.headers.get('Location')).toBe('/imprint?utm_source=google')

    const head = await app.request('/widerruf', { method: 'HEAD' })
    expect(head.status).toBe(301)
    expect(head.headers.get('Location')).toBe('/returns')
  })

  test('passes /faq without trailing slash and unrelated paths through without looping', async () => {
    const app = appWithFallback()
    for (const path of ['/faq', '/imprint', '/products', '/']) {
      const response = await app.request(path)
      expect(response.status).toBe(200)
      expect(await response.text()).toBe(`spa:${path}`)
    }
  })
})
