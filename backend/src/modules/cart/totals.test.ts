import { describe, expect, test } from 'bun:test'
import type { Discount } from '@/modules/promotion'
import { cartTotals } from './totals'

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
/** The discount of one line of `cents` (no prompt) with `discount`. */
const discountOn = (cents: number, discount: Discount) =>
  cartTotals(cents === 0 ? [] : [line(cents)], discount).discountCents

describe('cartTotals', () => {
  test('the subtotal counts the prompt price and the quantity of every line', () => {
    expect(cartTotals([line(1490, 200, 3), line(999)], undefined)).toEqual({
      subtotalCents: (1490 + 200) * 3 + 999,
      shippingCostCents: 0,
      discountCents: 0,
      totalCents: (1490 + 200) * 3 + 999,
    })
    expect(cartTotals([], undefined)).toEqual({
      subtotalCents: 0,
      shippingCostCents: 0,
      discountCents: 0,
      totalCents: 0,
    })
  })

  test('shipping is 490 below the 5000-cent threshold and free from it on', () => {
    const shipping = (cents: number) => cartTotals([line(cents)], undefined).shippingCostCents
    expect(shipping(1)).toBe(490)
    expect(shipping(4999)).toBe(490)
    expect(shipping(5000)).toBe(0)
    expect(shipping(10_000)).toBe(0)
  })

  test('a percentage discounts subtotal and shipping together, capped at 100', () => {
    // 3980 + 490 = 4470; ten percent of that is 447.
    expect(cartTotals([line(1490, 500, 2)], percentage(10))).toEqual({
      subtotalCents: 3980,
      shippingCostCents: 490,
      discountCents: 447,
      totalCents: 4023,
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
    expect(cartTotals([line(1000)], fixed(5000))).toMatchObject({
      discountCents: 1490,
      totalCents: 0,
    })
    expect(discountOn(0, fixed(999))).toBe(0)
    expect(discountOn(0, percentage(50))).toBe(0)
  })
})
