import type { MiddlewareHandler } from 'hono'
import type { Kysely } from 'kysely'
import type { ArticleCatalog } from '@/modules/article'
import type { ImageModule, PrintImageRegistry } from '@/modules/image'
import type { Orders } from '@/modules/order'
import type { PromotionCodes } from '@/modules/promotion'
import type { PromptCatalog } from '@/modules/prompt'
import type { Auth } from '@/platform/auth/auth'
import type { Tables } from '@/platform/db/database'
import { createCartRepository } from './repository'
import { cartRoutes } from './routes'
import { createCartService } from './service'

export type { CheckoutCart, CheckoutCartLine } from './service'
export { createPrintImageReads } from './repository'
export { cartTotals, type CartTotals, type ShippingPolicy } from './totals'

/**
 * The cart module: the eight `/api/cart` routes and the three things it exports. `guestImageResolver`
 * is the image module's `GuestImageResolver`, which the composition root installs with
 * `image.guestImageRoute(cart.guestImageResolver)`. `printImageRegistry` is the image module's
 * `PrintImageRegistry` for the admin orphan list. `checkoutCarts` is what the checkout needs:
 * the priced active cart, the row lock of its first transaction, and the close. The fourth export
 * of this module, `createPrintImageReads`, is a factory rather than a member, because the order
 * module needs it and is created before the cart.
 */
export function createCartModule({
  db,
  articleCatalog,
  promptCatalog,
  promotionCodes,
  orders,
  images,
  guestTokens,
  promotionRateLimit,
}: {
  db: Kysely<Tables>
  articleCatalog: Pick<ArticleCatalog, 'find'>
  promptCatalog: Pick<PromptCatalog, 'findSalesGrossPriceCents'>
  promotionCodes: Pick<PromotionCodes, 'validate' | 'find' | 'release'>
  orders: Pick<Orders, 'orderItem' | 'printImageIds'>
  images: Pick<ImageModule, 'privateStorage'>
  guestTokens: Auth['guestTokens']
  promotionRateLimit?: MiddlewareHandler
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
    routes: cartRoutes(service, guestTokens, promotionRateLimit),
    guestImageResolver: repository.printImageFilename,
    printImageRegistry: {
      inUse: async () => repository.printImagesInUse(await orders.printImageIds()),
      release: repository.releasePrintImage,
    } satisfies PrintImageRegistry,
    checkoutCarts: {
      /**
       * Checkout TX1: locks (`SELECT … FOR UPDATE`) and prices the caller's active cart (`userId`
       * first, else `guestToken`) inside `trx`, or answers `null` when there is none.
       */
      lockActiveCart: service.lockCheckoutCart,
      /** Closes the cart in the caller's transaction; `false` when it was no longer active. */
      markCheckedOut: repository.markCheckedOut,
    },
  }
}

export type CartModule = ReturnType<typeof createCartModule>
export type CheckoutCarts = CartModule['checkoutCarts']
