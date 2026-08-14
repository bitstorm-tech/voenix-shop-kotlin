import { describe, expect, it } from 'vitest'
import { orderDisplayStatus } from '../orderDisplayStatus'

describe('orderDisplayStatus', () => {
  it('lets a settled order status decide alone', () => {
    expect(orderDisplayStatus('PAID', 'PAID')).toBe('PAID')
    expect(orderDisplayStatus('PAID', null)).toBe('PAID')
    expect(orderDisplayStatus('CANCELLED', 'FAILED')).toBe('CANCELLED')
  })

  it('shows a pending order as paid once the payment is confirmed', () => {
    expect(orderDisplayStatus('PENDING', 'PAID')).toBe('PAID')
  })

  it('surfaces a dead payment while the order is still pending', () => {
    expect(orderDisplayStatus('PENDING', 'FAILED')).toBe('PAYMENT_FAILED')
    expect(orderDisplayStatus('PENDING', 'EXPIRED')).toBe('PAYMENT_EXPIRED')
    expect(orderDisplayStatus('PENDING', 'CANCELED')).toBe('PAYMENT_CANCELED')
  })

  it('reads every running or absent payment as in progress', () => {
    expect(orderDisplayStatus('PENDING', 'OPEN')).toBe('PENDING')
    expect(orderDisplayStatus('PENDING', 'PENDING')).toBe('PENDING')
    expect(orderDisplayStatus('PENDING', 'AUTHORIZED')).toBe('PENDING')
    expect(orderDisplayStatus('PENDING', null)).toBe('PENDING')
  })
})
