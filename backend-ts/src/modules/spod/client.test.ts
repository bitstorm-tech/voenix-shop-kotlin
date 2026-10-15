import { describe, expect, test } from 'bun:test'
import pino from 'pino'
import {
  createSpodClient,
  spodEnvironmentOfStoredValue,
  type SpodAccess,
  type SpodOrderRequest,
  type SpodPreviewRequest,
} from '@/modules/spod'

/**
 * What the client promises production and the catalog sync: exactly these requests go out, every
 * API request carries the destination's token and is paced, and everything other than a usable
 * answer is a bounded code, with nothing the partner wrote, no token, and no URL in the log. Every
 * test drives a stubbed fetch; the access carries staging, which pins the base URL.
 */

const ACCESS_TOKEN = 'spod-access-token-must-never-be-logged'
/** A marker no log line may ever contain: it stands in for whatever the partner writes. */
const PROVIDER_BODY = 'PROVIDER-SECRET-ECHO'
const MIN_INTERVAL = 1050
const MAX_IMAGE_BYTES = 10 * 1024 * 1024
const PNG_BYTES = new Uint8Array([0x89, 0x50, 0x4e, 0x47])
const IMAGE_PATH = 'front-view-of-a-shirt.png'
const IMAGE_URL = `https://image.cdn.example/${IMAGE_PATH}`
const SIGNATURE = 'signature-that-must-never-be-logged'
const SIGNED_IMAGE_URL = `${IMAGE_URL}?signature=${SIGNATURE}`

const access = (timeoutSeconds = 30): SpodAccess => ({
  destinationId: 5,
  environment: 'STAGING',
  accessToken: ACCESS_TOKEN,
  timeoutSeconds,
})

const jsonAnswer = (body: string) =>
  new Response(body, { headers: { 'Content-Type': 'application/json' } })
const imageAnswer = (bytes: Uint8Array, contentType: string) =>
  new Response(bytes, { headers: { 'Content-Type': contentType } })
const refusal = (status: number) => new Response(PROVIDER_BODY, { status })

/**
 * A client on a stubbed fetch, with a fake monotonic clock that the recording sleep advances, so
 * the pacer's arithmetic is observable without a test that takes seconds.
 */
function harness(answer: (request: Request, init: RequestInit) => Response | Promise<Response>) {
  const requests: Request[] = []
  const logged: string[] = []
  const waits: number[] = []
  let clock = 1000
  const client = createSpodClient({
    fetch: async (url, init) => {
      const request = new Request(url, init)
      requests.push(request)
      return answer(request, init)
    },
    now: () => clock,
    sleep: (millis) => {
      waits.push(millis)
      clock += millis
      return Promise.resolve()
    },
    logger: pino({ level: 'trace' }, { write: (line: string) => logged.push(line) }),
  })
  return {
    client,
    requests,
    logged,
    waits,
    advance: (millis: number) => {
      clock += millis
    },
    request: (index = 0): Request => {
      const request = requests[index]
      if (request === undefined) throw new Error(`no request ${String(index)} went out`)
      return request
    },
  }
}

const expectNoSecrets = (logged: string[]) => {
  const all = logged.join('\n')
  expect(all).not.toContain(PROVIDER_BODY)
  expect(all).not.toContain(ACCESS_TOKEN)
  expect(all).not.toContain('rest.spod')
}

const sampleRequest = (): SpodOrderRequest => ({
  externalOrderReference: 'ORD-7-JOB-3',
  email: 'kundin@example.com',
  phone: '+49301234567',
  shipping: {
    address: {
      firstName: 'Erika',
      lastName: 'Musterfrau',
      street: 'Musterstraße 1',
      city: 'Berlin',
      country: 'DE',
      zipCode: '12345',
    },
  },
  oneTimeItems: [
    {
      productTypeId: 812,
      quantityItems: [{ quantity: 2, sizeId: 3, appearanceId: 4 }],
      configurations: [{ image: { designId: 'design-1' }, view: 'FRONT', hotspot: 'MEDIUM_FRONT' }],
    },
  ],
})

const previewRequest = (): SpodPreviewRequest => ({
  configurations: [{ designId: 'design-1', hotspot: 'MEDIUM_FRONT' }],
  appearanceId: '2',
  width: 1000,
  height: 1000,
})

