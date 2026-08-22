import type { CartItem, CartView } from '@/stores/shop/cart'

/**
 * The example `CartView` of `docs/dev/backend/packages/cart-package.md`, field for field. Every consumer of
 * the cart contract builds its fixtures from these two helpers, so a contract change breaks in one
 * place instead of in every spec.
 */
export function createCartItem(overrides: Partial<CartItem> = {}): CartItem {
  return {
    id: 34,
    articleId: 10,
    variantId: 20,
    articleType: 'MUG',
    articleName: 'Classic',
    variantName: 'Weiß',
    outsideColorCode: '#ffffff',
    insideColorCode: '#ff0000',
    available: true,
    price: 1490,
    quantity: 2,
    imageId: 77,
    promptId: 5,
    promptPrice: 500,
    ...overrides,
  }
}

export function createCartView(overrides: Partial<CartView> = {}): CartView {
  return {
    id: 12,
    items: [createCartItem()],
    subtotal: 3980,
    shippingCost: 490,
    discountAmount: 447,
    total: 4023,
    totalItems: 2,
    appliedPromotion: {
      id: 3,
      name: 'Sommer',
      promotionCode: 'SAVE10',
      discountType: 'PERCENTAGE',
      discountValue: 10,
    },
    ...overrides,
  }
}

/** What `GET /api/cart` answers a visitor without a cart: `id: null` and zeros. */
export function createEmptyCartView(): CartView {
  return {
    id: null,
    items: [],
    subtotal: 0,
    shippingCost: 0,
    discountAmount: 0,
    total: 0,
    totalItems: 0,
    appliedPromotion: null,
  }
}
