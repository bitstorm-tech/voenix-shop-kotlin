import type { Kysely } from 'kysely'
import type { Tables } from '@/platform/db/database'
import { createPromotionCodes } from './codes'
import { createPromotionRepository } from './repository'
import { promotionRoutes } from './routes'
import { createPromotionService } from './service'

export { toApiError } from './codes'
export type {
  ApplicablePromotion,
  PromotionCodeResult,
  PromotionCodes,
  PromotionRejection,
} from './codes'
export type { Discount, DiscountType } from './repository'

/**
 * The promotion module: the coupon admin routes, and the `promotionCodes` capability Cart,
 * Checkout, and Order apply, reserve, redeem, and release coupon codes with.
 */
export function createPromotionModule({ db }: { db: Kysely<Tables> }) {
  return {
    routes: promotionRoutes(createPromotionService(createPromotionRepository(db))),
    promotionCodes: createPromotionCodes(db),
  }
}

export type PromotionModule = ReturnType<typeof createPromotionModule>
