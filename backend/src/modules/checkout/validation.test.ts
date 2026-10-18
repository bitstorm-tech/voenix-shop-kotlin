import { describe, expect, test } from 'bun:test'
import { validationFailed } from '@/platform/http/respond'
import { checkoutInput } from './routes'
import { checkoutBody } from './test-fixtures'

/** The complete field-rule matrix of the one request body the checkout accepts. */

const address = (overrides: Record<string, unknown> = {}) => ({
  ...checkoutBody().shippingAddress,
  ...overrides,
})
const shipping = (overrides: Record<string, unknown> = {}) => ({
  shippingAddress: address(overrides),
  billingAddress: null,
})

/** The field errors of a rejected body, or `{}` when it is accepted. */
function errorsOf(body: unknown): Record<string, string[]> {
  const result = checkoutInput.safeParse(body)
  return result.success ? {} : validationFailed(result.error).errors
}
function parsed(body: unknown) {
  const result = checkoutInput.safeParse(body)
  if (!result.success) throw new Error(JSON.stringify(validationFailed(result.error).errors))
  return result.data
}

describe('checkoutInput', () => {
  test('the exact request the frontend sends today is valid', () => {
    expect(errorsOf(checkoutBody())).toEqual({})
  })

  test('a billing address is optional, validated when it is there, and its contact fields are ignored', () => {
    expect(errorsOf({ shippingAddress: address() })).toEqual({})
    expect(parsed({ shippingAddress: address() }).billingAddress).toBeNull()
    expect(errorsOf({ ...shipping(), billingAddress: address({ city: ' ' }) })).toEqual({
      'billingAddress.city': ['Must not be blank'],
    })
    // The Vue store sends `email` and `phone` on the billing address too; they are accepted and
    // ignored, so a separate billing address is no "unknown property".
    expect(errorsOf({ ...shipping(), billingAddress: address({ phone: '123' }) })).toEqual({})
  })

  test('a request without a shipping address reports that one field', () => {
    expect(errorsOf({})).toEqual({ shippingAddress: ['Shipping address is required'] })
    expect(errorsOf({ shippingAddress: null })).toEqual({
      shippingAddress: ['Shipping address is required'],
    })
  })

  test('an empty shipping address reports every required field once', () => {
    expect(Object.keys(errorsOf({ shippingAddress: {} }))).toEqual([
      'shippingAddress.firstName',
      'shippingAddress.lastName',
      'shippingAddress.street',
      'shippingAddress.houseNumber',
      'shippingAddress.postalCode',
      'shippingAddress.city',
      'shippingAddress.country',
      'shippingAddress.email',
    ])
  })

  test('a whitespace-only postal field is as blank as a missing one', () => {
    expect(errorsOf(shipping({ street: '   ' }))).toEqual({
      'shippingAddress.street': ['Must not be blank'],
    })
  })

  test('every postal field has the length its column has', () => {
    const errors = errorsOf(
      shipping({
        firstName: 'a'.repeat(101),
        lastName: 'a'.repeat(101),
        street: 'a'.repeat(201),
        houseNumber: 'a'.repeat(21),
        postalCode: 'a'.repeat(11),
        city: 'a'.repeat(101),
      }),
    )
    expect(Object.keys(errors)).toEqual([
      'shippingAddress.firstName',
      'shippingAddress.lastName',
      'shippingAddress.street',
      'shippingAddress.houseNumber',
      'shippingAddress.postalCode',
      'shippingAddress.city',
    ])
    expect(errors['shippingAddress.city']).toEqual(['Must be at most 100 characters'])
  })

  test('the country is checked for its shape, upper-cased, and never checked against a list', () => {
    for (const code of ['D', 'DEU', 'D1', '  ']) {
      expect(errorsOf(shipping({ country: code }))).toEqual({
        'shippingAddress.country': ['Must be a two-letter code'],
      })
    }
    for (const code of ['DE', 'at', 'ZZ']) {
      expect(parsed(shipping({ country: code })).shippingAddress.country).toBe(code.toUpperCase())
    }
  })

  test('the email is required, bounded, and has to look like an address', () => {
    expect(errorsOf(shipping({ email: '   ' }))).toEqual({
      'shippingAddress.email': ['Email is required'],
    })
    expect(errorsOf(shipping({ email: 'ada@example' }))).toEqual({
      'shippingAddress.email': ['Must be a valid email address'],
    })
    expect(errorsOf(shipping({ email: `${'a'.repeat(250)}@example.org` }))).toEqual({
      'shippingAddress.email': ['Must be at most 255 characters'],
    })
    expect(parsed(shipping({ email: '  ada@example.org ' })).shippingAddress.email).toBe(
      'ada@example.org',
    )
  })

  test('a blank or absent phone is no phone at all, a given one is trimmed and bounded', () => {
    for (const phone of [undefined, null, '', '   ']) {
      expect(parsed(shipping({ phone })).shippingAddress.phone).toBeNull()
    }
    expect(parsed(shipping({ phone: '  +49 89 1234 ' })).shippingAddress.phone).toBe('+49 89 1234')
    expect(errorsOf(shipping({ phone: '1'.repeat(51) }))).toEqual({
      'shippingAddress.phone': ['Must be at most 50 characters'],
    })
  })

  test('an unknown property is refused', () => {
    expect(errorsOf({ ...shipping(), promotionCode: 'SAVE10' })).toEqual({
      promotionCode: ['Unknown property'],
    })
    expect(errorsOf(shipping({ company: 'Analytical Engines' }))).toEqual({
      'shippingAddress.company': ['Unknown property'],
    })
  })
})
