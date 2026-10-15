import { describe, expect, test } from 'bun:test'
import type { Settings } from '@/platform/settings'
import { renderPasswordReset } from '../templates/password-reset'
import { createSweegoDelivery, type Fetch } from './sweego'

const settings = {
  enabled: true,
  pollIntervalMinutes: 5,
  apiKey: 'secret-key',
  fromEmail: 'mail@voenix.shop',
  fromName: 'Voenix Shop',
} satisfies Settings['email']

const jsonBody = (init: RequestInit | undefined): unknown =>
  typeof init?.body === 'string' ? JSON.parse(init.body) : null

const email = renderPasswordReset({
  recipient: 'kunde@example.com',
  resetUrl: 'https://shop.example/reset-password?token=t',
})

/** The adapter on a stub that records every request and answers with `respond`, or fails with it. */
function sweego(respond: () => Response | Error) {
  const requests: { url: string; init: RequestInit }[] = []
  const fetch: Fetch = (url, init) => {
    requests.push({ url, init })
    const answer = respond()
    return answer instanceof Error ? Promise.reject(answer) : Promise.resolve(answer)
  }
  return { delivery: createSweegoDelivery(settings, fetch), requests }
}

describe('Sweego delivery', () => {
  test('posts the transactional contract and accepts a 2xx answer whatever its body', async () => {
    const { delivery, requests } = sweego(() => new Response('not-json', { status: 202 }))

    expect(await delivery.deliver(email, 'voenix-email-42')).toEqual({ kind: 'accepted' })

    expect(requests).toHaveLength(1)
    const [request] = requests
    expect(request?.url).toBe('https://api.sweego.io/send')
    expect(request?.init.method).toBe('POST')
    expect(request?.init.redirect).toBe('manual')
    expect(request?.init.signal).toBeInstanceOf(AbortSignal)
    expect(request?.init.headers).toEqual({
      'Api-Key': 'secret-key',
      'Content-Type': 'application/json',
    })
    expect(jsonBody(request?.init)).toEqual({
      channel: 'email',
      provider: 'sweego',
      recipients: [{ email: 'kunde@example.com', name: '' }],
      from: { email: 'mail@voenix.shop', name: 'Voenix Shop' },
      subject: 'Setze dein Passwort zurück',
      'message-html': email.html,
      'message-txt': email.text,
      'campaign-type': 'transac',
      'campaign-id': 'voenix-email-42',
    })
  })

  test('omits an absent campaign id', async () => {
    const { delivery, requests } = sweego(() => new Response(null, { status: 200 }))
    await delivery.deliver(email, null)
    expect(jsonBody(requests[0]?.init)).not.toHaveProperty('campaign-id')
  })

  test('reports a refusal or a redirect by its status, never by the body', async () => {
    const refused = sweego(() => new Response('kunde@example.com token=secret', { status: 503 }))
    expect(await refused.delivery.deliver(email, null)).toEqual({
      kind: 'failed',
      code: 'PROVIDER_HTTP_503',
    })

    const redirected = sweego(
      () => new Response(null, { status: 302, headers: { Location: 'https://attacker.test/' } }),
    )
    expect(await redirected.delivery.deliver(email, null)).toEqual({
      kind: 'failed',
      code: 'PROVIDER_HTTP_302',
    })
    expect(redirected.requests).toHaveLength(1)
  })

  test('classifies a timeout and an unreachable provider', async () => {
    const timedOut = sweego(() => new DOMException('The operation timed out.', 'TimeoutError'))
    expect(await timedOut.delivery.deliver(email, null)).toEqual({
      kind: 'failed',
      code: 'REQUEST_TIMEOUT',
    })

    const unreachable = sweego(() => new TypeError('fetch failed'))
    expect(await unreachable.delivery.deliver(email, null)).toEqual({
      kind: 'failed',
      code: 'PROVIDER_UNAVAILABLE',
    })
  })
})
