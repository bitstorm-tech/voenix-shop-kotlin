import { Hono } from 'hono'
import { z } from 'zod'
import { exampleImageUpload } from '@/modules/image'
import { requireAdmin } from '@/platform/auth/auth'
import {
  formBody,
  integerParam,
  jsonBody,
  respondFailure,
  respondResult,
} from '@/platform/http/respond'
import { optionalFilename, optionalText, reorderInput, requiredText } from '../routes'
import type { ArticleCategoryInput, ArticleSubcategoryInput, CategoryService } from './service'

/** The category create and update body. The position is the module's, never the client's. */
export const articleCategoryInput = z.strictObject({
  name: requiredText('Name', 200),
  description: optionalText('Description', 1000),
  active: z.boolean().default(true),
}) satisfies z.ZodType<ArticleCategoryInput>

/** The subcategory create and update body; it names its category on both sides of the contract. */
export const articleSubcategoryInput = z.strictObject({
  categoryId: z.int({ error: 'CategoryId is required' }).positive('CategoryId must be positive'),
  name: requiredText('Name', 200),
  description: optionalText('Description', 1000),
  exampleImageFilename: optionalFilename,
  active: z.boolean().default(true),
}) satisfies z.ZodType<ArticleSubcategoryInput>

/** Each route can produce one `409` meaning only, so the route's message is the meaning. */
const CATEGORY = {
  notFound: 'Article category not found',
  conflict: 'Article category name already exists',
}
const CATEGORY_DELETE = {
  ...CATEGORY,
  conflict: 'Article category is used by subcategories or articles and cannot be deleted',
}
const CATEGORY_ORDER = {
  ...CATEGORY,
  conflict: 'Article category order changed concurrently, please retry',
}
const SUBCATEGORY = {
  notFound: 'Article subcategory not found',
  conflict: 'Article subcategory name already exists in this article category',
}
const SUBCATEGORY_DELETE = {
  ...SUBCATEGORY,
  conflict: 'Article subcategory is used by articles and cannot be deleted',
}
const SUBCATEGORY_ORDER = {
  ...SUBCATEGORY,
  conflict: 'Article subcategory order changed concurrently, please retry',
}

const categoryId = integerParam('id', 'Invalid article category id')
const subcategoryId = integerParam('id', 'Invalid article subcategory id')

/** The admin category and subcategory routes; `/order` is registered before `/:id`. */
export function categoryRoutes(service: CategoryService): Hono {
  const categories = new Hono()
  categories.use(requireAdmin)
  categories.get('/', async (c) => c.json(await service.listCategories()))
  categories.post('/', jsonBody(articleCategoryInput), async (c) => {
    const result = await service.createCategory(c.req.valid('json'))
    if (result.kind !== 'ok') return respondFailure(c, result, CATEGORY)
    c.header('Location', `/api/admin/articles/categories/${String(result.value.id)}`)
    return c.json(result.value, 201)
  })
  categories.put('/order', jsonBody(reorderInput), async (c) => {
    const { sourceId, targetId } = c.req.valid('json')
    return respondResult(c, await service.reorderCategories(sourceId, targetId), CATEGORY_ORDER)
  })
  categories.get('/:id', categoryId, async (c) =>
    respondResult(c, await service.getCategory(c.req.valid('param').id), CATEGORY),
  )
  categories.put('/:id', categoryId, jsonBody(articleCategoryInput), async (c) =>
    respondResult(
      c,
      await service.updateCategory(c.req.valid('param').id, c.req.valid('json')),
      CATEGORY,
    ),
  )
  categories.delete('/:id', categoryId, async (c) => {
    const result = await service.deleteCategory(c.req.valid('param').id)
    if (result.kind !== 'ok') return respondFailure(c, result, CATEGORY_DELETE)
    return c.body(null, 204)
  })

  const subcategories = new Hono()
  subcategories.use(requireAdmin)
  subcategories.get('/', async (c) => c.json(await service.listSubcategories()))
  subcategories.post('/', jsonBody(articleSubcategoryInput), async (c) => {
    const result = await service.createSubcategory(c.req.valid('json'))
    if (result.kind !== 'ok') return respondFailure(c, result, SUBCATEGORY)
    c.header('Location', `/api/admin/articles/subcategories/${String(result.value.id)}`)
    return c.json(result.value, 201)
  })
  subcategories.post(
    '/example-images',
    formBody,
    exampleImageUpload(service.storeExampleImage, SUBCATEGORY),
  )
  subcategories.put('/order', jsonBody(reorderInput), async (c) => {
    const { sourceId, targetId } = c.req.valid('json')
    return respondResult(
      c,
      await service.reorderSubcategories(sourceId, targetId),
      SUBCATEGORY_ORDER,
    )
  })
  subcategories.get('/:id', subcategoryId, async (c) =>
    respondResult(c, await service.getSubcategory(c.req.valid('param').id), SUBCATEGORY),
  )
  subcategories.put('/:id', subcategoryId, jsonBody(articleSubcategoryInput), async (c) =>
    respondResult(
      c,
      await service.updateSubcategory(c.req.valid('param').id, c.req.valid('json')),
      SUBCATEGORY,
    ),
  )
  subcategories.delete('/:id', subcategoryId, async (c) => {
    const result = await service.deleteSubcategory(c.req.valid('param').id)
    if (result.kind !== 'ok') return respondFailure(c, result, SUBCATEGORY_DELETE)
    return c.body(null, 204)
  })

  const routes = new Hono()
  routes.route('/api/admin/articles/categories', categories)
  routes.route('/api/admin/articles/subcategories', subcategories)
  return routes
}
