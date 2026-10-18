import { describe, expect, test } from 'bun:test'
import { percentOfCents, roundHalfUp } from '@/platform/money'

describe('roundHalfUp', () => {
  test('a half rounds away from zero, like BigDecimal HALF_UP', () => {
    expect(roundHalfUp(2.5)).toBe(3)
    expect(roundHalfUp(-2.5)).toBe(-3)
    expect(roundHalfUp(2.4)).toBe(2)
    expect(roundHalfUp(-0.4)).toBe(0)
  })
})

describe('percentOfCents', () => {
  test('rounds half up to whole cents, also where floating point would round down', () => {
    expect(percentOfCents(1000, 19)).toBe(190)
    // 1234 × 7.5 % = 92.55 cents; as a float 92.55 is slightly below the half.
    expect(percentOfCents(1234, 7.5)).toBe(93)
    expect(percentOfCents(1250, 7.5)).toBe(94)
    expect(percentOfCents(1, 50)).toBe(1)
    expect(percentOfCents(0, 19)).toBe(0)
  })
})
