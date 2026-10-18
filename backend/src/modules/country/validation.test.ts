import { describe, expect, test } from 'bun:test'
import { countryInput } from '@/modules/country/routes'
import { validationFailed } from '@/platform/http/respond'

const errorsOf = (input: unknown) => {
  const result = countryInput.safeParse(input)
  return result.success ? {} : validationFailed(result.error).errors
}

describe('countryInput', () => {
  test('valid input is accepted and trimmed', () => {
    expect(countryInput.parse({ name: ' Denmark ', countryCode: ' dk ' })).toEqual({
      name: 'Denmark',
      countryCode: 'dk',
    })
  })

  test('invalid input reports every applicable field error with the documented message', () => {
    expect(errorsOf({})).toEqual({
      name: ['Name is required'],
      countryCode: ['Country code is required'],
    })
    expect(errorsOf({ name: null, countryCode: 5 })).toEqual({
      name: ['Name is required'],
      countryCode: ['Country code is required'],
    })
    expect(errorsOf({ name: 'A'.repeat(256), countryCode: 'D1' })).toEqual({
      name: ['Name must be at most 255 characters'],
      countryCode: ['Country code must contain only letters'],
    })
    expect(errorsOf({ name: 'Germany', countryCode: 'D' })).toEqual({
      countryCode: ['Country code must be exactly 2 characters'],
    })
    expect(errorsOf({ name: 'Germany', countryCode: 'GER' })).toEqual({
      countryCode: ['Country code must be exactly 2 characters'],
    })
    expect(errorsOf({ name: 'Germany', countryCode: '   ' })).toEqual({
      countryCode: ['Country code is required'],
    })
    expect(errorsOf({ name: 'Germany', countryCode: 'DE', ignored: true })).toEqual({
      ignored: ['Unknown property'],
    })
  })
})
