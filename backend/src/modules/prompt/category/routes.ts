import { Hono } from 'hono'
import { z } from 'zod'
import {
  integerParam,
  jsonBody,
  respondFailure,
  respondResult,
  type FailureResponses,
} from '@/platform/http/respond'
import { optionalText, reorderInput, requiredName } from '../fields'
import type { CategoryValues, PromptCategoryRepository, SubcategoryValues } from './repository'

/** The create and update body of a category; `active` defaults to `true`. */
export const categoryInput = z.strictObject({
  name: requiredName(200),
  active: z.boolean({ error: 'Active must be a boolean' }).default(true),
}) satisfies z.ZodType<CategoryValues>

/** The create and update body of a subcategory, including the category it belongs to. */
export const subcategoryInput = z.strictObject({
  categoryId: z.int({ error: 'CategoryId is required' }).positive('CategoryId must be positive'),
  name: requiredName(200),
  description: optionalText('Description', 1000),
  active: z.boolean({ error: 'Active must be a boolean' }).default(true),
}) satisfies z.ZodType<SubcategoryValues>

/** Each route answers `409` for one reason only, so the message is stable per route. */
const responses = (subject: string, nameConflict: string, inUse: string) => {
  const write: FailureResponses = { notFound: `${subject} not found`, conflict: nameConflict }
  return {
    write,
    delete: { ...write, conflict: inUse },
    reorder: { ...write, conflict: `${subject} order changed concurrently, please retry` },
  }
}

const CATEGORY_PATH = '/api/admin/prompts/categories'
const CATEGORY = responses(
  'Prompt category',
  'Prompt category name already exists',
  'Prompt category is used by subcategories or prompts and cannot be deleted',
)
const categoryId = integerParam('id', 'Invalid prompt category id')

const SUBCATEGORY_PATH = '/api/admin/prompts/subcategories'
const SUBCATEGORY = responses(
  'Prompt subcategory',
  'Prompt subcategory name already exists in this prompt category',
  'Prompt subcategory is used by prompts and cannot be deleted',
)
const subcategoryId = integerParam('id', 'Invalid prompt subcategory id')

/** `/api/admin/prompts/categories`, mounted below the module's admin protection. */
export function categoryRoutes(categories: PromptCategoryRepository): Hono {
  const routes = new Hono()
  routes.get('/', async (c) => c.json(await categories.listCategories()))
  routes.post('/', jsonBody(categoryInput), async (c) => {
    const result = await categories.createCategory(c.req.valid('json'))
    if (result.kind !== 'ok') return respondFailure(c, result, CATEGORY.write)
    c.header('Location', `${CATEGORY_PATH}/${String(result.value.id)}`)
    return c.json(result.value, 201)
  })
  routes.put('/order', jsonBody(reorderInput), async (c) => {
    const { sourceId, targetId } = c.req.valid('json')
    return respondResult(
      c,
      await categories.reorderCategories(sourceId, targetId),
      CATEGORY.reorder,
    )
  })
  routes.get('/:id', categoryId, async (c) =>
    respondResult(c, await categories.getCategory(c.req.valid('param').id), CATEGORY.write),
  )
  routes.put('/:id', categoryId, jsonBody(categoryInput), async (c) =>
    respondResult(
      c,
      await categories.updateCategory(c.req.valid('param').id, c.req.valid('json')),
      CATEGORY.write,
    ),
  )
  routes.delete('/:id', categoryId, async (c) => {
    const result = await categories.deleteCategory(c.req.valid('param').id)
    if (result.kind !== 'ok') return respondFailure(c, result, CATEGORY.delete)
    return c.body(null, 204)
  })
  return routes
}

/** `/api/admin/prompts/subcategories`, mounted below the module's admin protection. */
export function subcategoryRoutes(categories: PromptCategoryRepository): Hono {
  const routes = new Hono()
  routes.get('/', async (c) => c.json(await categories.listSubcategories()))
  routes.post('/', jsonBody(subcategoryInput), async (c) => {
    const result = await categories.createSubcategory(c.req.valid('json'))
    if (result.kind !== 'ok') return respondFailure(c, result, SUBCATEGORY.write)
    c.header('Location', `${SUBCATEGORY_PATH}/${String(result.value.id)}`)
    return c.json(result.value, 201)
  })
  routes.put('/order', jsonBody(reorderInput), async (c) => {
    const { sourceId, targetId } = c.req.valid('json')
    return respondResult(
      c,
      await categories.reorderSubcategories(sourceId, targetId),
      SUBCATEGORY.reorder,
    )
  })
  routes.get('/:id', subcategoryId, async (c) =>
    respondResult(c, await categories.getSubcategory(c.req.valid('param').id), SUBCATEGORY.write),
  )
  routes.put('/:id', subcategoryId, jsonBody(subcategoryInput), async (c) =>
    respondResult(
      c,
      await categories.updateSubcategory(c.req.valid('param').id, c.req.valid('json')),
      SUBCATEGORY.write,
    ),
  )
  routes.delete('/:id', subcategoryId, async (c) => {
    const result = await categories.deleteSubcategory(c.req.valid('param').id)
    if (result.kind !== 'ok') return respondFailure(c, result, SUBCATEGORY.delete)
    return c.body(null, 204)
  })
  return routes
}
