import { describe, expect, test } from 'bun:test'
import type { z } from 'zod'
import {
  accountEmailInput,
  changeEmailInput,
  changePasswordInput,
  confirmChangeEmailInput,
  confirmEmailInput,
  loginInput,
  profileInput,
  registerInput,
  resetPasswordInput,
  supplierLoginInput,
  supplierLoginQuery,
} from '@/modules/account/routes'
import { validationFailed } from '@/platform/http/respond'

const errorsOf = (schema: z.ZodType, input: unknown) => {
  const result = schema.safeParse(input)
  return result.success ? {} : validationFailed(result.error).errors
}

describe('registerInput', () => {
  test('checks the e-mail format and the password rule, and trims the e-mail', () => {
    expect(errorsOf(registerInput, {})).toEqual({
      email: ['Email is required'],
      password: ['Password is required'],
    })
    for (const email of [
      'no-at-sign',
      'two@@signs',
      'two@at@signs',
      '@missing-local',
      'missing-domain@',
      'white space@example.com',
      `${'a'.repeat(250)}@example.com`,
    ]) {
      expect(errorsOf(registerInput, { email, password: 'password-1' })).toEqual({
        email: ['Invalid email format'],
      })
    }
    expect(errorsOf(registerInput, { email: '   ', password: '1234567' })).toEqual({
      email: ['Email is required'],
      password: ['Password must be at least 8 characters'],
    })
    expect(registerInput.parse({ email: ' user@example.com ', password: '12345678' })).toEqual({
      email: 'user@example.com',
      password: '12345678',
    })
    expect(errorsOf(registerInput, { email: 'a@b', password: '12345678', x: 1 })).toEqual({
      x: ['Unknown property'],
    })
  })
})

describe('loginInput', () => {
  test('checks the e-mail but never the password shape', () => {
    expect(errorsOf(loginInput, { email: '', password: '' })).toEqual({
      email: ['Email is required'],
      password: ['Password is required'],
    })
    expect(errorsOf(loginInput, { email: 'not-an-email', password: 'x' })).toEqual({
      email: ['Invalid email format'],
    })
    expect(errorsOf(loginInput, { email: 'user@example.com', password: 'x' })).toEqual({})
  })
})

describe('the link bodies', () => {
  test('confirmEmailInput requires the user id and a non-blank token', () => {
    expect(errorsOf(confirmEmailInput, {})).toEqual({
      userId: ['User id is required'],
      token: ['Token is required'],
    })
    expect(errorsOf(confirmEmailInput, { userId: 1, token: ' ' })).toEqual({
      token: ['Token is required'],
    })
    expect(errorsOf(confirmEmailInput, { userId: 1, token: 'abc' })).toEqual({})
  })

  test('resetPasswordInput checks e-mail, token, and the password rule', () => {
    expect(errorsOf(resetPasswordInput, {})).toEqual({
      email: ['Email is required'],
      token: ['Token is required'],
      newPassword: ['Password is required'],
    })
    expect(
      errorsOf(resetPasswordInput, {
        email: 'user@example.com',
        token: 'token',
        newPassword: '1234567',
      }),
    ).toEqual({ newPassword: ['Password must be at least 8 characters'] })
  })

  test('confirmChangeEmailInput requires the user id, the new e-mail, and the token', () => {
    expect(errorsOf(confirmChangeEmailInput, {})).toEqual({
      userId: ['User id is required'],
      newEmail: ['Email is required'],
      token: ['Token is required'],
    })
    expect(
      errorsOf(confirmChangeEmailInput, { userId: 1, newEmail: 'new@example.com', token: 't' }),
    ).toEqual({})
  })
})

