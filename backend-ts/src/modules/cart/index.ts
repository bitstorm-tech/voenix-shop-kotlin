import type { Kysely } from 'kysely'
import type { ArticleCatalog } from '@/modules/article'
import type { ImageModule } from '@/modules/image'
import type { Orders } from '@/modules/order'
import type { PromotionCodes } from '@/modules/promotion'
import type { PromptCatalog } from '@/modules/prompt'
import type { Auth } from '@/platform/auth/auth'
import type { Tables } from '@/platform/db/database'
import { createCartRepository } from './repository'
import { cartRoutes } from './routes'
import { createCartService } from './service'

export type { CheckoutCart, CheckoutCartLine } from './service'

/**
 * The cart module: the eight `/api/cart` routes and the two things it exports. `guestImageResolver`
 * is the image module's `GuestImageResolver`, which the composition root installs with
 * `image.guestImageRoute(cart.guestImageResolver)`. `checkoutCarts` is what the checkout needs:
 * the priced active cart, the row lock of its first transaction, and the close.
 */
export function createCartModule({
  db,
  articleCatalog,
  promptCatalog,
  promotionCodes,
  orders,
  images,
  guestTokens,
}: {
  db: Kysely<Tables>
  articleCatalog: Pick<ArticleCatalog, 'find'>
  promptCatalog: Pick<PromptCatalog, 'findSalesGrossPriceCents'>
  promotionCodes: Pick<PromotionCodes, 'validate' | 'find' | 'release'>
  orders: Pick<Orders, 'orderItem'>
  images: Pick<ImageModule, 'privateStorage'>
  guestTokens: Auth['guestTokens']
}) {
  const repository = createCartRepository(db, promotionCodes)
  const service = createCartService({
    repository,
    articleCatalog,
    promptCatalog,
    promotionCodes,
    orders,
    privateStorage: images.privateStorage,
  })
  return {
    routes: cartRoutes(service, guestTokens),
    guestImageResolver: repository.printImageFilename,
    checkoutCarts: {
      /** The priced active cart of the caller (`userId` first, else `guestToken`), or `null`. */
      activeCart: service.checkoutCart,
      /** Checkout TX1: `SELECT … FOR UPDATE` on the cart row; `true` while it is still active. */
      lockForCheckout: repository.lockForCheckout,
      /** Closes the cart in the caller's transaction; `false` when it was no longer active. */
      markCheckedOut: repository.markCheckedOut,
    },
  }
}

export type CartModule = ReturnType<typeof createCartModule>
export type CheckoutCarts = CartModule['checkoutCarts']
