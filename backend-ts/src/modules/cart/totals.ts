import type { Discount } from '@/modules/promotion'
import { percentOfCents, roundHalfUp } from '@/platform/money'

/** Orders below this pre-coupon subtotal pay shipping; from it on, shipping is free. */
export const FREE_SHIPPING_THRESHOLD_CENTS = 5_000

/** What shipping costs while the subtotal is below the free-shipping threshold. */
export const SHIPPING_COST_CENTS = 490

/** The four amounts of a cart, in whole cents; `total = subtotal + shipping - discount`. */
export interface CartTotals {
  subtotalCents: number
  shippingCostCents: number
  discountCents: number
  totalCents: number
}

/**
 * The monetary rules of a cart, as pure arithmetic: the one place cart lines are added up, so the
 * cart the customer sees and the snapshot the checkout charges can never disagree.
 *
 * - The subtotal is article price plus prompt price, times the quantity, per line.
 * - Shipping is `0` for an empty cart and from the threshold on, `490` in between. It is
 *   calculated from the pre-coupon subtotal, so a coupon never takes free shipping away again.
 * - The discount's base is subtotal **plus** shipping. A percentage above 100 counts as 100, halves
 *   round up, and the result never exceeds the base: a 50-euro coupon on a 10-euro cart makes it
 *   free, never negative.
 */
export function cartTotals(
  lines: readonly { priceCents: number; promptPriceCents: number; quantity: number }[],
  discount: Discount | undefined,
): CartTotals {
  const subtotalCents = lines.reduce(
    (sum, line) => sum + (line.priceCents + line.promptPriceCents) * line.quantity,
    0,
  )
  const shippingCostCents =
    subtotalCents <= 0 || subtotalCents >= FREE_SHIPPING_THRESHOLD_CENTS ? 0 : SHIPPING_COST_CENTS
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
