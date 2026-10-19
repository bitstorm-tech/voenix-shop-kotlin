import { readFile } from 'node:fs/promises'
import { Hono, type Context } from 'hono'
import { z } from 'zod'
import { requireAdmin, requireAuthenticated, type Auth } from '@/platform/auth/auth'
import { apiError, jsonBody, respondFailure } from '@/platform/http/respond'
import type { OperationResult } from '@/platform/operation'
import type { OrphanImages } from './orphans'
import {
  PRINT_IMAGE_FOLDER,
  type ImageResource,
  type ImageService,
  type ImageVisibility,
} from './service'

/**
 * Answers which stored print image `imageId` is, when it belongs to the caller identified by
 * `guestToken` or `userId` (either may be `null`), and `null` otherwise. It must not tell "no such
 * image" from "somebody else's image": the guest route answers both with `404`, so an id cannot be
 * probed for existence. The cart module implements it; the composition root hands it over.
 */
export type GuestImageResolver = (caller: {
  imageId: number
  guestToken: string | null
  userId: number | null
}) => Promise<string | null>

const IMAGE_RESPONSES = { notFound: 'Image not found', conflict: null }
const CACHE_CONTROL: Record<ImageVisibility, string> = {
  public: 'public, max-age=86400',
  private: 'private, max-age=3600',
}

/** The public delivery route and the private one, which needs any signed-in session. */
export function imageRoutes(images: ImageService): Hono {
  const privateImages = new Hono()
  privateImages.use(requireAuthenticated)
  privateImages.get('/:size/:filename{.+}', async (c) =>
    respondImage(
      c,
      'private',
      await images.get('private', c.req.param('size'), c.req.param('filename')),
    ),
  )

  const routes = new Hono()
  routes.get('/api/images/public/:size/:filename{.+}', async (c) =>
    respondImage(
      c,
      'public',
      await images.get('public', c.req.param('size'), c.req.param('filename')),
    ),
  )
  routes.route('/api/images/private', privateImages)
  return routes
}

/**
 * `GET /api/images/guest/{size}/{id}`: a print image for whoever the resolver accepts. It needs no
 * session, reads the guest cookie without ever creating one (looking at an image must not create a
 * guest), and answers `404` for a non-numeric id and for a `null` from the resolver alike.
 */
export function guestImageRoute(
  images: ImageService,
  guestTokens: Auth['guestTokens'],
  resolver: GuestImageResolver,
): Hono {
  const routes = new Hono()
  routes.get('/api/images/guest/:size/:id', async (c) => {
    const imageId = integerOrNull(c.req.param('id'))
    const filename =
      imageId === null
        ? null
        : await resolver({
            imageId,
            guestToken: await guestTokens.tryGet(c),
            userId: c.var.principal?.userId ?? null,
          })
    if (filename === null) return c.json(apiError('Image not found'), 404)
    const result = await images.get(
      'private',
      c.req.param('size'),
      `${PRINT_IMAGE_FOLDER}/${filename}`,
    )
    return respondImage(c, 'private', result)
  })
  return routes
}

/** One original the delete names; the orphan rules decide whether it is really deleted. */
const imageLocation = z.strictObject({
  visibility: z.enum(['public', 'private']),
  path: z.string().min(1).max(1024),
})

const deleteInput = z.strictObject({
  images: z.array(imageLocation).min(1).max(10_000),
})

/**
 * The admin orphan routes: `GET /api/admin/images/orphans` lists every original no module refers
 * to, oldest first, and `POST /api/admin/images/orphans/delete` deletes those of the named ones that
 * are still orphans, answering `{ deleted, kept }`. `requireAdmin` checks the CSRF pair of the
 * `POST`.
 */
