import {
  catalogKey,
  type ArticleCatalog,
  type ArticleType,
  type CatalogVariant,
} from '@/modules/article'
import { uploadRejection, type ImageStorage, type UploadedImage } from '@/modules/image'
import type { Orders, PlaceOrderInput } from '@/modules/order'
import type {
  ApplicablePromotion,
  DiscountType,
  PromotionCodes,
  PromotionRejection,
} from '@/modules/promotion'
import type { PromptCatalog } from '@/modules/prompt'
import { createLogger } from '@/platform/logging'
import { conflict, invalid, notFound, ok, type OperationResult } from '@/platform/operation'
import type { CartOwner, CartRepository, StoredCart } from './repository'
import { cartTotals, type CartTotals } from './totals'

/**
 * The one answer every cart operation but the upload gives: the complete, recalculated cart. A
 * mutation never answers only what it changed, because the browser cannot compute shipping
 * thresholds and discount caps itself.
 */
export interface CartView {
  /** `null` while the caller has no cart yet, so a client can tell "no cart" from cart `0`. */
  id: number | null
  items: CartLine[]
  subtotal: number
  shippingCost: number
  discountAmount: number
  total: number
  totalItems: number
  appliedPromotion: AppliedPromotion | null
}

/**
 * One rendered line. `price` and `promptPrice` are the snapshots taken when it was added; the
 * type, the names, the colour codes, and `available` are current master data, resolved on every
 * read. A reference the catalog no longer answers renders with `null`s and `available: false`
 * instead of disappearing.
 */
export interface CartLine {
  id: number
  articleId: number
  variantId: number
  articleType: ArticleType | null
  articleName: string | null
  variantName: string | null
  outsideColorCode: string | null
  insideColorCode: string | null
  available: boolean
  price: number
  quantity: number
  imageId: number | null
  promptId: number | null
  promptPrice: number
}

/** The applied promotion as the cart renders it: a flat type and value, no nested union. */
export interface AppliedPromotion {
  id: number
  name: string
  promotionCode: string
  discountType: DiscountType
  discountValue: number
}

/** The validated body of `POST /api/cart/items`. */
export interface AddItemInput {
  articleId: number
  variantId: number
  quantity: number
  promptId?: number | null | undefined
  imageId?: number | null | undefined
}

/**
 * The priced active cart a checkout places its order from. It is shaped so that the checkout
 * builds its `PlaceOrderInput` from it without a second read: the caller's identity as it asked,
 * the cart and promotion ids, the lines with their price snapshots, and the four totals the
 * customer was shown. Each line also carries its current `articleType` (`null` when the catalog no
 * longer resolves it), which the checkout's phone rule for SPOD articles asks about.
 */
export interface CheckoutCart
  extends Pick<PlaceOrderInput, 'cartId' | 'userId' | 'guestToken' | 'promotionId'>,
    CartTotals {
  lines: CheckoutCartLine[]
}

export type CheckoutCartLine = PlaceOrderInput['lines'][number] & {
  articleType: ArticleType | null
}

/** Applying a coupon answers the cart, no cart (`notFound`), or the promotion's refusal. */
export type ApplyPromotionResult =
  | OperationResult<CartView>
  | { kind: 'rejected'; rejection: PromotionRejection }

const EMPTY_CART: CartView = {
  id: null,
  items: [],
  subtotal: 0,
  shippingCost: 0,
  discountAmount: 0,
  total: 0,
  totalItems: 0,
  appliedPromotion: null,
}

/** A stored cart with everything resolved that is current master data. */
interface PricedCart {
  stored: StoredCart
  variants: Map<string, CatalogVariant>
  promotion: ApplicablePromotion | undefined
  totals: CartTotals
}

const log = createLogger('cart')

export type CartService = ReturnType<typeof createCartService>

/**
 * What a cart means, between the routes and the tables: **snapshotting** (an add asks the two
 * catalogs what the customer is charged right now, and that number goes onto the line for good),
 * **rendering** (the type, names, colours, availability, and promotion behind the stored ids are
 * resolved on every answer, in one batched call each), and **totals** (`cartTotals`).
 */
