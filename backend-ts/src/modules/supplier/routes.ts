import { Hono } from 'hono'
import { z } from 'zod'
import { requireAdmin } from '@/platform/auth/auth'
import { integerParam, jsonBody, respondFailure, respondResult } from '@/platform/http/respond'
import type { SupplierInput, SupplierService } from './service'

/** Trimmed optional text; blank, `null`, or omitted is stored as `null`. */
const optionalText = (label: string, maximum = 255) =>
  z
    .string()
    .trim()
    .max(maximum, `${label} must be at most ${String(maximum)} characters`)
    .nullish()
    .transform((value) => (value === undefined || value === '' ? null : value))

/** One `@` with text on both sides and no whitespace; deliberately no stricter. */
const hasEmailShape = (email: string) => email === '' || /^[^@\s]+@[^@\s]+$/.test(email)

/**
 * The create and full-replacement body. `name` is required; every other field becomes the
 * submitted value or `null`, so `PUT` clears what it omits. Messages match the Kotlin backend.
 */
export const supplierInput = z.strictObject({
  name: z
    .string({ error: 'Name is required' })
    .trim()
    .min(1, 'Name is required')
    .max(255, 'Name must be at most 255 characters'),
  title: optionalText('Title'),
  firstName: optionalText('FirstName'),
  lastName: optionalText('LastName'),
  street: optionalText('Street'),
  houseNumber: optionalText('HouseNumber'),
  city: optionalText('City'),
  postalCode: optionalText('PostalCode', 20),
  countryId: z
    .int()
    .nullish()
    .transform((value) => value ?? null),
  phoneNumber1: optionalText('PhoneNumber1'),
  phoneNumber2: optionalText('PhoneNumber2'),
  phoneNumber3: optionalText('PhoneNumber3'),
  email: z
    .string()
    .trim()
    .max(255, { error: 'Email must be at most 255 characters', abort: true })
    .refine(hasEmailShape, 'Email must be a valid email address')
    .nullish()
    .transform((value) => (value === undefined || value === '' ? null : value)),
  website: optionalText('Website'),
}) satisfies z.ZodType<SupplierInput>

const SUPPLIER_RESPONSES = {
  notFound: 'Supplier not found',
  conflict: 'Supplier is in use and cannot be deleted',
}
const supplierId = integerParam('id', 'Invalid supplier id')

export function supplierRoutes(suppliers: SupplierService): Hono {
  const admin = new Hono()
  admin.use(requireAdmin)
  admin.get('/', async (c) => c.json(await suppliers.list()))
  admin.post('/', jsonBody(supplierInput), async (c) => {
    const result = await suppliers.create(c.req.valid('json'))
    if (result.kind !== 'ok') return respondFailure(c, result, SUPPLIER_RESPONSES)
    c.header('Location', `/api/admin/suppliers/${String(result.value.id)}`)
    return c.json(result.value, 201)
  })
  admin.get('/:id', supplierId, async (c) =>
    respondResult(c, await suppliers.get(c.req.valid('param').id), SUPPLIER_RESPONSES),
  )
  admin.put('/:id', supplierId, jsonBody(supplierInput), async (c) =>
    respondResult(
      c,
      await suppliers.update(c.req.valid('param').id, c.req.valid('json')),
      SUPPLIER_RESPONSES,
    ),
  )
  admin.delete('/:id', supplierId, async (c) => {
    const result = await suppliers.delete(c.req.valid('param').id)
    if (result.kind !== 'ok') return respondFailure(c, result, SUPPLIER_RESPONSES)
    return c.body(null, 204)
  })

  const routes = new Hono()
  routes.route('/api/admin/suppliers', admin)
  return routes
}
