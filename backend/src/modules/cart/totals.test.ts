import { describe, expect, test } from 'bun:test'
import type { Discount } from '@/modules/promotion'
import { cartTotals, NO_SHIPPING_POLICY, type ShippingPolicy } from './totals'

const DE_SHIPPING: ShippingPolicy = { shippingCostCents: 490, freeShippingThresholdCents: 5_000 }
const EU_NO_THRESHOLD: ShippingPolicy = {
  shippingCostCents: 1_190,
  freeShippingThresholdCents: null,
}

const line = (priceCents: number, promptPriceCents = 0, quantity = 1) => ({
  priceCents,
  promptPriceCents,
  quantity,
})
const percentage = (discountValue: number): Discount => ({
  discountType: 'PERCENTAGE',
  discountValue,
})
const fixed = (discountValue: number): Discount => ({ discountType: 'FIXED_AMOUNT', discountValue })
/** The discount of one line of `cents` (no prompt) with `discount` under DE shipping. */
const discountOn = (cents: number, discount: Discount, policy: ShippingPolicy = DE_SHIPPING) =>
  cartTotals(cents === 0 ? [] : [line(cents)], discount, policy).discountCents

describe('cartTotals', () => {
  test('the subtotal counts the prompt price and the quantity of every line', () => {
    expect(cartTotals([line(1490, 200, 3), line(999)], undefined, DE_SHIPPING)).toEqual({
      subtotalCents: (1490 + 200) * 3 + 999,
      shippingCostCents: 0,
      discountCents: 0,
      totalCents: (1490 + 200) * 3 + 999,
    })
    expect(cartTotals([], undefined, DE_SHIPPING)).toEqual({
      subtotalCents: 0,
      shippingCostCents: 0,
      discountCents: 0,
      totalCents: 0,
    })
  })

  test('shipping respects the country cost and optional free-shipping threshold', () => {
    const shipping = (cents: number, policy: ShippingPolicy) =>
      cartTotals([line(cents)], undefined, policy).shippingCostCents
    expect(shipping(1, DE_SHIPPING)).toBe(490)
    expect(shipping(4999, DE_SHIPPING)).toBe(490)
    expect(shipping(5000, DE_SHIPPING)).toBe(0)
    expect(shipping(10_000, DE_SHIPPING)).toBe(0)

    // Without a free-shipping threshold, shipping stays payable regardless of subtotal.
    expect(shipping(1, EU_NO_THRESHOLD)).toBe(1_190)
    expect(shipping(50_000, EU_NO_THRESHOLD)).toBe(1_190)
    expect(shipping(0, EU_NO_THRESHOLD)).toBe(0)

    // A country with 0 shipping cost or NO_SHIPPING_POLICY always charges 0.
    expect(shipping(1490, NO_SHIPPING_POLICY)).toBe(0)
  })

  test('a percentage discounts subtotal and shipping together, capped at 100', () => {
    // DE: 3980 + 490 = 4470; ten percent of that is 447.
    expect(cartTotals([line(1490, 500, 2)], percentage(10), DE_SHIPPING)).toEqual({
      subtotalCents: 3980,
      shippingCostCents: 490,
      discountCents: 447,
      totalCents: 4023,
    })
    // EU without threshold: 3980 + 1190 = 5170; ten percent of that is 517.
    expect(cartTotals([line(1490, 500, 2)], percentage(10), EU_NO_THRESHOLD)).toEqual({
      subtotalCents: 3980,
      shippingCostCents: 1_190,
      discountCents: 517,
      totalCents: 4_653,
    })
    expect(discountOn(10_000, percentage(100))).toBe(10_000)
    expect(discountOn(10_000, percentage(250))).toBe(10_000)
  })

  test('halves round up, and a fractional percentage still ends in whole cents', () => {
    // 1 % of 5050 is 50.5 -> 51; of 5049 it is 50.49 -> 50.
    expect(discountOn(5050, percentage(1))).toBe(51)
    expect(discountOn(5049, percentage(1))).toBe(50)
    // 12.5 % of 6000 is 750; of 5999 it is 749.875 -> 750.
    expect(discountOn(6000, percentage(12.5))).toBe(750)
    expect(discountOn(5999, percentage(12.5))).toBe(750)
  })

  test('a fixed discount is capped at subtotal plus shipping, and an empty cart is never discounted', () => {
    expect(discountOn(6000, fixed(500))).toBe(500)
    expect(cartTotals([line(1000)], fixed(5000), DE_SHIPPING)).toMatchObject({
      discountCents: 1490,
      totalCents: 0,
    })
    expect(cartTotals([line(1000)], fixed(5000), EU_NO_THRESHOLD)).toMatchObject({
      discountCents: 2_190,
      totalCents: 0,
    })
    expect(discountOn(0, fixed(999))).toBe(0)
    expect(discountOn(0, percentage(50))).toBe(0)
  })
})