export function createCartService({
  repository,
  articleCatalog,
  promptCatalog,
  promotionCodes,
  orders,
  privateStorage,
}: {
  repository: CartRepository
  articleCatalog: Pick<ArticleCatalog, 'find'>
  promptCatalog: Pick<PromptCatalog, 'findSalesGrossPriceCents'>
  promotionCodes: Pick<PromotionCodes, 'validate' | 'find'>
  orders: Pick<Orders, 'orderItem'>
  privateStorage: ImageStorage
}) {
  const price = async (stored: StoredCart): Promise<PricedCart> => {
    const variants = await articleCatalog.find(stored.lines)
    const promotion =
      stored.promotionId === null
        ? undefined
        : (await promotionCodes.find([stored.promotionId])).get(stored.promotionId)
    return { stored, variants, promotion, totals: cartTotals(stored.lines, promotion?.discount) }
  }

  const view = async (stored: StoredCart): Promise<CartView> => toCartView(await price(stored))

  const viewOf = async (stored: StoredCart | undefined): Promise<OperationResult<CartView>> =>
    stored === undefined ? notFound : ok(await view(stored))

  /**
   * Adds one line at today's prices. The variant must be purchasable and the prompt usable; the
   * image, when given, must belong to the caller.
   */
  const addItem = async (
    owner: CartOwner,
    input: AddItemInput,
  ): Promise<OperationResult<CartView>> => {
    const variant = (await articleCatalog.find([input])).get(catalogKey(input))
    if (variant?.purchasable !== true || variant.grossSalesPriceCents === null) {
      return invalid({ variantId: ['The article variant cannot be bought'] })
    }
    const promptId = input.promptId ?? null
    let promptPriceCents = 0
    if (promptId !== null) {
      const promptPrice = (await promptCatalog.findSalesGrossPriceCents([promptId])).get(promptId)
      if (promptPrice === undefined) return invalid({ promptId: ['The prompt cannot be used'] })
      promptPriceCents = promptPrice
    }
    const stored = await repository.addItem(owner, {
      articleId: input.articleId,
      variantId: input.variantId,
      quantity: input.quantity,
      priceCents: variant.grossSalesPriceCents,
      promptId,
      promptPriceCents,
      printImageId: input.imageId ?? null,
    })
    if (stored === 'imageNotOwned') return invalid({ imageId: ['The image cannot be used'] })
    return ok(await view(stored))
  }

  return {
    /** The active cart of `owner`, or the empty view when they have none yet. */
    cart: async (owner: CartOwner): Promise<CartView> => {
      const stored = await repository.findActiveCart(owner)
      return stored === undefined ? EMPTY_CART : view(stored)
    },

    /**
     * Stores the upload in the private storage and registers it as a print image of `owner`. The
     * file goes first: a row pointing at a missing file would be a broken line forever, a file
     * without a row is an unreachable orphan. When the row cannot be written, the file is deleted
     * again.
     */
    uploadPrintImage: async (
      owner: CartOwner,
      upload: UploadedImage,
    ): Promise<OperationResult<{ id: number }>> => {
      if (upload.kind === 'missing') return uploadRejection('An image file is required')
      if (upload.kind === 'tooLarge') return uploadRejection('Image must not exceed 10 MiB')
      const stored = await privateStorage.store(upload.upload)
      if (stored.kind !== 'ok') return stored
      const { filename } = stored.value
      try {
        return ok({ id: await repository.insertPrintImage(owner, filename) })
      } catch (error) {
        // A failing delete must not hide why the registration failed.
        await privateStorage.delete(filename).catch((deleteError: unknown) => {
          log.error({ err: deleteError, filename }, 'Could not delete an unregistered print image')
        })
        throw error
      }
    },

    addItem,

    /**
     * Puts an ordered line back into the cart as an ordinary add of one, at today's price. The
     * ordered line contributes its references only. Its print image is the one thing a reorder
     * cannot replace, so a line without one, an image the caller may not use, and an image whose
     * file is gone are all `conflict`. An order item the caller does not own is `notFound`.
     */
    reorder: async (owner: CartOwner, orderItemId: number): Promise<OperationResult<CartView>> => {
      const ordered = await orders.orderItem(orderItemId, owner.userId, owner.guestToken)
      if (ordered === undefined) return notFound
      if (ordered.printImageId === null) return conflict
      const filename = await repository.printImageFilename({
        imageId: ordered.printImageId,
        guestToken: owner.guestToken,
        userId: owner.userId,
      })
      if (filename === null || !(await privateStorage.exists(filename))) return conflict
      return addItem(owner, {
        articleId: ordered.articleId,
        variantId: ordered.variantId,
        quantity: 1,
        promptId: ordered.promptId,
        imageId: ordered.printImageId,
      })
    },

    updateQuantity: async (owner: CartOwner, itemId: number, quantity: number) =>
      viewOf(await repository.updateQuantity(owner, itemId, quantity)),

    removeItem: async (owner: CartOwner, itemId: number) =>
      viewOf(await repository.removeItem(owner, itemId)),

    /**
     * Validates the code before anything is written, so a rejected code never replaces the one the
     * cart carries. The cart names itself, so its own reservation does not make the code look
     * exhausted. Only the promotion id is stored; the discount is recalculated on every read.
     */
    applyPromotion: async (owner: CartOwner, code: string): Promise<ApplyPromotionResult> => {
      const cart = await repository.findActiveCart(owner)
      if (cart === undefined) return notFound
      const validated = await promotionCodes.validate(code, cart.id, owner.userId)
      if (validated.kind !== 'applicable') return { kind: 'rejected', rejection: validated }
      return viewOf(await repository.applyPromotion(owner, validated.promotion.id))
    },

    removePromotion: async (owner: CartOwner) => viewOf(await repository.removePromotion(owner)),

    /**
     * The priced active cart of this caller for the checkout, or `null` when there is none. The
     * lookup is the cart routes' own, so a signed-in checkout gets the customer's cart. A cart
     * without lines is a snapshot with no lines, not `null`.
     */
    checkoutCart: async (
      guestToken: string | null,
      userId: number | null,
    ): Promise<CheckoutCart | null> => {
      const stored = await repository.findActiveCart({ guestToken, userId })
      if (stored === undefined) return null
      const { variants, totals } = await price(stored)
      return {
        cartId: stored.id,
        userId,
        guestToken,
        promotionId: stored.promotionId,
        lines: stored.lines.map((line) => ({
          articleId: line.articleId,
          variantId: line.variantId,
          quantity: line.quantity,
          priceCents: line.priceCents,
          promptPriceCents: line.promptPriceCents,
          promptId: line.promptId,
          printImageId: line.printImageId,
          articleType: variants.get(catalogKey(line))?.articleType ?? null,
        })),
        ...totals,
      }
    },
  }
}

