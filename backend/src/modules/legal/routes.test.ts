import { describe, expect, test } from 'bun:test'
import { createLegalModule } from '@/modules/legal'
import { jsonOf, testApp } from '@test-support/http'

const HOUR = 60 * 60 * 1000

/** A stub iubenda: answers each document with its URL tail and a counter, or fails while `down`. */
function iubenda() {
  const state = { calls: 0, down: false }
  const fetch = (url: string) => {
    state.calls += 1
    if (state.down) return Promise.resolve(new Response('unavailable', { status: 503 }))
    const content = `${url.endsWith('/cookie-policy/no-markup') ? 'cookies' : 'privacy'} ${String(state.calls)}`
    return Promise.resolve(Response.json({ success: true, content }))
  }
  return { state, fetch }
}

function setup() {
  const stub = iubenda()
  const clock = { now: 0 }
  const app = testApp(createLegalModule({ fetch: stub.fetch, now: () => clock.now }).routes)
  const read = () => app.request('/api/legal/privacy')
  return { stub, clock, read }
}

describe('GET /api/legal/privacy', () => {
  test('answers both documents and reads iubenda once per hour', async () => {
    const { stub, read } = setup()

    const first = await read()
    expect(first.status).toBe(200)
    expect(await jsonOf(first)).toEqual({ privacyPolicy: 'privacy 1', cookiePolicy: 'cookies 2' })

    await read()
    expect(stub.state.calls).toBe(2)
  })

  test('answers 502 when iubenda fails and there is no copy yet', async () => {
    const { stub, read } = setup()
    stub.state.down = true

    const response = await read()
    expect(response.status).toBe(502)
    expect(await jsonOf(response)).toMatchObject({
      message: 'The privacy policy could not be loaded',
    })
  })

  test('serves the old copy while refreshing, and keeps it when the refresh fails', async () => {
    const { stub, clock, read } = setup()
    await read()

    clock.now = HOUR + 1
    stub.state.down = true
    expect(await jsonOf(await read())).toEqual({
      privacyPolicy: 'privacy 1',
      cookiePolicy: 'cookies 2',
    })
    await Bun.sleep(1)
    expect(await jsonOf(await read())).toEqual({
      privacyPolicy: 'privacy 1',
      cookiePolicy: 'cookies 2',
    })
    await Bun.sleep(1)

    stub.state.down = false
    await read()
    await Bun.sleep(1)
    expect(await jsonOf(await read())).toEqual({
      privacyPolicy: 'privacy 7',
      cookiePolicy: 'cookies 8',
    })
  })
})