describe('the environment', () => {
  test('a stored value reads back, anything else is a broken database', () => {
    expect(spodEnvironmentOfStoredValue('STAGING')).toBe('STAGING')
    expect(spodEnvironmentOfStoredValue('PRODUCTION')).toBe('PRODUCTION')
    expect(() => spodEnvironmentOfStoredValue('staging')).toThrow()
  })
})

describe('the order calls', () => {
  test('the design upload posts the png as multipart with the access token', async () => {
    const spod = harness(() => jsonAnswer('{"designId":"design-1"}'))

    const result = await spod.client.uploadDesign(access(), 'ORD-1-JOB-1-1.png', PNG_BYTES)

    expect(result).toEqual({ kind: 'answered', value: 'design-1' })
    const request = spod.request()
    expect(request.url).toBe('https://rest.spod-staging.com/designs/upload')
    expect(request.method).toBe('POST')
    expect(request.headers.get('X-SPOD-ACCESS-TOKEN')).toBe(ACCESS_TOKEN)
    const body = await request.text()
    expect(body).toContain('name="file"; filename="ORD-1-JOB-1-1.png"')
    expect(body).toContain('Content-Type: image/png')
  })

  test('the order creation sends the whole contract of this shop, always in state NEW', async () => {
    const spod = harness(() => jsonAnswer('{"id":"spod-42","state":"NEW"}'))

    const result = await spod.client.createOrder(access(), sampleRequest())

    expect(result).toEqual({ kind: 'answered', value: 'spod-42' })
    const request = spod.request()
    expect(request.url).toBe('https://rest.spod-staging.com/orders')
    expect(request.method).toBe('POST')
    expect(request.headers.get('X-SPOD-ACCESS-TOKEN')).toBe(ACCESS_TOKEN)
    expect(request.headers.get('Content-Type')).toContain('application/json')
    const { shipping, oneTimeItems, ...rest } = sampleRequest()
    expect(await request.json()).toEqual({
      ...rest,
      shipping: { ...shipping, preferredType: 'STANDARD' },
      oneTimeItems,
      orderItems: [],
      state: 'NEW',
    })
  })

  /** A numeric order id that failed to read would count as ambiguous and quarantine every job. */
  test('an order id answered as a number is read like a quoted one', async () => {
    const spod = harness(() => jsonAnswer('{"id":12345,"state":"NEW"}'))

    expect(await spod.client.createOrder(access(), sampleRequest())).toEqual({
      kind: 'answered',
      value: '12345',
    })
  })

  test("reading and confirming address the order by the partner's id", async () => {
    const spod = harness(() => jsonAnswer('{"id":"spod-42","state":"NEW"}'))

    const state = await spod.client.getOrder(access(), 'spod-42')
    const confirmed = await spod.client.confirmOrder(access(), 'spod-42')

    expect(state).toEqual({ kind: 'answered', value: 'NEW' })
    expect(confirmed).toEqual({ kind: 'answered', value: undefined })
    expect(spod.requests.map((request) => `${request.method} ${request.url}`)).toEqual([
      'GET https://rest.spod-staging.com/orders/spod-42',
      'POST https://rest.spod-staging.com/orders/spod-42/confirm',
    ])
    expect(spod.requests.map((request) => request.headers.get('X-SPOD-ACCESS-TOKEN'))).toEqual([
      ACCESS_TOKEN,
      ACCESS_TOKEN,
    ])
  })

  test('the hotspots of a product type are asked for one design', async () => {
    const spod = harness(() =>
      jsonAnswer('{"hotspots":[{"name":"LEFT_CHEST"},{"name":"MEDIUM_FRONT"}]}'),
    )

    const result = await spod.client.availableHotspots(access(), 812, 'design-1')

    expect(result).toEqual({ kind: 'answered', value: ['LEFT_CHEST', 'MEDIUM_FRONT'] })
    expect(spod.request().method).toBe('GET')
    expect(spod.request().url).toBe(
      'https://rest.spod-staging.com/productTypes/812/hotspots/design/design-1',
    )
    expect(spod.request().headers.get('X-SPOD-ACCESS-TOKEN')).toBe(ACCESS_TOKEN)
  })

  test('no redirect is followed and every call carries a timeout signal', async () => {
    const inits: RequestInit[] = []
    const spod = harness((_, init) => {
      inits.push(init)
      return jsonAnswer('{"id":"spod-42","state":"NEW"}')
    })

    await spod.client.getOrder(access(), 'spod-42')

    expect(inits[0]?.redirect).toBe('manual')
    expect(inits[0]?.signal).toBeInstanceOf(AbortSignal)
  })
})

