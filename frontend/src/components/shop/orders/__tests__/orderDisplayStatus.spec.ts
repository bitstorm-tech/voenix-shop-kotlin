import { describe, expect, it } from 'vitest'
import { orderDisplayStatus } from '../orderDisplayStatus'

describe('orderDisplayStatus', () => {
  it('lets a settled order status decide alone, refined by shipping progress once paid', () => {
    expect(orderDisplayStatus('PAID', 'PAID')).toBe('PAID')
    expect(orderDisplayStatus('PAID', null)).toBe('PAID')
    expect(orderDisplayStatus('PAID', 'PAID', 'OPEN')).toBe('PAID')
    expect(orderDisplayStatus('PAID', 'PAID', 'PARTIALLY_SHIPPED')).toBe('PARTIALLY_SHIPPED')
    expect(orderDisplayStatus('PAID', 'PAID', 'SHIPPED')).toBe('SHIPPED')
    expect(orderDisplayStatus('PAID', null, 'SHIPPED')).toBe('SHIPPED')
    expect(orderDisplayStatus('CANCELLED', 'FAILED', 'SHIPPED')).toBe('CANCELLED')
  })

  it('shows a pending order as paid (or shipped) once the payment is confirmed', () => {
    expect(orderDisplayStatus('PENDING', 'PAID')).toBe('PAID')
    expect(orderDisplayStatus('PENDING', 'PAID', 'SHIPPED')).toBe('SHIPPED')
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
