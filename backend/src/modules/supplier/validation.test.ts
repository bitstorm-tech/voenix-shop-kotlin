import { describe, expect, test } from 'bun:test'
import { supplierInput } from '@/modules/supplier/routes'
import { validationFailed } from '@/platform/http/respond'

const errorsOf = (input: unknown) => {
  const result = supplierInput.safeParse(input)
  return result.success ? {} : validationFailed(result.error).errors
}

describe('supplierInput', () => {
  test('valid input is trimmed, and blank or omitted optional fields become null', () => {
    expect(
      supplierInput.parse({
        name: ' Acme ',
        title: ' Dr. ',
        firstName: ' ',
        lastName: null,
        city: ' Berlin ',
        postalCode: ' 10115 ',
        countryId: 1,
        phoneNumber1: ' +49 30 123456 ',
        email: ' info@example.test ',
        website: ' https://example.test ',
      }),
    ).toEqual({
      name: 'Acme',
      title: 'Dr.',
      firstName: null,
      lastName: null,
      street: null,
      houseNumber: null,
      city: 'Berlin',
      postalCode: '10115',
      countryId: 1,
      phoneNumber1: '+49 30 123456',
      phoneNumber2: null,
      phoneNumber3: null,
      email: 'info@example.test',
      website: 'https://example.test',
    })
    expect(supplierInput.parse({ name: 'Acme', email: ' ' }).email).toBeNull()
  })

  test('invalid input reports every applicable field error with the documented message', () => {
    expect(errorsOf({})).toEqual({ name: ['Name is required'] })
    expect(errorsOf({ name: '   ' })).toEqual({ name: ['Name is required'] })
    expect(errorsOf({ name: null })).toEqual({ name: ['Name is required'] })
    const tooLong = 'x'.repeat(256)
    expect(
      errorsOf({
        name: tooLong,
        title: tooLong,
        firstName: tooLong,
        lastName: tooLong,
        street: tooLong,
        houseNumber: tooLong,
        city: tooLong,
        postalCode: '1'.repeat(21),
        phoneNumber1: tooLong,
        phoneNumber2: tooLong,
        phoneNumber3: tooLong,
        email: tooLong,
        website: tooLong,
      }),
    ).toEqual({
      name: ['Name must be at most 255 characters'],
      title: ['Title must be at most 255 characters'],
      firstName: ['FirstName must be at most 255 characters'],
      lastName: ['LastName must be at most 255 characters'],
      street: ['Street must be at most 255 characters'],
      houseNumber: ['HouseNumber must be at most 255 characters'],
      city: ['City must be at most 255 characters'],
      postalCode: ['PostalCode must be at most 20 characters'],
      phoneNumber1: ['PhoneNumber1 must be at most 255 characters'],
      phoneNumber2: ['PhoneNumber2 must be at most 255 characters'],
      phoneNumber3: ['PhoneNumber3 must be at most 255 characters'],
      email: ['Email must be at most 255 characters'],
      website: ['Website must be at most 255 characters'],
    })
    for (const email of ['not-an-email', '@example.test', 'ada@', 'a@b@c', 'ada lovelace@x.test'])
      expect(errorsOf({ name: 'Acme', email })).toEqual({
        email: ['Email must be a valid email address'],
      })
    expect(errorsOf({ name: 'Acme', ignored: true })).toEqual({ ignored: ['Unknown property'] })
  })
})
