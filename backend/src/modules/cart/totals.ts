import type { Discount } from '@/modules/promotion'
import { percentOfCents, roundHalfUp } from '@/platform/money'

/**
 * Country-specific shipping rules in whole cents. A `null` threshold means shipping is never free
 * by subtotal alone (it is still `0` when `shippingCostCents === 0` or the cart subtotal is `0`).
 */
export interface ShippingPolicy {
  shippingCostCents: number
  freeShippingThresholdCents: number | null
}

/** Used in the cart before a shipping country is chosen: no shipping cost is assumed. */
export const NO_SHIPPING_POLICY: ShippingPolicy = {
  shippingCostCents: 0,
  freeShippingThresholdCents: null,
}

/** The four amounts of a cart, in whole cents; `total = subtotal + shipping - discount`. */
export interface CartTotals {
  subtotalCents: number
  shippingCostCents: number
  discountCents: number
  totalCents: number
}

/**
 * The monetary rules of a cart, as pure arithmetic: the one place cart lines are added up, so the
 * cart the customer sees and the snapshot the checkout charges follow one formula.
 *
 * - The subtotal is article price plus prompt price, times the quantity, per line.
 * - Shipping is `0` when `subtotal <= 0`, `0` when `freeShippingThresholdCents !== null` and
 *   `subtotal >= freeShippingThresholdCents`, and `shippingCostCents` otherwise. It is calculated
 *   from the pre-coupon subtotal, so a coupon never takes free shipping away again.
 * - The discount's base is subtotal **plus** shipping. A percentage above 100 counts as 100, halves
 *   round up, and the result never exceeds the base: a 50-euro coupon on a 10-euro cart makes it
 *   free, never negative.
 */
export function cartTotals(
  lines: readonly { priceCents: number; promptPriceCents: number; quantity: number }[],
  discount: Discount | undefined,
  shippingPolicy: ShippingPolicy,
): CartTotals {
  const subtotalCents = lines.reduce(
    (sum, line) => sum + (line.priceCents + line.promptPriceCents) * line.quantity,
    0,
  )
  const freeByThreshold =
    shippingPolicy.freeShippingThresholdCents !== null &&
    subtotalCents >= shippingPolicy.freeShippingThresholdCents
  const shippingCostCents =
    subtotalCents <= 0 || freeByThreshold ? 0 : shippingPolicy.shippingCostCents
  const base = subtotalCents + shippingCostCents
  const discountCents = discount === undefined || base <= 0 ? 0 : discountOf(base, discount)
  return {
    subtotalCents,
    shippingCostCents,
    discountCents,
    totalCents: base - discountCents,
  }
}

function discountOf(base: number, discount: Discount): number {
  const requested =
    discount.discountType === 'PERCENTAGE'
      ? percentOfCents(base, Math.min(discount.discountValue, 100))
      : roundHalfUp(discount.discountValue)
  return Math.max(0, Math.min(requested, base))
}