describe('failures', () => {
  test('a rate limited answer is the retryable code and not an ambiguity', async () => {
    const spod = harness(() => refusal(429))

    expect(await spod.client.createOrder(access(), sampleRequest())).toEqual({
      kind: 'failed',
      error: 'RATE_LIMITED',
      ambiguous: false,
    })
  })

  test('a refusal is known, a redirect is a refusal, and a server failure is ambiguous', async () => {
    const answers = [refusal(400), refusal(302), refusal(502)]
    const spod = harness(() => answers.shift() ?? refusal(500))

    expect(await spod.client.createOrder(access(), sampleRequest())).toEqual({
      kind: 'failed',
      error: 'REFUSED',
      ambiguous: false,
    })
    expect(await spod.client.createOrder(access(), sampleRequest())).toEqual({
      kind: 'failed',
      error: 'REFUSED',
      ambiguous: false,
    })
    expect(await spod.client.createOrder(access(), sampleRequest())).toEqual({
      kind: 'failed',
      error: 'PROVIDER_UNAVAILABLE',
      ambiguous: true,
    })
  })

  test('an answer that cannot be read is ambiguous and quotes nothing of it', async () => {
    const spod = harness(() => jsonAnswer(`{"unexpected":"${PROVIDER_BODY}"}`))
    const broken = harness(() => jsonAnswer(`{${PROVIDER_BODY}`))

    for (const client of [spod.client, broken.client]) {
      expect(await client.createOrder(access(), sampleRequest())).toEqual({
        kind: 'failed',
        error: 'PROVIDER_ANSWER_UNREADABLE',
        ambiguous: true,
      })
    }
    expect(spod.logged).toHaveLength(1)
    expect(broken.logged).toHaveLength(1)
    expectNoSecrets([...spod.logged, ...broken.logged])
  })

  test('a request without an answer is ambiguous, and a timeout is named as such', async () => {
    const reset = harness(() => Promise.reject(new TypeError(`connection reset ${ACCESS_TOKEN}`)))
    const slow = harness(
      (_, init) =>
        new Promise((_resolve, reject) => {
          init.signal?.addEventListener('abort', () => {
            const reason: unknown = init.signal?.reason
            reject(reason instanceof Error ? reason : new Error('aborted without a reason'))
          })
        }),
    )

    const failure = { kind: 'failed', error: 'PROVIDER_UNAVAILABLE', ambiguous: true } as const
    expect(await reset.client.getOrder(access(), 'spod-42')).toEqual(failure)
    expect(await slow.client.getOrder(access(0), 'spod-42')).toEqual(failure)
    expect(slow.logged.join('\n')).toContain('did not answer within the configured timeout')
    expectNoSecrets([...reset.logged, ...slow.logged])
  })

  test('neither the token nor the provider body reaches a log line, the status does', async () => {
    const spod = harness(() => refusal(403))

    await spod.client.uploadDesign(access(), 'design.png', PNG_BYTES)
    await spod.client.createOrder(access(), sampleRequest())
    await spod.client.confirmOrder(access(), 'spod-42')
    await spod.client.articles(access(), 2, 0)
    await spod.client.sizeChart(access(), 812)

    expect(spod.logged).toHaveLength(5)
    expect(spod.logged.every((line) => line.includes('403'))).toBe(true)
    expectNoSecrets(spod.logged)
  })
})