export function orphanImageRoutes(orphans: OrphanImages): Hono {
  const admin = new Hono()
  admin.use(requireAdmin, async (c, next) => {
    c.header('Cache-Control', 'no-store')
    await next()
  })
  admin.get('/', async (c) =>
    c.json(
      (await orphans.list()).map((image) => ({
        visibility: image.visibility,
        path: image.path,
        size: image.size,
        modifiedAt: image.modifiedAt.toISOString(),
      })),
    ),
  )
  admin.post('/delete', jsonBody(deleteInput), async (c) =>
    c.json(await orphans.delete(c.req.valid('json').images)),
  )

  const routes = new Hono()
  routes.route('/api/admin/images/orphans', admin)
  return routes
}

function integerOrNull(value: string | undefined): number | null {
  if (value === undefined || !/^-?\d+$/.test(value)) return null
  const number = Number(value)
  return Number.isSafeInteger(number) ? number : null
}

async function respondImage(
  c: Context,
  visibility: ImageVisibility,
  result: OperationResult<ImageResource>,
): Promise<Response> {
  if (result.kind !== 'ok') return respondFailure(c, result, IMAGE_RESPONSES)
  return respondFile(c, result.value, CACHE_CONTROL[visibility])
}

/**
 * Sends a file with the validators and the conditional and range handling Ktor's
 * `ConditionalHeaders` and `PartialContent` gave the Kotlin routes: `ETag` and `Last-Modified`,
 * `304` for a matching `If-None-Match` (or, without one, a satisfied `If-Modified-Since`), and
 * `206` for a single byte range unless an `If-Range` no longer matches. A multi-range or malformed
 * `Range` is ignored and answered with the whole file.
 */
async function respondFile(c: Context, file: ImageResource, cacheControl: string) {
  const etag = `"${file.size.toString(16)}-${file.lastModified.getTime().toString(16)}"`
  const lastModified = file.lastModified.toUTCString()
  const headers = {
    'Cache-Control': cacheControl,
    ETag: etag,
    'Last-Modified': lastModified,
    'Accept-Ranges': 'bytes',
  }
  if (notModified(c, etag, file.lastModified)) return c.body(null, 304, headers)

  const bytes = await readFile(file.path)
  const ifRange = c.req.header('If-Range')
  const range =
    ifRange === undefined || ifRange === etag || ifRange === lastModified
      ? byteRange(c.req.header('Range'), bytes.length)
      : null
  if (range === 'unsatisfiable') {
    return c.body(null, 416, { ...headers, 'Content-Range': `bytes */${String(bytes.length)}` })
  }
  const [start, end] = range ?? [0, bytes.length - 1]
  const content = bytes.subarray(start, end + 1)
  return c.body(content, range === null ? 200 : 206, {
    ...headers,
    'Content-Type': file.contentType,
    'Content-Length': String(content.length),
    ...(range === null
      ? {}
      : { 'Content-Range': `bytes ${String(start)}-${String(end)}/${String(bytes.length)}` }),
  })
}

function notModified(c: Context, etag: string, lastModified: Date): boolean {
  const ifNoneMatch = c.req.header('If-None-Match')
  if (ifNoneMatch !== undefined) {
    return ifNoneMatch
      .split(',')
      .some((tag) => ['*', etag].includes(tag.trim().replace(/^W\//, '')))
  }
  const since = Date.parse(c.req.header('If-Modified-Since') ?? '')
  return Math.floor(lastModified.getTime() / 1000) * 1000 <= since
}

/** The inclusive byte range one `bytes=` range asks for, or `null` to send the whole file. */
function byteRange(
  header: string | undefined,
  length: number,
): [number, number] | 'unsatisfiable' | null {
  const match = /^bytes=(\d*)-(\d*)$/.exec(header?.trim() ?? '')
  if (match === null) return null
  const [, first = '', last = ''] = match
  if (first === '') {
    if (last === '') return null
    const count = Number(last)
    return count === 0 ? 'unsatisfiable' : [Math.max(0, length - count), length - 1]
  }
  const start = Number(first)
  const end = last === '' ? length - 1 : Math.min(Number(last), length - 1)
  if (start >= length) return 'unsatisfiable'
  return end < start ? null : [start, end]
}
