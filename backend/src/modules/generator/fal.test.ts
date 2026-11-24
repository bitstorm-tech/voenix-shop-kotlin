import { beforeAll, describe, expect, test } from 'bun:test'
import pino from 'pino'
import sharp from 'sharp'
import {
  createFalBackgroundRemover,
  createFalImageGenerator,
  FAL_BACKGROUND_REMOVAL_URL,
  FAL_EDIT_URL,
} from './fal'
import type { RawImage } from './image-generator'
import { MAX_IMAGE_BYTES } from './upload'

/**
 * What the adapter promises: exactly one request shape goes to fal.ai, the result is fetched from
 * the URL it names without the key (with manual HTTPS-only, non-internal redirect validation), the
 * downloaded bytes are verified with sharp, and every disappointment is an absent image with
 * nothing the provider wrote in the log. Every test drives a stubbed fetch; none reaches the real
 * provider.
 */

const API_KEY = 'secret-key-that-must-never-be-logged'
/** A marker no log line may ever contain: it stands in for whatever the provider writes. */
const PROVIDER_BODY = 'PROVIDER-SECRET-ECHO'
const RESULT_URL = 'https://cdn.example.com/result.png'
const PROMPT = 'Ein Mops im Weltall'

let GENERATED_PNG: Uint8Array<ArrayBuffer>
let GENERATED_WEBP: Uint8Array<ArrayBuffer>
let GENERATED_JPEG: Uint8Array<ArrayBuffer>
let GENERATED_GIF: Uint8Array<ArrayBuffer>