describe('the pacer', () => {
  /**
   * The first request goes out at once, every later one waits out the remainder of the interval
   * since the previous one, and a caller that was slow anyway waits not at all.
   */
  test('requests are paced at least the minimum interval apart', async () => {
    const spod = harness(() => jsonAnswer('{"id":"spod-1","state":"NEW"}'))

    await spod.client.getOrder(access(), 'spod-1')
    await spod.client.getOrder(access(), 'spod-1')
    spod.advance(50)
    await spod.client.getOrder(access(), 'spod-1')
    spod.advance(MIN_INTERVAL * 2)
    await spod.client.getOrder(access(), 'spod-1')

    expect(spod.waits).toEqual([MIN_INTERVAL, MIN_INTERVAL - 50])
  })

  test('concurrent callers queue up behind each other', async () => {
    const spod = harness(() => jsonAnswer('{"id":"spod-1","state":"NEW"}'))

    await Promise.all([
      spod.client.getOrder(access(), 'spod-1'),
      spod.client.views(access(), 812),
      spod.client.articles(access(), 2, 0),
    ])

    expect(spod.waits).toEqual([MIN_INTERVAL, MIN_INTERVAL])
    expect(spod.requests).toHaveLength(3)
  })

  /** The 60-per-minute budget is the API's. A CDN image is not one of those 60. */
  test('image downloads are not paced while the catalog calls are', async () => {
    const spod = harness((request) =>
      new URL(request.url).host === 'image.cdn.example'
        ? imageAnswer(PNG_BYTES, 'image/png')
        : jsonAnswer(ARTICLES_PAGE_2),
    )

    await spod.client.download(IMAGE_URL, 30)
    await spod.client.download(IMAGE_URL, 30)
    expect(spod.waits).toEqual([])

    await spod.client.articles(access(), 2, 0)
    await spod.client.articles(access(), 2, 2)
    expect(spod.waits).toEqual([MIN_INTERVAL])
  })
})

/** Two pages of one three-article catalog; ids are numbers in the first, quoted in the second. */
const ARTICLES_PAGE_1 = `
{"count":3,"limit":2,"offset":0,"items":[
  {"id":11,"title":"Shirt","description":"A shirt","unknownField":"ignored",
   "variants":[{"id":101,"productTypeId":812,"productTypeName":"Men's T-Shirt","appearanceId":4,
                "appearanceName":"Schwarz","appearanceColorValue":"#0A0B0C","sizeId":3,
                "sizeName":"M","sku":"SKU-1","imageIds":[901]}],
   "images":[{"id":901,"productId":598279462,"appearanceId":4,"perspective":"front",
              "imageUrl":"https://image.cdn.example/901.png"}]},
  {"id":12}
]}`
const ARTICLES_PAGE_2 = '{"count":3,"limit":2,"offset":2,"items":[{"id":"13"}]}'
const articlesPage = (articleId: string, variantId: string) =>
  `{"count":1,"limit":1,"offset":0,"items":[{"id":${articleId},"variants":[{"id":${variantId},"productTypeId":812}]}]}`

