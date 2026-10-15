import type { Generated } from 'kysely'

/** A cart is being filled (`ACTIVE`) or a checkout has closed it (`CHECKED_OUT`); nothing else. */
export type CartStatus = 'ACTIVE' | 'CHECKED_OUT'

/**
 * The `carts` table (V15). A cart carries one identity for life: the guest token of an anonymous
 * visitor or the user id of a signed-in customer (`ck_carts_single_owner`); both are `NULL` only
 * after the account was deleted. One partial unique index per identity allows one active cart.
 */
export interface CartsTable {
  id: Generated<number>
  guest_session_token: string | null
  user_id: number | null
  status: CartStatus
  promotion_id: number | null
  created_at: Generated<Date>
  updated_at: Generated<Date>
}

/**
 * The `cart_items` table (V15): one line, with the article and prompt price snapshots taken when
 * it was added. `(variant_id, article_id)` references `article_variant_identities`, quantities
 * are 1 to 99, and `position` is unique within its cart.
 */
export interface CartItemsTable {
  id: Generated<number>
  cart_id: number
  article_id: number
  variant_id: number
  quantity: number
  price_cents: number
  prompt_id: number | null
  prompt_price_cents: number
  print_image_id: number | null
  position: number
  created_at: Generated<Date>
  updated_at: Generated<Date>
}

/**
 * The `print_images` table (V15): the registry of uploaded print images, whose files live in the
 * image module's private storage. An upload made while signed in stores both owners; the order
 * module reads the file names with one raw statement.
 */
export interface PrintImagesTable {
  id: Generated<number>
  filename: string
  guest_session_token: string | null
  user_id: number | null
  created_at: Generated<Date>
}

declare module '@/platform/db/database' {
  interface Tables {
    carts: CartsTable
    cart_items: CartItemsTable
    print_images: PrintImagesTable
  }
}
