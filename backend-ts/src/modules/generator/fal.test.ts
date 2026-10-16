import { describe, expect, test } from 'bun:test'
import pino from 'pino'
import { createFalImageGenerator, FAL_EDIT_URL } from './fal'
import type { RawImage } from './image-generator'
import { MAX_IMAGE_BYTES } from './upload'

/**
 * What the adapter promises: exactly one request shape goes to fal.ai, the result is fetched from
 * the URL it names without the key, and every disappointment is an absent image with nothing the
 * provider wrote in the log. Every test drives a stubbed fetch; none reaches the real provider.
 */

const API_KEY = 'secret-key-that-must-never-be-logged'
/** A marker no log line may ever contain: it stands in for whatever the provider writes. */
const PROVIDER_BODY = 'PROVIDER-SECRET-ECHO'
const RESULT_URL = 'https://cdn.example.com/result.png'
const GENERATED = new Uint8Array([1, 2, 3, 4, 5])
const PROMPT = 'Ein Mops im Weltall'

const upload = (): RawImage => ({
  bytes: new Uint8Array(new TextEncoder().encode('hello')),
  contentType: 'image/png',
})
const json = (body: string) =>
  new Response(body, { headers: { 'Content-Type': 'application/json' } })
const generation = (url = RESULT_URL, contentType: string | null = 'image/png') =>
  json(
    JSON.stringify({
      images: [contentType === null ? { url } : { url, content_type: contentType }],
      seed: 42,
    }),
  )

/** The adapter on a fetch that answers the generation call and the download separately. */
function harness(
  answerGeneration: () => Response | Promise<Response>,
  answerDownload: () => Response | Promise<Response> = () => new Response(GENERATED),
) {
  const requests: { url: string; init: RequestInit }[] = []
  const logged: string[] = []
  const generate = createFalImageGenerator({
    apiKey: API_KEY,
    fetch: async (url, init) => {
      requests.push({ url, init })
      return url === FAL_EDIT_URL ? answerGeneration() : answerDownload()
    },
    logger: pino({ level: 'trace' }, { write: (line: string) => logged.push(line) }),
  })
  return { generate, requests, logged }
}

describe('the fal.ai adapter', () => {
  test('sends the exact fal contract and answers the downloaded image', async () => {
    const { generate, requests, logged } = harness(() => generation())
    for (const ratio of ['16:9', '1:1'] as const) {
      expect(await generate(upload(), PROMPT, ratio)).toEqual({
        bytes: GENERATED,
        contentType: 'image/png',
      })
    }
    const [paid, download] = requests
    const bodyOf = (index: number): unknown => {
      const body = requests[index]?.init.body
      return typeof body === 'string' ? JSON.parse(body) : body
    }
    expect(paid?.url).toBe('https://fal.run/fal-ai/nano-banana-2/edit')
    expect(paid?.init.method).toBe('POST')
    expect(paid?.init.redirect).toBe('manual')
    expect(paid?.init.headers).toEqual({
      Authorization: `Key ${API_KEY}`,
      'Content-Type': 'application/json',
    })
    expect(bodyOf(0)).toEqual({
      image_urls: ['data:image/png;base64,aGVsbG8='],
      prompt: PROMPT,
      num_images: 1,
      aspect_ratio: '16:9',
    })
    expect(bodyOf(2)).toMatchObject({ aspect_ratio: '1:1' })
    expect(download?.url).toBe(RESULT_URL)
    expect(download?.init.method).toBe('GET')
    expect(download?.init.headers).toBeUndefined()
    expect(download?.init.redirect).toBeUndefined()
    expect(logged).toEqual([])
  })

  test('every way the generation call disappoints is an absent image, logged without the provider text', async () => {
    const answers: (() => Response | Promise<Response>)[] = [
      () => new Response(PROVIDER_BODY, { status: 429 }),
      () => new Response(PROVIDER_BODY, { status: 302, headers: { Location: RESULT_URL } }),
      () => json('{"images":[]}'),
      () => json('{}'),
      () => json(`{ ${PROVIDER_BODY} is not json`),
      () => json(`{"images":[{"url":42,"note":"${PROVIDER_BODY}"}]}`),
      () => new Response(new Uint8Array(MAX_IMAGE_BYTES + 1)),
      () => Promise.reject(new TypeError(`connection reset ${PROVIDER_BODY}`)),
      () => Promise.reject(new DOMException(`timed out ${PROVIDER_BODY}`, 'TimeoutError')),
    ]
    for (const answer of answers) {
      const { generate, requests, logged } = harness(answer)
      expect(await generate(upload(), PROMPT, '16:9')).toBeNull()
      expect(requests).toHaveLength(1)
      expect(logged).toHaveLength(1)
      expect(logged.join('\n')).not.toContain(PROVIDER_BODY)
      expect(logged.join('\n')).not.toContain(API_KEY)
    }
  })

  test('an unreadable answer is logged by its error class only', async () => {
    const { generate, logged } = harness(() => json(`{ ${PROVIDER_BODY}`))
    expect(await generate(upload(), PROMPT, '16:9')).toBeNull()
    expect(logged).toHaveLength(1)
    expect(logged[0]).toContain('could not be read (SyntaxError)')
    expect(logged[0]).not.toContain(PROVIDER_BODY)
    const refused = harness(() => new Response(PROVIDER_BODY, { status: 500 }))
    expect(await refused.generate(upload(), PROMPT, '16:9')).toBeNull()
    expect(refused.logged[0]).toContain('fal.ai refused a generation with status 500')
  })

  test('a result URL that is not https is never fetched', async () => {
    const { generate, requests } = harness(() => generation('http://cdn.example.com/result.png'))
    expect(await generate(upload(), PROMPT, '16:9')).toBeNull()
    expect(requests).toHaveLength(1)
  })

  test('a failed, oversized, or unreachable download is an absent image', async () => {
    for (const answer of [
      () => new Response(PROVIDER_BODY, { status: 404 }),
      () => new Response(new Uint8Array(MAX_IMAGE_BYTES + 1)),
      () => Promise.reject(new TypeError('connection reset')),
    ]) {
      const { generate, logged } = harness(() => generation(), answer)
      expect(await generate(upload(), PROMPT, '16:9')).toBeNull()
      expect(logged.join('\n')).not.toContain(PROVIDER_BODY)
    }
  })

  test('a result type this shop does not serve, or none, becomes JPEG', async () => {
    for (const reported of ['application/octet-stream', null]) {
      const { generate } = harness(() => generation(RESULT_URL, reported))
      expect((await generate(upload(), PROMPT, '1:1'))?.contentType).toBe('image/jpeg')
    }
    const { generate } = harness(() => generation(RESULT_URL, ' IMAGE/WEBP '))
    expect((await generate(upload(), PROMPT, '1:1'))?.contentType).toBe('image/webp')
  })
})
