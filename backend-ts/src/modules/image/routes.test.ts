import { afterAll, beforeAll, describe, expect, test } from 'bun:test'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { Hono } from 'hono'
import sharp from 'sharp'
import { createImageModule, type GuestImageResolver } from '@/modules/image'
import { cookieHeader, jsonOf, signedInHeaders, testApp, testAuth } from '@test-support/http'

let directory: string
let images: ReturnType<typeof createImageModule>
let printImage: string

const png = (width: number, height: number) =>
  sharp({ create: { width, height, channels: 3, background: '#336699' } })
    .png()
    .toBuffer()

beforeAll(async () => {
  directory = await mkdtemp(join(tmpdir(), 'image-routes-'))
  const settings = {
    publicRoot: join(directory, 'public'),
    privateRoot: join(directory, 'private'),
    cacheRoot: join(directory, 'cache'),
  }
  images = createImageModule({ settings, guestTokens: testAuth.guestTokens })
  await mkdir(join(settings.publicRoot, 'nested'))
  await writeFile(join(settings.publicRoot, 'nested', 'sample.png'), await png(300, 200))
  await writeFile(join(settings.privateRoot, 'private.png'), await png(300, 200))
  const stored = await images.privateStorage.store({
    bytes: await png(300, 200),
    contentType: 'image/png',
  })
  if (stored.kind !== 'ok') throw new Error('The print image was not stored')
  printImage = stored.value.filename
})

afterAll(async () => {
  await rm(directory, { recursive: true, force: true })
})

describe('GET /api/images/public/{size}/{filename...}', () => {
  test('serves the resized file with cache headers, 304 for its ETag, and 206 for a range', async () => {
    const app = testApp(images.routes)
    const response = await app.request('/api/images/public/120/nested/sample.png')
    expect(response.status).toBe(200)
    expect(response.headers.get('Content-Type')).toBe('image/png')
    expect(response.headers.get('Cache-Control')).toBe('public, max-age=86400')
    expect(response.headers.get('Accept-Ranges')).toBe('bytes')
    expect(response.headers.get('Last-Modified')).not.toBeNull()
    const bytes = new Uint8Array(await response.arrayBuffer())
    expect(response.headers.get('Content-Length')).toBe(String(bytes.length))
    expect(await sharp(bytes).metadata()).toMatchObject({ format: 'png', width: 120, height: 80 })
    const etag = response.headers.get('ETag') ?? ''
    expect(etag).toMatch(/^"[0-9a-f]+-[0-9a-f]+"$/)

    const unchanged = await app.request('/api/images/public/120/nested/sample.png', {
      headers: { 'If-None-Match': etag },
    })
    expect(unchanged.status).toBe(304)
    expect(unchanged.headers.get('Cache-Control')).toBe('public, max-age=86400')

    const range = await app.request('/api/images/public/120/nested/sample.png', {
      headers: { Range: 'bytes=0-9' },
    })
    expect(range.status).toBe(206)
    expect(range.headers.get('Content-Range')).toBe(`bytes 0-9/${String(bytes.length)}`)
    expect(new Uint8Array(await range.arrayBuffer())).toEqual(bytes.subarray(0, 10))
  })

  test('answers 404 for a missing original and 400 for an invalid size or format', async () => {
    const app = testApp(images.routes)
    const missing = await app.request('/api/images/public/120/missing.png')
    expect(missing.status).toBe(404)
    expect(await jsonOf(missing)).toEqual({ message: 'Image not found', errors: {} })
    const size = await app.request('/api/images/public/4097/nested/sample.png')
    expect(await jsonOf(size)).toEqual({
      message: 'Validation failed',
      errors: { size: ['Size must be width or widthxheight from 1 through 4096'] },
    })
    const format = await app.request('/api/images/public/120/nested/sample.gif')
    expect(format.status).toBe(400)
  })

  test('a traversal attempt never leaves the root', async () => {
    const app = testApp(images.routes)
    // Dot segments are resolved by the URL itself, so the request misses every image route.
    expect((await app.request('/api/images/public/120/../../../private.png')).status).toBe(404)
    expect((await app.request('/api/images/public/120/%2e%2e/private.png')).status).toBe(404)
    // An encoded slash survives into the file name, which the name check refuses.
    const encoded = await app.request('/api/images/public/120/..%2Fprivate%2Fprivate.png')
    expect(encoded.status).toBe(400)
    expect(await jsonOf(encoded)).toEqual({
      message: 'Validation failed',
      errors: { filename: ['Invalid image filename'] },
    })
  })
})

describe('GET /api/images/private/{size}/{filename...}', () => {
  test('needs a session and then serves the file as private', async () => {
    const app = testApp(images.routes)
    expect((await app.request('/api/images/private/120/private.png')).status).toBe(401)
    const response = await app.request('/api/images/private/120/private.png', {
      headers: await signedInHeaders({ roles: ['CUSTOMER'] }),
    })
    expect(response.status).toBe(200)
    expect(response.headers.get('Cache-Control')).toBe('private, max-age=3600')
  })
})

describe('GET /api/images/guest/{size}/{id}', () => {
  const resolverOwning = (owned: (caller: Parameters<GuestImageResolver>[0]) => boolean) => {
    const calls: Parameters<GuestImageResolver>[0][] = []
    const resolver: GuestImageResolver = (caller) => {
      calls.push(caller)
      return Promise.resolve(owned(caller) ? printImage : null)
    }
    return { calls, resolver }
  }
  const guestCookie = async () => {
    const probe = new Hono().get('/probe', async (c) => {
      await testAuth.guestTokens.getOrCreate(c)
      return c.body(null, 204)
    })
    return cookieHeader(await testApp(probe).request('/probe'))
  }

  test('serves a guest owner through the guest cookie without issuing a cookie', async () => {
    const { calls, resolver } = resolverOwning((caller) => caller.guestToken !== null)
    const app = testApp(images.guestImageRoute(resolver))
    const response = await app.request('/api/images/guest/120/7', {
      headers: { Cookie: await guestCookie() },
    })
    expect(response.status).toBe(200)
    expect(response.headers.get('Content-Type')).toBe('image/webp')
    expect(response.headers.get('Cache-Control')).toBe('private, max-age=3600')
    expect(response.headers.getSetCookie()).toEqual([])
    expect(calls).toEqual([{ imageId: 7, guestToken: expect.any(String) as string, userId: null }])
    const etag = response.headers.get('ETag') ?? ''
    const unchanged = await app.request('/api/images/guest/120/7', {
      headers: { Cookie: await guestCookie(), 'If-None-Match': etag },
    })
    expect(unchanged.status).toBe(304)
  })

  test('serves a signed-in owner through the session', async () => {
    const { calls, resolver } = resolverOwning((caller) => caller.userId === 42)
    const app = testApp(images.guestImageRoute(resolver))
    const response = await app.request('/api/images/guest/120x120/7', {
      headers: await signedInHeaders({ userId: '42', roles: ['CUSTOMER'] }),
    })
    expect(response.status).toBe(200)
    expect(calls).toEqual([{ imageId: 7, guestToken: null, userId: 42 }])
  })

  test('answers 404 for an image the resolver denies and for a non-numeric id', async () => {
    const { calls, resolver } = resolverOwning(() => false)
    const app = testApp(images.guestImageRoute(resolver))
    const denied = await app.request('/api/images/guest/120/7')
    expect(denied.status).toBe(404)
    expect(await jsonOf(denied)).toEqual({ message: 'Image not found', errors: {} })
    expect((await app.request('/api/images/guest/120/abc')).status).toBe(404)
    expect(calls).toHaveLength(1)
  })
})