function toCartView({ stored, variants, promotion, totals }: PricedCart): CartView {
  return {
    id: stored.id,
    items: stored.lines.map((line) => {
      const variant = variants.get(catalogKey(line))
      return {
        id: line.id,
        articleId: line.articleId,
        variantId: line.variantId,
        articleType: variant?.articleType ?? null,
        articleName: variant?.articleName ?? null,
        variantName: variant?.variantName ?? null,
        outsideColorCode: variant?.outsideColorCode ?? null,
        insideColorCode: variant?.insideColorCode ?? null,
        available: variant?.purchasable === true,
        price: line.priceCents,
        quantity: line.quantity,
        imageId: line.printImageId,
        promptId: line.promptId,
        promptPrice: line.promptPriceCents,
      }
    }),
    subtotal: totals.subtotalCents,
    shippingCost: totals.shippingCostCents,
    discountAmount: totals.discountCents,
    total: totals.totalCents,
    totalItems: stored.lines.reduce((sum, line) => sum + line.quantity, 0),
    appliedPromotion:
      promotion === undefined
        ? null
        : {
            id: promotion.id,
            name: promotion.name,
            promotionCode: promotion.couponCode,
            discountType: promotion.discount.discountType,
            discountValue: promotion.discount.discountValue,
          },
  }
}