describe('the catalog calls', () => {
  test('the article listing pages with limit and offset and reads the sparse fields as defaults', async () => {
    const pages = [ARTICLES_PAGE_1, ARTICLES_PAGE_2]
    const spod = harness(() => jsonAnswer(pages.shift() ?? '{}'))

    const first = await spod.client.articles(access(), 2, 0)
    const second = await spod.client.articles(access(), 2, 2)

    expect(spod.requests.map((request) => request.url)).toEqual([
      'https://rest.spod-staging.com/articles?limit=2&offset=0',
      'https://rest.spod-staging.com/articles?limit=2&offset=2',
    ])
    expect(spod.request().headers.get('X-SPOD-ACCESS-TOKEN')).toBe(ACCESS_TOKEN)
    expect(first).toEqual({
      kind: 'answered',
      value: {
        count: 3,
        items: [
          {
            id: '11',
            title: 'Shirt',
            description: 'A shirt',
            variants: [
              {
                id: '101',
                productTypeId: 812,
                productTypeName: "Men's T-Shirt",
                appearanceId: 4,
                appearanceName: 'Schwarz',
                appearanceColorValue: '#0A0B0C',
                sizeId: 3,
                sizeName: 'M',
                sku: 'SKU-1',
              },
            ],
            images: [
              {
                id: '901',
                productId: 598279462,
                appearanceId: 4,
                perspective: 'front',
                imageUrl: 'https://image.cdn.example/901.png',
              },
            ],
          },
          { id: '12', title: '', description: '', variants: [], images: [] },
        ],
      },
    })
    expect(second).toEqual({
      kind: 'answered',
      value: {
        count: 3,
        items: [{ id: '13', title: '', description: '', variants: [], images: [] }],
      },
    })
  })

  /** Otherwise a re-sync would create a second row for every shirt. */
  test('article and variant ids answered as numbers read like quoted ones', async () => {
    const numeric = harness(() => jsonAnswer(articlesPage('42', '7')))
    const quoted = harness(() => jsonAnswer(articlesPage('"42"', '"7"')))

    const fromNumbers = await numeric.client.articles(access(), 1, 0)
    const fromStrings = await quoted.client.articles(access(), 1, 0)

    expect(fromNumbers).toEqual(fromStrings)
    expect(fromNumbers.kind === 'answered' && fromNumbers.value.items[0]?.variants[0]?.id).toBe('7')
  })

  /** Only a page with a count may tell the sync that the listing is complete. */
  test('a page without a count reads as a null count, an empty answer as an empty page', async () => {
    const spod = harness(() => jsonAnswer('{}'))

    expect(await spod.client.articles(access(), 2, 0)).toEqual({
      kind: 'answered',
      value: { items: [], count: null },
    })
  })

  test('the size chart is asked per product type', async () => {
    const spod = harness(() => jsonAnswer('{"sizeImageUrl":"https://image.cdn.example/chart.png"}'))

    expect(await spod.client.sizeChart(access(), 812)).toEqual({
      kind: 'answered',
      value: { sizeImageUrl: 'https://image.cdn.example/chart.png' },
    })
    expect(spod.request().method).toBe('GET')
    expect(spod.request().url).toBe('https://rest.spod-staging.com/productTypes/812/size-chart')
    expect(spod.request().headers.get('X-SPOD-ACCESS-TOKEN')).toBe(ACCESS_TOKEN)
  })
})

describe('the calibration calls', () => {
  test('the views of a product type are read with the access token', async () => {
    const spod = harness(() =>
      jsonAnswer(
        `{"views":[{"id":"1","name":"FRONT","unknownField":"ignored",
          "hotspots":[{"name":"MEDIUM_FRONT"}],
          "images":[{"appearanceId":"2","image":"https://image.cdn.example/front.png"}]}]}`,
      ),
    )

    expect(await spod.client.views(access(), 812)).toEqual({
      kind: 'answered',
      value: {
        views: [
          {
            id: '1',
            name: 'FRONT',
            hotspots: [{ name: 'MEDIUM_FRONT' }],
            images: [{ appearanceId: '2', image: 'https://image.cdn.example/front.png' }],
          },
        ],
      },
    })
    expect(spod.request().method).toBe('GET')
    expect(spod.request().url).toBe('https://rest.spod-staging.com/productTypes/812/views')
    expect(spod.request().headers.get('X-SPOD-ACCESS-TOKEN')).toBe(ACCESS_TOKEN)
  })

  test('a preview request posts exactly the configurations, the colour, and the size', async () => {
    const spod = harness(() =>
      jsonAnswer(
        '{"images":[{"url":"https://image.cdn.example/preview.webp","viewId":"1","viewName":"FRONT","unknownField":"ignored"}]}',
      ),
    )

    expect(await spod.client.previews(access(), 812, previewRequest())).toEqual({
      kind: 'answered',
      value: {
        images: [{ url: 'https://image.cdn.example/preview.webp', viewId: '1', viewName: 'FRONT' }],
      },
    })
    const request = spod.request()
    expect(request.method).toBe('POST')
    expect(request.url).toBe('https://rest.spod-staging.com/productTypes/812/previews')
    expect(request.headers.get('X-SPOD-ACCESS-TOKEN')).toBe(ACCESS_TOKEN)
    expect(request.headers.get('Content-Type')).toContain('application/json')
    expect(await request.json()).toEqual({
      configurations: [{ designId: 'design-1', hotspot: 'MEDIUM_FRONT' }],
      appearanceId: '2',
      width: 1000,
      height: 1000,
    })
  })

  test('a views or previews answer without its array reads as empty', async () => {
    const spod = harness(() => jsonAnswer('{"unknownField":"ignored"}'))

    expect(await spod.client.views(access(), 812)).toEqual({
      kind: 'answered',
      value: { views: [] },
    })
    expect(await spod.client.previews(access(), 812, previewRequest())).toEqual({
      kind: 'answered',
      value: { images: [] },
    })
  })

  test('the calibration calls judge a refusal like every other call', async () => {
    for (const [status, error, ambiguous] of [
      [429, 'RATE_LIMITED', false],
      [502, 'PROVIDER_UNAVAILABLE', true],
      [400, 'REFUSED', false],
    ] as const) {
      const spod = harness(() => refusal(status))
      const failure = { kind: 'failed', error, ambiguous } as const
      expect(await spod.client.views(access(), 812)).toEqual(failure)
      expect(await spod.client.previews(access(), 812, previewRequest())).toEqual(failure)
    }
  })
})

