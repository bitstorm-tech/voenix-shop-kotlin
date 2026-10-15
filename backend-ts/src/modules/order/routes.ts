import { zValidator } from '@hono/zod-validator'
import { Hono, type Context } from 'hono'
import { createMiddleware } from 'hono/factory'
import { z } from 'zod'
import type { ProductionPdfGenerator, ProductionPdfResult } from '@/modules/production'
import { guestCapable, requireAdmin, type Auth } from '@/platform/auth/auth'
import { apiError, respondResult } from '@/platform/http/respond'
import type { OrderService } from './service'

const ORDER_NOT_FOUND = 'Order not found'
const ORDER_RESPONSES = { notFound: ORDER_NOT_FOUND, conflict: null }

/** An order answer is personal: no cache may keep it, a refusal included. */
const noStore = createMiddleware(async (c, next) => {
  c.header('Cache-Control', 'no-store')
  await next()
})

const id = z
  .string()
  .regex(/^\d{1,15}$/)
  .transform(Number)

/**
 * The ids of a path. An id that is not a number never named an order, so it answers the same `404`
 * an unknown one does, never a `400` telling a prober where the probe went wrong.
 */
function idParams<Names extends string>(...names: Names[]) {
  const shape = Object.fromEntries(names.map((name) => [name, id])) as Record<Names, typeof id>
  return zValidator('param', z.object(shape), (result, c) =>
    result.success ? undefined : c.json(apiError(ORDER_NOT_FOUND), 404),
  )
}

/** The signed-in customer's id, or `null` for a guest. */
const userIdOf = (c: Context): number | null => {
  const userId = Number(c.var.principal?.userId)
  return Number.isSafeInteger(userId) && userId > 0 ? userId : null
}

/**
 * The customer's own orders (guest-capable) and the anonymous mail-link lookup, on two disjoint
 * subtrees. Reading never mints a guest cookie; the lookup has no protection at all, because the
 * token in its path is the credential, and every miss there is the same `404`.
 */
export function orderRoutes(orders: OrderService, guestTokens: Auth['guestTokens']): Hono {
  const customer = new Hono()
  customer.use(noStore, guestCapable)
  customer.get('/', async (c) =>
    c.json(await orders.history(userIdOf(c), await guestTokens.tryGet(c))),
  )
  customer.get('/:orderId', idParams('orderId'), async (c) =>
    respondResult(
      c,
      await orders.order(c.req.valid('param').orderId, userIdOf(c), await guestTokens.tryGet(c)),
      ORDER_RESPONSES,
    ),
  )

  const lookup = new Hono()
  lookup.use(noStore)
  lookup.get('/', (c) => c.json(apiError(ORDER_NOT_FOUND), 404))
  lookup.get('/:token', async (c) =>
    respondResult(c, await orders.orderByToken(c.req.param('token')), ORDER_RESPONSES),
  )

  const routes = new Hono()
  routes.route('/api/orders', customer)
  routes.route('/api/order-lookup', lookup)
  return routes
}

/**
 * The production documents of one order, for admins only: the list names the suppliers a
 * download exists for, the fetch picks its document out of the same freshly generated set.
 * Nothing is stored.
 */
export function adminPdfRoutes(generator: Pick<ProductionPdfGenerator, 'generate'>): Hono {
  const admin = new Hono()
  admin.use(noStore, requireAdmin)
  admin.get('/:orderId/production-pdfs', idParams('orderId'), async (c) => {
    const result = await generator.generate(c.req.valid('param').orderId)
    if (result.kind !== 'generated') return pdfFailure(c, result)
    return c.json(
      result.documents.map((document) => ({
        supplierId: document.supplierId,
        fileName: document.fileName,
      })),
    )
  })
  admin.get(
    '/:orderId/production-pdfs/:supplierId',
    idParams('orderId', 'supplierId'),
    async (c) => {
      const { orderId, supplierId } = c.req.valid('param')
      const result = await generator.generate(orderId)
      if (result.kind !== 'generated') return pdfFailure(c, result)
      const document = result.documents.find((candidate) => candidate.supplierId === supplierId)
      if (document === undefined) return c.json(apiError(ORDER_NOT_FOUND), 404)
      return c.body(new Uint8Array(document.bytes), 200, {
        'Content-Type': document.mediaType,
        'Content-Disposition': `attachment; filename="${document.fileName}"`,
      })
    },
  )

  const routes = new Hono()
  routes.route('/api/admin/orders', admin)
  return routes
}

/**
 * Three of the four reasons are about the order's own production data, which an admin can repair:
 * `409`. A renderer failure is nobody's data problem: `500`, details in the log only.
 */
function pdfFailure(c: Context, result: Exclude<ProductionPdfResult, { kind: 'generated' }>) {
  if (result.kind === 'orderNotFound') return c.json(apiError(ORDER_NOT_FOUND), 404)
  switch (result.error) {
    case 'MISSING_IMAGE':
      return c.json(
        apiError(
          'An ordered item has no usable production image',
          {},
          'PRODUCTION_PDF_MISSING_IMAGE',
        ),
        409,
      )
    case 'UNREADABLE_IMAGE':
      return c.json(
        apiError(
          "An ordered item's production image cannot be read",
          {},
          'PRODUCTION_PDF_UNREADABLE_IMAGE',
        ),
        409,
      )
    case 'INVALID_SOURCE':
      return c.json(
        apiError(
          'The order carries production data no document can be laid out from',
          {},
          'PRODUCTION_PDF_INVALID_SOURCE',
        ),
        409,
      )
    case 'RENDER_FAILURE':
      return c.json(
        apiError(
          'The production document could not be rendered',
          {},
          'PRODUCTION_PDF_RENDER_FAILURE',
        ),
        500,
      )
  }
}
