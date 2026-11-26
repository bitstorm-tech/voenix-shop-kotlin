/**
 * Country-specific shipping rules in whole cents. Mirrors `backend/src/modules/cart/totals.ts`
 * and `backend/src/platform/money.ts` so the checkout preview matches the order placement to the cent.
 */
export interface ShippingPolicy {
  shippingCostCents: number
  freeShippingThresholdCents: number | null
}

export interface CheckoutDiscount {
  discountType: 'PERCENTAGE' | 'FIXED_AMOUNT'
  discountValue: number
}

export interface CartTotals {
  subtotalCents: number
  shippingCostCents: number
  discountCents: number
  totalCents: number
}

function roundHalfUp(value: number): number {
  const rounded = Math.round(Math.abs(value))
  return rounded === 0 ? 0 : value < 0 ? -rounded : rounded
}

function percentOfCents(cents: number, percent: number): number {
  const percentHundredths = Math.round(percent * 100)
  return roundHalfUp((cents * percentHundredths) / 10_000)
}

/**
 * Pure monetary calculation for checkout totals:
 * - Shipping is `0` when `subtotalCents <= 0`, `0` when `freeShippingThresholdCents !== null` and
 *   `subtotalCents >= freeShippingThresholdCents`, otherwise `shippingCostCents`.
 * - Discount applies to `subtotalCents + shippingCostCents` (percentage capped at 100, halves
 *   rounded up; fixed amount rounded half up; result capped at `subtotalCents + shippingCostCents`).
 * - Total is `subtotalCents + shippingCostCents - discountCents`.
 */
export function calculateCartTotals(
  subtotalCents: number,
  discount: CheckoutDiscount | null | undefined,
  shippingPolicy: ShippingPolicy,
): CartTotals {
  const freeByThreshold =
    shippingPolicy.freeShippingThresholdCents !== null &&
    subtotalCents >= shippingPolicy.freeShippingThresholdCents
  const shippingCostCents =
    subtotalCents <= 0 || freeByThreshold ? 0 : shippingPolicy.shippingCostCents
  const base = subtotalCents + shippingCostCents
  const discountCents =
    discount === null || discount === undefined || base <= 0 ? 0 : discountOf(base, discount)
  return {
    subtotalCents,
    shippingCostCents,
    discountCents,
    totalCents: base - discountCents,
  }
}

function discountOf(base: number, discount: CheckoutDiscount): number {
  const requested =
    discount.discountType === 'PERCENTAGE'
      ? percentOfCents(base, Math.min(discount.discountValue, 100))
      : roundHalfUp(discount.discountValue)
  return Math.max(0, Math.min(requested, base))
}
