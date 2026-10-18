import { describe, expect, test } from 'bun:test'
import { promotionInput } from '@/modules/promotion/routes'
import { validationFailed } from '@/platform/http/respond'

const errorsOf = (input: unknown) => {
  const result = promotionInput.safeParse(input)
  return result.success ? {} : validationFailed(result.error).errors
}

const percentage = (fields: Record<string, unknown> = {}) => ({
  name: 'Winter sale',
  couponCode: 'Winter10',
  discountType: 'PERCENTAGE',
  discountValue: 10,
  ...fields,
})
const fixedAmount = (discountValue: number) =>
  percentage({ discountType: 'FIXED_AMOUNT', discountValue })

describe('promotionInput', () => {
  test('valid input is accepted, trimmed, parsed, and completed with nulls and isActive false', () => {
    expect(
      promotionInput.parse({
        name: ' Winter sale ',
        couponCode: ' Winter10 ',
        discountType: 'PERCENTAGE',
        discountValue: 100,
        startsAt: '2026-01-01T00:00:00Z',
        endsAt: '2026-01-01T01:00:00+01:00',
        usageLimitTotal: 1,
        usageLimitPerUser: 1,
        isActive: true,
      }),
    ).toEqual({
      name: 'Winter sale',
      couponCode: 'Winter10',
      discountType: 'PERCENTAGE',
      discountValue: 100,
      startsAt: new Date('2026-01-01T00:00:00Z'),
      endsAt: new Date('2026-01-01T00:00:00Z'),
      usageLimitTotal: 1,
      usageLimitPerUser: 1,
      isActive: true,
    })
    expect(
      promotionInput.parse({
        name: 'Autumn sale',
        couponCode: 'Autumn5',
        discountType: 'FIXED_AMOUNT',
        discountValue: 500,
        startsAt: null,
      }),
    ).toEqual({
      name: 'Autumn sale',
      couponCode: 'Autumn5',
      discountType: 'FIXED_AMOUNT',
      discountValue: 500,
      startsAt: null,
      endsAt: null,
      usageLimitTotal: null,
      usageLimitPerUser: null,
      isActive: false,
    })
    expect(
      errorsOf({
        name: 'n'.repeat(255),
        couponCode: 'c'.repeat(64),
        discountType: 'FIXED_AMOUNT',
        discountValue: 9_999_999_999,
        startsAt: '2026-01-01T00:00:00.123+01:00',
      }),
    ).toEqual({})
    expect(errorsOf(percentage({ discountValue: 12.34 }))).toEqual({})
  })

  test('invalid input reports every applicable field error with the documented message', () => {
    expect(errorsOf({})).toEqual({
      name: ['Name is required'],
      couponCode: ['CouponCode is required'],
      discountType: ['DiscountType is required'],
      discountValue: ['DiscountValue is required'],
    })
    expect(errorsOf(percentage({ name: '   ', couponCode: null }))).toEqual({
      name: ['Name is required'],
      couponCode: ['CouponCode is required'],
    })
    expect(
      errorsOf(percentage({ name: ` ${'n'.repeat(256)} `, couponCode: ` ${'c'.repeat(65)} ` })),
    ).toEqual({
      name: ['Name must be at most 255 characters'],
      couponCode: ['CouponCode must be at most 64 characters'],
    })
    expect(errorsOf(percentage({ discountType: 'INVALID' }))).toEqual({
      discountType: ['DiscountType must be PERCENTAGE or FIXED_AMOUNT'],
    })
    for (const value of [0, -10]) {
      expect(errorsOf(percentage({ discountValue: value }))).toEqual({
        discountValue: ['DiscountValue must be positive'],
      })
    }
    expect(errorsOf(fixedAmount(-500))).toEqual({
      discountValue: ['DiscountValue must be positive'],
    })
    expect(errorsOf(percentage({ discountValue: 100.01 }))).toEqual({
      discountValue: ['DiscountValue must be at most 100 for percentage promotions'],
    })
    expect(errorsOf(percentage({ discountValue: 10.005 }))).toEqual({
      discountValue: ['DiscountValue must have at most 2 decimal places for percentage promotions'],
    })
    expect(errorsOf(fixedAmount(5.5))).toEqual({
      discountValue: ['DiscountValue must be whole cents for fixed amount promotions'],
    })
    expect(errorsOf(fixedAmount(10_000_000_000))).toEqual({
      discountValue: ['DiscountValue must be at most 9999999999 for fixed amount promotions'],
    })
    expect(
      errorsOf(percentage({ startsAt: 'not-a-timestamp', endsAt: '2026-13-01T00:00:00' })),
    ).toEqual({
      startsAt: ['StartsAt must be an ISO-8601 timestamp'],
      endsAt: ['EndsAt must be an ISO-8601 timestamp'],
    })
    expect(
      errorsOf(percentage({ startsAt: '2026-03-01T00:00:00Z', endsAt: '2026-01-01T00:00:00Z' })),
    ).toEqual({
      startsAt: ['StartsAt must not be after EndsAt'],
      endsAt: ['StartsAt must not be after EndsAt'],
    })
    expect(errorsOf(percentage({ usageLimitTotal: 0, usageLimitPerUser: -1 }))).toEqual({
      usageLimitTotal: ['UsageLimitTotal must be positive'],
      usageLimitPerUser: ['UsageLimitPerUser must be positive'],
    })
  })

  test('a cross-field rule waits for its own fields, and types the Kotlin parser rejected get a field error', () => {
    // An invalid discount type leaves the value unjudged; another field's error does not.
    expect(errorsOf(percentage({ discountType: 'OTHER', discountValue: 1000 }))).toEqual({
      discountType: ['DiscountType must be PERCENTAGE or FIXED_AMOUNT'],
    })
    expect(errorsOf(percentage({ name: '', discountValue: 1000 }))).toEqual({
      name: ['Name is required'],
      discountValue: ['DiscountValue must be at most 100 for percentage promotions'],
    })
    expect(
      errorsOf(
        percentage({
          discountValue: '10',
          startsAt: 5,
          usageLimitTotal: 1.5,
          usageLimitPerUser: 2_147_483_648,
          isActive: null,
          ignored: true,
        }),
      ),
    ).toEqual({
      discountValue: ['DiscountValue is required'],
      startsAt: ['StartsAt must be an ISO-8601 timestamp'],
      usageLimitTotal: ['UsageLimitTotal must be an integer'],
      usageLimitPerUser: ['UsageLimitPerUser must be an integer'],
      isActive: ['IsActive must be a boolean'],
      ignored: ['Unknown property'],
    })
  })
})
