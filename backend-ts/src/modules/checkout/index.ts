import type { Kysely } from 'kysely'
import type { CheckoutCarts } from '@/modules/cart'
import type { CountryModule } from '@/modules/country'
import type { Orders } from '@/modules/order'
import type { PromotionCodes } from '@/modules/promotion'
import type { Auth } from '@/platform/auth/auth'
import type { Tables } from '@/platform/db/database'
import { checkoutRoutes } from './routes'
import { createCheckoutService } from './service'

/**
 * The checkout module: the one buy journey, `POST /api/checkout` and the payment retry. It owns no
 * table and exports no capability; it is the last consumer in the chain, where the cart, the
 * promotion, the order, and the country list meet. `db` is for its two transactions only.
 */
export function createCheckoutModule({
  db,
  checkoutCarts,
  promotionCodes,
  orders,
  countries,
  guestTokens,
}: {
  db: Kysely<Tables>
  checkoutCarts: CheckoutCarts
  promotionCodes: Pick<PromotionCodes, 'reserve' | 'release'>
  orders: Pick<Orders, 'place' | 'payable' | 'confirmFree' | 'startPayment'>
  countries: Pick<CountryModule, 'isShippable'>
  guestTokens: Auth['guestTokens']
}) {
  const service = createCheckoutService({
    db,
    carts: checkoutCarts,
    promotionCodes,
    orders,
    countries,
  })
  return { routes: checkoutRoutes(service, guestTokens) }
}

export type CheckoutModule = ReturnType<typeof createCheckoutModule>