describe('the e-mail and password changes', () => {
  test('accountEmailInput is the e-mail alone', () => {
    expect(errorsOf(accountEmailInput, {})).toEqual({ email: ['Email is required'] })
    expect(errorsOf(accountEmailInput, { email: 'nope' })).toEqual({
      email: ['Invalid email format'],
    })
  })

  test('changeEmailInput requires the new e-mail and the current password', () => {
    expect(errorsOf(changeEmailInput, {})).toEqual({
      newEmail: ['Email is required'],
      currentPassword: ['Current password is required'],
    })
    expect(errorsOf(changeEmailInput, { newEmail: 'nope', currentPassword: 'x' })).toEqual({
      newEmail: ['Invalid email format'],
    })
  })

  test('changePasswordInput requires the current password and checks the new one', () => {
    expect(errorsOf(changePasswordInput, {})).toEqual({
      currentPassword: ['Current password is required'],
      newPassword: ['Password is required'],
    })
    expect(errorsOf(changePasswordInput, { currentPassword: 'x', newPassword: '1234567' })).toEqual(
      { newPassword: ['Password must be at least 8 characters'] },
    )
  })
})

describe('profileInput', () => {
  test('requires a shipping address and applies the field rules to both addresses', () => {
    expect(errorsOf(profileInput, { hasSeparateBillingAddress: false })).toEqual({
      shippingAddress: ['Shipping address is required'],
    })
    expect(
      errorsOf(profileInput, {
        shippingAddress: {
          firstName: 'a'.repeat(101),
          lastName: 'a'.repeat(101),
          street: 'a'.repeat(201),
          houseNumber: 'a'.repeat(21),
          postalCode: 'a'.repeat(11),
          city: 'a'.repeat(101),
        },
      }),
    ).toEqual({
      'shippingAddress.firstName': ['Must be at most 100 characters'],
      'shippingAddress.lastName': ['Must be at most 100 characters'],
      'shippingAddress.street': ['Must be at most 200 characters'],
      'shippingAddress.houseNumber': ['Must be at most 20 characters'],
      'shippingAddress.postalCode': ['Must be at most 10 characters'],
      'shippingAddress.city': ['Must be at most 100 characters'],
    })
    for (const country of ['DEU', 'D', 'D1', '12']) {
      expect(errorsOf(profileInput, { shippingAddress: { country } })).toEqual({
        'shippingAddress.country': ['Country must be a two-letter code'],
      })
    }
    for (const phone of ['abc', '+49a', '12#34']) {
      expect(errorsOf(profileInput, { shippingAddress: { phone } })).toEqual({
        'shippingAddress.phone': ['Phone is not a valid phone number'],
      })
    }
    expect(
      errorsOf(profileInput, {
        shippingAddress: { firstName: 'Erika' },
        hasSeparateBillingAddress: true,
        billingAddress: { country: 'DEU' },
      }),
    ).toEqual({ 'billingAddress.country': ['Country must be a two-letter code'] })
  })

  test('normalizes after validation: trimmed fields, blanks to null, an empty address to null', () => {
    expect(
      profileInput.parse({
        shippingAddress: {
          firstName: ' Erika ',
          country: ' DE ',
          phone: '+49 (0) 30 123-456/78',
          city: '   ',
        },
        billingAddress: { firstName: ' ' },
      }),
    ).toEqual({
      shippingAddress: {
        firstName: 'Erika',
        lastName: null,
        street: null,
        houseNumber: null,
        postalCode: null,
        city: null,
        country: 'DE',
        phone: '+49 (0) 30 123-456/78',
      },
      hasSeparateBillingAddress: false,
      billingAddress: null,
    })
  })
})

describe('supplier login bodies', () => {
  test('supplierLoginInput checks the e-mail and the shape of the supplier id', () => {
    expect(errorsOf(supplierLoginInput, { supplierId: 0, email: 'not-an-email' })).toEqual({
      supplierId: ['Supplier id must be positive'],
      email: ['Invalid email format'],
    })
    expect(errorsOf(supplierLoginInput, { email: 'logistik@lieferant.example' })).toEqual({
      supplierId: ['Supplier id is required'],
    })
  })

  test('supplierLoginQuery requires one positive supplier id', () => {
    for (const query of [{}, { supplierId: '' }, { supplierId: 'abc' }, { supplierId: '0' }]) {
      expect(errorsOf(supplierLoginQuery, query)).toEqual({
        supplierId: ['A positive supplier id is required'],
      })
    }
    expect(errorsOf(supplierLoginQuery, { supplierId: '-1' })).toEqual({
      supplierId: ['A positive supplier id is required'],
    })
    expect(supplierLoginQuery.parse({ supplierId: '3' })).toEqual({ supplierId: 3 })
  })
})