describe('the image download', () => {
  /** The token stays home: a host this adapter never chose must not get the merchant's key. */
  test('an image download sends no token and answers the bytes with their type', async () => {
    const spod = harness(() => imageAnswer(PNG_BYTES, 'image/png'))

    expect(await spod.client.download(IMAGE_URL, 30)).toEqual({
      kind: 'answered',
      value: { bytes: PNG_BYTES, contentType: 'image/png' },
    })
    const request = spod.request()
    expect(request.method).toBe('GET')
    expect(request.url).toBe(IMAGE_URL)
    expect(request.headers.get('X-SPOD-ACCESS-TOKEN')).toBeNull()
    expect(request.headers.get('User-Agent')).toBe('Spreadconnect_API_flow')
  })

  test('an image download refuses a URL that is not https before anything goes out', async () => {
    const spod = harness(() => imageAnswer(PNG_BYTES, 'image/png'))

    for (const url of ['http://image.cdn.example/a.png', '/a.png', 'not a url', 'https://']) {
      expect(await spod.client.download(url, 30)).toEqual({
        kind: 'failed',
        error: 'PROVIDER_ANSWER_UNREADABLE',
        ambiguous: false,
      })
    }
    expect(spod.requests).toHaveLength(0)
  })

  test('an image download refuses an answer that is not an image, or one over the cap', async () => {
    const answers = [
      imageAnswer(new TextEncoder().encode(PROVIDER_BODY), 'text/html'),
      imageAnswer(new Uint8Array(MAX_IMAGE_BYTES + 1), 'image/png'),
    ]
    const spod = harness(() => answers.shift() ?? refusal(500))

    const unusable = {
      kind: 'failed',
      error: 'PROVIDER_ANSWER_UNREADABLE',
      ambiguous: false,
    } as const
    expect(await spod.client.download(IMAGE_URL, 30)).toEqual(unusable)
    expect(await spod.client.download(IMAGE_URL, 30)).toEqual(unusable)
  })

  test('a refused download is judged like every API call', async () => {
    const spod = harness(() => refusal(403))

    expect(await spod.client.download(IMAGE_URL, 30)).toEqual({
      kind: 'failed',
      error: 'REFUSED',
      ambiguous: false,
    })
  })

  test('a download logs the host and nothing of the path, the query, or the user info', async () => {
    const spod = harness((request) =>
      request.url.endsWith('timeout')
        ? Promise.reject(new DOMException(`timed out on ${request.url}`, 'TimeoutError'))
        : imageAnswer(new TextEncoder().encode(PROVIDER_BODY), 'text/html'),
    )

    await spod.client.download(SIGNED_IMAGE_URL, 30)
    await spod.client.download(`https://user:password@image.cdn.example:443/${IMAGE_PATH}`, 30)
    await spod.client.download(`${IMAGE_URL}?timeout`, 30)

    expect(spod.logged).toHaveLength(3)
    expect(spod.logged.every((line) => line.includes('image.cdn.example'))).toBe(true)
    const all = spod.logged.join('\n')
    for (const secret of [IMAGE_PATH, SIGNATURE, 'password', PROVIDER_BODY]) {
      expect(all).not.toContain(secret)
    }
  })
})
