import { Hono } from 'hono'
import { getCountryCallingCode, isSupportedCountry } from 'libphonenumber-js'
import { z } from 'zod'
import { requireAdmin } from '@/platform/auth/auth'
import { integerParam, jsonBody, respondFailure, respondResult } from '@/platform/http/respond'
import type { Country, CountryInput, CountryRepository } from './repository'

/** The create and update body. A missing, `null`, or non-string field answers its required message. */
export const countryInput = z.strictObject({
  name: z
    .string({ error: 'Name is required' })
    .trim()
    .min(1, 'Name is required')
    .max(255, 'Name must be at most 255 characters'),
  countryCode: z
    .string({ error: 'Country code is required' })
    .trim()
    .min(1, { error: 'Country code is required', abort: true })
    .length(2, { error: 'Country code must be exactly 2 characters', abort: true })
    .regex(/^[A-Za-z]+$/, 'Country code must contain only letters'),
}) satisfies z.ZodType<CountryInput>

const COUNTRY_RESPONSES = {
  notFound: 'Country not found',
  conflict: 'Country name or code already exists',
}
const countryId = integerParam('id', 'Invalid country id')

/** The public response: no id, and the dial code when the region is known. */
function toPublicCountry({ name, countryCode }: Country) {
  return {
    name,
    countryCode,
    dialCode: isSupportedCountry(countryCode) ? `+${getCountryCallingCode(countryCode)}` : null,
  }
}

export function countryRoutes(countries: CountryRepository): Hono {
  const admin = new Hono()
  admin.use(requireAdmin)
  admin.get('/', async (c) => c.json(await countries.list()))
  admin.post('/', jsonBody(countryInput), async (c) => {
    const result = await countries.create(c.req.valid('json'))
    if (result.kind !== 'ok') return respondFailure(c, result, COUNTRY_RESPONSES)
    c.header('Location', `/api/admin/countries/${String(result.value.id)}`)
    return c.json(result.value, 201)
  })
  admin.get('/:id', countryId, async (c) =>
    respondResult(c, await countries.get(c.req.valid('param').id), COUNTRY_RESPONSES),
  )
  admin.put('/:id', countryId, jsonBody(countryInput), async (c) =>
    respondResult(
      c,
      await countries.update(c.req.valid('param').id, c.req.valid('json')),
      COUNTRY_RESPONSES,
    ),
  )
  admin.delete('/:id', countryId, async (c) => {
    const result = await countries.delete(c.req.valid('param').id)
    if (result.kind !== 'ok') return respondFailure(c, result, COUNTRY_RESPONSES)
    return c.body(null, 204)
  })

  const routes = new Hono()
  routes.get('/api/countries', async (c) => c.json((await countries.list()).map(toPublicCountry)))
  routes.route('/api/admin/countries', admin)
  return routes
}
