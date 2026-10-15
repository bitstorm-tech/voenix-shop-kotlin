import { Hono } from 'hono'
import { z } from 'zod'
import { requireAdmin } from '@/platform/auth/auth'
import {
  formBody,
  integerParam,
  jsonBody,
  respondFailure,
  respondResult,
} from '@/platform/http/respond'
import { PRINT_ASPECT_RATIOS } from '../catalog'
import {
  articlePrice,
  exampleImageUpload,
  optionalFilename,
  optionalId,
  optionalText,
  reorderInput,
  requiredText,
} from '../routes'
import type { MugArticleInput, MugService } from './service'

/** A measurement every mug with details carries; an omitted one is `0` and fails the same rule. */
const measurement = (field: string) =>
  z.int().positive(`${field} must be greater than zero`).prefault(0)

const optionalMeasurement = (field: string) =>
  z.int().positive(`${field} must be greater than zero`).nullable().default(null)

const mugDetails = z.strictObject({
  heightMm: measurement('HeightMm'),
  diameterMm: measurement('DiameterMm'),
  printTemplateWidthMm: measurement('PrintTemplateWidthMm'),
  printTemplateHeightMm: measurement('PrintTemplateHeightMm'),
  fillingQuantity: optionalText('FillingQuantity', 255),
  dishwasherSafe: z.boolean().default(false),
  documentFormatWidthMm: optionalMeasurement('DocumentFormatWidthMm'),
  documentFormatHeightMm: optionalMeasurement('DocumentFormatHeightMm'),
  documentFormatMarginBottomMm: optionalMeasurement('DocumentFormatMarginBottomMm'),
})

const mugVariant = z.strictObject({
  id: optionalId('Id'),
  name: requiredText('Name', 255),
  insideColorCode: requiredText('InsideColorCode', 255),
  outsideColorCode: requiredText('OutsideColorCode', 255),
  isDefault: z.boolean().default(false),
  // An omitted flag is `false`, so a variant array that says nothing cannot make a mug visible.
  active: z.boolean().default(false),
  exampleImageFilename: optionalFilename,
})

/**
 * The create and update body of a mug. An omitted `price` keeps the stored one; there is no
 * `priceId` and no `articleType`. The rules about the article as a whole report on `active`; the
 * last activation rule, a price, belongs to the write path because an update may keep the stored
 * price.
 */
export const mugArticleInput = z
  .strictObject({
    name: requiredText('Name', 255),
    descriptionShort: requiredText('DescriptionShort', 1000),
    descriptionLong: requiredText('DescriptionLong', 5000),
    active: z.boolean().default(false),
    categoryId: optionalId('CategoryId'),
    subcategoryId: optionalId('SubcategoryId'),
    supplierId: optionalId('SupplierId'),
    supplierArticleName: optionalText('SupplierArticleName', 255),
    supplierArticleNumber: optionalText('SupplierArticleNumber', 255),
    printAspectRatio: z
      .string()
      .trim()
      .nullable()
      .default(null)
      .transform((ratio, ctx) => {
        if (ratio === null) return '16:9'
        const known = PRINT_ASPECT_RATIOS.find((candidate) => candidate === ratio)
        if (known !== undefined) return known
        ctx.addIssue({
          code: 'custom',
          message: `PrintAspectRatio must be one of ${PRINT_ASPECT_RATIOS.join(', ')}`,
        })
        return z.NEVER
      }),
    mugDetails: mugDetails.nullable().default(null),
    mugVariants: z.array(mugVariant).default([]),
    price: articlePrice,
  })
  .superRefine((input, ctx) => {
    const add = (field: string, message: string) => {
      ctx.addIssue({ code: 'custom', path: [field], message })
    }
    if (input.subcategoryId !== null && input.categoryId === null) {
      add('subcategoryId', 'SubcategoryId requires CategoryId')
    }
    const variants = input.mugVariants
    if (variants.length > 0 && variants.filter((variant) => variant.isDefault).length !== 1) {
      add('mugVariants', 'Exactly one variant must be marked as default')
    }
    const ids = variants.flatMap((variant) => variant.id ?? [])
    if (new Set(ids).size !== ids.length) add('mugVariants', 'Variant ids must be unique')
    if (input.active) {
      if (input.mugDetails === null)
        add('active', 'An active article requires complete mug details')
      if (!variants.some((variant) => variant.active)) {
        add('active', 'An active article requires at least one active variant')
      }
      if (input.categoryId === null) add('active', 'An active article requires a category')
    }
  }) satisfies z.ZodType<MugArticleInput>

const MUG_RESPONSES = { notFound: 'Article not found', conflict: null }
const ORDER_RESPONSES = {
  notFound: 'Article not found',
  conflict: 'Article order changed concurrently, please retry',
}
const mugId = integerParam('id', 'Invalid article id')

/**
 * The admin mug routes. Only the reorder can answer `409`: mugs have no unique name, and a create
 * or an update cannot collide on a position under the type anchor. `/order` is registered before
 * `/:id`, so it is never read as an id.
 */
export function mugRoutes(mugs: MugService): Hono {
  const admin = new Hono()
  admin.use(requireAdmin)
  admin.get('/', async (c) => respondResult(c, await mugs.list(), MUG_RESPONSES))
  admin.post('/', jsonBody(mugArticleInput), async (c) => {
    const result = await mugs.create(c.req.valid('json'))
    if (result.kind !== 'ok') return respondFailure(c, result, MUG_RESPONSES)
    c.header('Location', `/api/admin/articles/mugs/${String(result.value.id)}`)
    return c.json(result.value, 201)
  })
  admin.put('/order', jsonBody(reorderInput), async (c) => {
    const { sourceId, targetId } = c.req.valid('json')
    return respondResult(c, await mugs.reorder(sourceId, targetId), ORDER_RESPONSES)
  })
  admin.post(
    '/variant-example-images',
    formBody,
    exampleImageUpload(mugs.storeExampleImage, MUG_RESPONSES),
  )
  admin.get('/:id', mugId, async (c) =>
    respondResult(c, await mugs.get(c.req.valid('param').id), MUG_RESPONSES),
  )
  admin.put('/:id', mugId, jsonBody(mugArticleInput), async (c) =>
    respondResult(
      c,
      await mugs.update(c.req.valid('param').id, c.req.valid('json')),
      MUG_RESPONSES,
    ),
  )
  admin.delete('/:id', mugId, async (c) => {
    const result = await mugs.delete(c.req.valid('param').id)
    if (result.kind !== 'ok') return respondFailure(c, result, MUG_RESPONSES)
    return c.body(null, 204)
  })

  const routes = new Hono()
  routes.route('/api/admin/articles/mugs', admin)
  return routes
}
