import { describe, expect, test } from 'bun:test'
import { countryInput } from '@/modules/country/routes'
import { validationFailed } from '@/platform/http/respond'

const errorsOf = (input: unknown) => {
  const result = countryInput.safeParse(input)
  return result.success ? {} : validationFailed(result.error).errors
}

describe('countryInput', () => {
  test('valid input is accepted and trimmed', () => {
    expect(
      countryInput.parse({
        name: ' Denmark ',
        countryCode: ' dk ',
        shippingCostCents: 990,
        freeShippingThresholdCents: 7500,
      }),
    ).toEqual({
      name: 'Denmark',
      countryCode: 'dk',
      shippingCostCents: 990,
      freeShippingThresholdCents: 7500,
    })
    expect(
      countryInput.parse({
        name: 'Germany',
        countryCode: 'DE',
        shippingCostCents: 0,
        freeShippingThresholdCents: null,
      }),
    ).toEqual({
      name: 'Germany',
      countryCode: 'DE',
      shippingCostCents: 0,
      freeShippingThresholdCents: null,
    })
    expect(
      countryInput.parse({ name: 'Austria', countryCode: 'AT', shippingCostCents: 890 }),
    ).toEqual({
      name: 'Austria',
      countryCode: 'AT',
      shippingCostCents: 890,
      freeShippingThresholdCents: null,
    })
  })

  test('invalid input reports every applicable field error with the documented message', () => {
    expect(errorsOf({})).toEqual({
      name: ['Name is required'],
      countryCode: ['Country code is required'],
      shippingCostCents: ['Shipping cost is required'],
    })
    expect(
      errorsOf({
        name: null,
        countryCode: 5,
        shippingCostCents: null,
        freeShippingThresholdCents: '5000',
      }),
    ).toEqual({
      name: ['Name is required'],
      countryCode: ['Country code is required'],
      shippingCostCents: ['Shipping cost is required'],
      freeShippingThresholdCents: ['Free shipping threshold must be a whole number of cents'],
    })
    expect(
      errorsOf({
        name: 'A'.repeat(256),
        countryCode: 'D1',
        shippingCostCents: -1,
        freeShippingThresholdCents: 0,
      }),
    ).toEqual({
      name: ['Name must be at most 255 characters'],
      countryCode: ['Country code must contain only letters'],
      shippingCostCents: ['Shipping cost must not be negative'],
      freeShippingThresholdCents: ['Free shipping threshold must be positive'],
    })
    expect(
      errorsOf({
        name: 'Germany',
        countryCode: 'DE',
        shippingCostCents: 4.9,
        freeShippingThresholdCents: 50.5,
      }),
    ).toEqual({
      shippingCostCents: ['Shipping cost must be a whole number of cents'],
      freeShippingThresholdCents: ['Free shipping threshold must be a whole number of cents'],
    })
    expect(
      errorsOf({
        name: 'Germany',
        countryCode: 'DE',
        shippingCostCents: 2_147_483_648,
        freeShippingThresholdCents: 2_147_483_648,
      }),
    ).toEqual({
      shippingCostCents: ['Shipping cost is too large'],
      freeShippingThresholdCents: ['Free shipping threshold is too large'],
    })
    expect(errorsOf({ name: 'Germany', countryCode: 'D', shippingCostCents: 490 })).toEqual({
      countryCode: ['Country code must be exactly 2 characters'],
    })
    expect(errorsOf({ name: 'Germany', countryCode: 'GER', shippingCostCents: 490 })).toEqual({
      countryCode: ['Country code must be exactly 2 characters'],
    })
    expect(errorsOf({ name: 'Germany', countryCode: '   ', shippingCostCents: 490 })).toEqual({
      countryCode: ['Country code is required'],
    })
    expect(
      errorsOf({ name: 'Germany', countryCode: 'DE', shippingCostCents: 490, ignored: true }),
    ).toEqual({
      ignored: ['Unknown property'],
    })
  })
})
