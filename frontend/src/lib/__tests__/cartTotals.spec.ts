import { describe, expect, it } from 'vitest'
import { calculateCartTotals, type ShippingPolicy } from '@/lib/cartTotals'

const DE_POLICY: ShippingPolicy = { shippingCostCents: 490, freeShippingThresholdCents: 5000 }
const AT_NO_THRESHOLD: ShippingPolicy = {
  shippingCostCents: 1190,
  freeShippingThresholdCents: null,
}
const FREE_POLICY: ShippingPolicy = { shippingCostCents: 0, freeShippingThresholdCents: null }

describe('calculateCartTotals', () => {
  it('applies country shipping costs and respects optional free-shipping thresholds', () => {
    expect(calculateCartTotals(4999, null, DE_POLICY)).toEqual({
      subtotalCents: 4999,
      shippingCostCents: 490,
      discountCents: 0,
      totalCents: 5489,
    })
    expect(calculateCartTotals(5000, null, DE_POLICY)).toEqual({
      subtotalCents: 5000,
      shippingCostCents: 0,
      discountCents: 0,
      totalCents: 5000,
    })
    expect(calculateCartTotals(50_000, null, AT_NO_THRESHOLD)).toEqual({
      subtotalCents: 50_000,
      shippingCostCents: 1190,
      discountCents: 0,
      totalCents: 51_190,
    })
    expect(calculateCartTotals(0, null, AT_NO_THRESHOLD)).toEqual({
      subtotalCents: 0,
      shippingCostCents: 0,
      discountCents: 0,
      totalCents: 0,
    })
    expect(calculateCartTotals(1490, null, FREE_POLICY)).toEqual({
      subtotalCents: 1490,
      shippingCostCents: 0,
      discountCents: 0,
      totalCents: 1490,
    })
  })

  it('applies percentage and fixed coupons to subtotal plus shipping with half-up rounding', () => {
    // 3980 + 1190 = 5170; 10% = 517
    expect(
      calculateCartTotals(3980, { discountType: 'PERCENTAGE', discountValue: 10 }, AT_NO_THRESHOLD),
    ).toEqual({
      subtotalCents: 3980,
      shippingCostCents: 1190,
      discountCents: 517,
      totalCents: 4653,
    })

    // 1% of 5050 = 50.5 -> rounds half-up to 51
    expect(
      calculateCartTotals(5050, { discountType: 'PERCENTAGE', discountValue: 1 }, DE_POLICY),
    ).toMatchObject({ discountCents: 51 })

    // 12.5% of 5999 = 749.875 -> 750
    expect(
      calculateCartTotals(5999, { discountType: 'PERCENTAGE', discountValue: 12.5 }, DE_POLICY),
    ).toMatchObject({ discountCents: 750 })

    // Fixed coupon capped at subtotal + shipping
    expect(
      calculateCartTotals(
        1000,
        { discountType: 'FIXED_AMOUNT', discountValue: 5000 },
        AT_NO_THRESHOLD,
      ),
    ).toEqual({
      subtotalCents: 1000,
      shippingCostCents: 1190,
      discountCents: 2190,
      totalCents: 0,
    })

    // Percentage above 100 capped at 100
    expect(
      calculateCartTotals(1000, { discountType: 'PERCENTAGE', discountValue: 150 }, DE_POLICY),
    ).toEqual({
      subtotalCents: 1000,
      shippingCostCents: 490,
      discountCents: 1490,
      totalCents: 0,
    })
  })
})
