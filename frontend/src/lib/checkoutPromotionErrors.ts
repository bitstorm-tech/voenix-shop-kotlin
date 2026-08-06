export const checkoutPromotionErrorKeys = {
  PROMOTION_INVALID_CODE: 'checkout.errors.promotion.invalidCode',
  PROMOTION_INACTIVE: 'checkout.errors.promotion.inactive',
  PROMOTION_NOT_STARTED: 'checkout.errors.promotion.notStarted',
  PROMOTION_EXPIRED: 'checkout.errors.promotion.expired',
  PROMOTION_TOTAL_EXHAUSTED: 'checkout.errors.promotion.totalExhausted',
  PROMOTION_PER_USER_EXHAUSTED: 'checkout.errors.promotion.perUserExhausted',
  PROMOTION_LOGIN_REQUIRED: 'checkout.errors.promotion.loginRequired',
} as const

export type CheckoutPromotionErrorCode = keyof typeof checkoutPromotionErrorKeys

export function isCheckoutPromotionErrorCode(value: unknown): value is CheckoutPromotionErrorCode {
  return typeof value === 'string' && value in checkoutPromotionErrorKeys
}