beforeAll(async () => {
  const base = () => sharp({ create: { width: 8, height: 8, channels: 3, background: '#114488' } })
  GENERATED_PNG = new Uint8Array(await base().png().toBuffer())
  GENERATED_WEBP = new Uint8Array(await base().webp().toBuffer())
  GENERATED_JPEG = new Uint8Array(await base().jpeg().toBuffer())
  GENERATED_GIF = new Uint8Array(await base().gif().toBuffer())
})

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
  answerDownload: (url: string) => Response | Promise<Response> = () => new Response(GENERATED_PNG),
) {
  const requests: { url: string; init: RequestInit }[] = []
  const logged: string[] = []
  const generate = createFalImageGenerator({
    apiKey: API_KEY,
    fetch: async (url, init) => {
      requests.push({ url, init })
      return url === FAL_EDIT_URL ? answerGeneration() : answerDownload(url)
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
        bytes: GENERATED_PNG,
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
    expect(download?.init.redirect).toBe('manual')
    expect(logged).toEqual([])
  })

  test('follows safe HTTPS redirects up to the hop limit', async () => {
    const { generate, requests } = harness(
      () => generation('https://cdn.example.com/hop-1'),
      (url) =>
        url === 'https://cdn.example.com/hop-1'
          ? new Response(null, {
              status: 302,
              headers: { Location: 'https://cdn2.example.com/final.webp' },
            })
          : new Response(GENERATED_WEBP),
    )
    expect(await generate(upload(), PROMPT, '1:1')).toEqual({
      bytes: GENERATED_WEBP,
      contentType: 'image/webp',
    })
    expect(requests.map((r) => r.url)).toEqual([
      FAL_EDIT_URL,
      'https://cdn.example.com/hop-1',
      'https://cdn2.example.com/final.webp',
    ])
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

  test('an unreadable answer is logged without its message', async () => {
    const { generate, logged } = harness(() => json(`{ ${PROVIDER_BODY}`))
    expect(await generate(upload(), PROMPT, '16:9')).toBeNull()
    expect(logged).toHaveLength(1)
    expect(logged[0]).toContain('could not be read')
    expect(logged[0]).toContain('"class":"SyntaxError"')
    expect(logged[0]).not.toContain(PROVIDER_BODY)
    const refused = harness(() => new Response(PROVIDER_BODY, { status: 500 }))
    expect(await refused.generate(upload(), PROMPT, '16:9')).toBeNull()
    expect(refused.logged[0]).toContain('fal.ai refused a generation with status 500')
  })

  test('a result URL that is not external HTTPS is never fetched', async () => {
    for (const disallowed of [
      'http://cdn.example.com/result.png',
      'https://localhost/result.png',
      'https://127.0.0.1/result.png',
      'https://169.254.169.254/latest/meta-data',
      'https://10.0.0.5/result.png',
      'https://192.168.1.1/result.png',
      'https://[::1]/result.png',
      'https://[::ffff:127.0.0.1]/result.png',
      'https://user:pass@cdn.example.com/result.png',
    ]) {
      const { generate, requests } = harness(() => generation(disallowed))
      expect(await generate(upload(), PROMPT, '16:9')).toBeNull()
      expect(requests).toHaveLength(1)
    }
  })

  test('a redirect to a disallowed URL or exceeding the hop limit is refused', async () => {
    for (const target of [
      'http://cdn.example.com/plain.png',
      'https://169.254.169.254/latest/meta-data',
      'https://127.0.0.1/secret',
      'https://localhost/secret',
      'https://[::1]/secret',
    ]) {
      const { generate, requests } = harness(
        () => generation(RESULT_URL),
        () => new Response(null, { status: 302, headers: { Location: target } }),
      )
      expect(await generate(upload(), PROMPT, '16:9')).toBeNull()
      expect(requests).toHaveLength(2)
    }

    // Redirect loop stops after 5 hops without fetching forever.
    const { generate, requests } = harness(
      () => generation(RESULT_URL),
      () => new Response(null, { status: 302, headers: { Location: RESULT_URL } }),
    )
    expect(await generate(upload(), PROMPT, '16:9')).toBeNull()
    expect(requests).toHaveLength(7) // 1 POST + 6 GET attempts (initial + 5 hops)
  })

  test('a failed, oversized, non-image, or unreachable download is an absent image', async () => {
    for (const answer of [
      () => new Response(PROVIDER_BODY, { status: 404 }),
      () => new Response(new Uint8Array(MAX_IMAGE_BYTES + 1)),
      () =>
        new Response(`<html>${PROVIDER_BODY}</html>`, { headers: { 'Content-Type': 'image/png' } }),
      () => new Response(GENERATED_GIF),
      () => Promise.reject(new TypeError('connection reset')),
    ]) {
      const { generate, logged } = harness(() => generation(), answer)
      expect(await generate(upload(), PROMPT, '16:9')).toBeNull()
      expect(logged.join('\n')).not.toContain(PROVIDER_BODY)
    }
  })

  test('the returned contentType comes from the verified image bytes, ignoring untrusted headers', async () => {
    for (const [bytes, reported, expected] of [
      [GENERATED_JPEG, 'application/octet-stream', 'image/jpeg'],
      [GENERATED_PNG, null, 'image/png'],
      [GENERATED_WEBP, 'image/jpeg', 'image/webp'],
    ] as const) {
      const { generate } = harness(
        () => generation(RESULT_URL, reported),
        () => new Response(bytes),
      )
      expect((await generate(upload(), PROMPT, '1:1'))?.contentType).toBe(expected)
    }
  })
})

describe('the fal.ai background remover', () => {
  const removal = (image: unknown) => json(JSON.stringify({ image, timings: {} }))

  function removerHarness(
    answer: () => Response | Promise<Response>,
    downloadAnswer: () => Response | Promise<Response> = () => new Response(GENERATED_PNG),
  ) {
    const requests: { url: string; init: RequestInit }[] = []
    const logged: string[] = []
    const remove = createFalBackgroundRemover({
      apiKey: API_KEY,
      fetch: async (url, init) => {
        requests.push({ url, init })
        return url === FAL_BACKGROUND_REMOVAL_URL ? answer() : downloadAnswer()
      },
      logger: pino({ level: 'trace' }, { write: (line: string) => logged.push(line) }),
    })
    return { remove, requests, logged }
  }

  test('sends the image to Bria and answers the downloaded cut-out', async () => {
    const { remove, requests, logged } = removerHarness(() =>
      removal({ url: RESULT_URL, content_type: 'image/png' }),
    )
    expect(await remove(upload())).toEqual({ bytes: GENERATED_PNG, contentType: 'image/png' })
    const [paid, download] = requests
    expect(paid?.url).toBe('https://fal.run/fal-ai/bria/background/remove')
    expect(paid?.init.redirect).toBe('manual')
    expect(paid?.init.headers).toEqual({
      Authorization: `Key ${API_KEY}`,
      'Content-Type': 'application/json',
    })
    expect(JSON.parse(paid?.init.body as string)).toEqual({
      image_url: 'data:image/png;base64,aGVsbG8=',
    })
    expect(download?.url).toBe(RESULT_URL)
    expect(download?.init.headers).toBeUndefined()
    expect(download?.init.redirect).toBe('manual')
    expect(logged).toEqual([])
  })

  test('uses the verified format of the downloaded cut-out regardless of reported content_type', async () => {
    for (const reported of ['application/octet-stream', null]) {
      const { remove } = removerHarness(() => removal({ url: RESULT_URL, content_type: reported }))
      expect((await remove(upload()))?.contentType).toBe('image/png')
    }
  })

  test('every disappointment is an absent image, logged without the provider text', async () => {
    for (const answer of [
      () => new Response(PROVIDER_BODY, { status: 500 }),
      () => json('{}'),
      () => json(`{ ${PROVIDER_BODY}`),
      () => removal({ url: 'http://cdn.example.com/result.png' }),
      () => Promise.reject(new TypeError(`connection reset ${PROVIDER_BODY}`)),
    ]) {
      const { remove, logged } = removerHarness(answer)
      expect(await remove(upload())).toBeNull()
      expect(logged).toHaveLength(1)
      expect(logged.join('\n')).not.toContain(PROVIDER_BODY)
      expect(logged.join('\n')).not.toContain(API_KEY)
    }
  })
})
