import { sql, type Kysely } from 'kysely'
import type { Tables } from '@/platform/db/database'

/**
 * The rows a checkout journey starts from, written with SQL: how a mug or a cart is created is the
 * article and the cart module's contract, while every assertion here starts from rows that are
 * simply there. The mug is inactive and priceless on purpose; the catalog resolves it either way,
 * which is all a placement needs. Test-only.
 */

/** A mug the catalog resolves. */
export const MUG = { articleId: 4711, variantId: 8150 }
/** Identities without a mug behind them: a cart line may point at them, the catalog answers nothing. */
export const GHOST = { articleId: 4712, variantId: 8151 }
/** The SPOD article `seedSpodArticle` writes. */
export const SHIRT = { articleId: 4713, variantId: 8152 }

/** One line of this price plus the 490 shipping is the 14.90 EUR every journey pays. */
export const LINE_PRICE_CENTS = 1_000
export const TOTAL_CENTS = LINE_PRICE_CENTS + 490

/** Empties every table a checkout journey writes and seeds the mug and the ghost identities. */
export async function resetCheckouts(db: Kysely<Tables>): Promise<void> {
  await sql`TRUNCATE orders, payments, production_requests, email_jobs, promotion_redemptions,
    promotion_reservations, promotions, print_images, carts, users, article_identities,
    production_destinations, suppliers RESTART IDENTITY CASCADE`.execute(db)
  await sql`INSERT INTO article_identities (id, article_type)
    VALUES (${MUG.articleId}, 'MUG'), (${GHOST.articleId}, 'MUG')`.execute(db)
  await sql`INSERT INTO article_variant_identities (id, article_id, article_type)
    VALUES (${MUG.variantId}, ${MUG.articleId}, 'MUG'),
      (${GHOST.variantId}, ${GHOST.articleId}, 'MUG')`.execute(db)
  await sql`INSERT INTO article_mugs (id, position, name, description_short, description_long,
      active)
    VALUES (${MUG.articleId}, 1, 'Zaubertasse', 'Kurz', 'Lang', FALSE)`.execute(db)
  await sql`INSERT INTO article_mug_variants (id, article_id, inside_color_code,
      outside_color_code, name, is_default, active)
    VALUES (${MUG.variantId}, ${MUG.articleId}, '#FFFFFF', '#0000FF', 'Blau', TRUE, FALSE)`.execute(
    db,
  )
}

/**
 * A SPOD article with one colour and one size, behind a supplier and a SPOD destination. Written
 * after the application exists: the production module refuses to start next to a SPOD destination
 * unless the channel is configured, and nothing here needs it configured.
 */
export async function seedSpodArticle(db: Kysely<Tables>): Promise<void> {
  const { rows: suppliers } = await sql<{ id: number }>`INSERT INTO suppliers (name)
    VALUES ('Spreadconnect') RETURNING id`.execute(db)
  const supplierId = suppliers[0]?.id ?? 0
  const { rows: destinations } = await sql<{ id: number }>`INSERT INTO production_destinations
      (supplier_id, channel, label)
    VALUES (${supplierId}, 'SPOD', 'Spreadconnect staging') RETURNING id`.execute(db)
  await sql`INSERT INTO article_identities (id, article_type)
    VALUES (${SHIRT.articleId}, 'SPOD')`.execute(db)
  await sql`INSERT INTO article_spod_articles (id, position, name, description_short,
      description_long, active, supplier_id, spod_destination_id, spod_environment,
      spod_article_id, spod_synced_at, spod_product_type_id, spod_product_type_name, spod_hotspot,
      print_frame_left_pct, print_frame_top_pct, print_frame_width_pct, print_frame_height_pct)
    VALUES (${SHIRT.articleId}, 1, 'Shirt Organic', 'Kurz', 'Lang', FALSE, ${supplierId},
      ${destinations[0]?.id ?? 0}, 'STAGING', 'a-1', now(), 812, 'T-Shirt', 'MEDIUM_FRONT',
      35.6, 18.9, 28.4, 28.4)`.execute(db)
  const { rows: appearances } = await sql<{ id: number }>`INSERT INTO article_spod_appearances
      (article_id, spod_appearance_id, color_name, color_hex, position)
    VALUES (${SHIRT.articleId}, 5, 'Black', '#101010', 1) RETURNING id`.execute(db)
  await sql`INSERT INTO article_variant_identities (id, article_id, article_type)
    VALUES (${SHIRT.variantId}, ${SHIRT.articleId}, 'SPOD')`.execute(db)
  await sql`INSERT INTO article_spod_variants (id, article_id, appearance_id, size_label,
      spod_product_type_id, spod_size_id, spod_variant_id, is_default, active)
    VALUES (${SHIRT.variantId}, ${SHIRT.articleId}, ${appearances[0]?.id ?? 0}, 'M', 812, 1,
      'v-1', TRUE, TRUE)`.execute(db)
}

/** An active percentage coupon of its own for one journey. */
export async function seedPromotion(
  db: Kysely<Tables>,
  code: string,
  {
    percentage = 10,
    usageLimitTotal = null,
    endsAt = null,
  }: { percentage?: number; usageLimitTotal?: number | null; endsAt?: Date | null } = {},
): Promise<number> {
  const { rows } = await sql<{ id: number }>`INSERT INTO promotions (name, discount_type,
      discount_value, coupon_code, coupon_code_normalized, is_active, usage_limit_total, ends_at)
    VALUES (${code}, 'PERCENTAGE', ${percentage}, ${code}, ${code.toUpperCase()}, TRUE,
      ${usageLimitTotal}, ${endsAt})
    RETURNING id`.execute(db)
  return rows[0]?.id ?? 0
}

/** The active cart of `owner` with one line, and the coupon it carries. */
export async function seedCart(
  db: Kysely<Tables>,
  owner: { guestToken: string } | { userId: number },
  {
    line = MUG,
    promotionId = null,
  }: { line?: { articleId: number; variantId: number }; promotionId?: number | null } = {},
): Promise<number> {
  const guestToken = 'guestToken' in owner ? owner.guestToken : null
  const userId = 'userId' in owner ? owner.userId : null
  const { rows } = await sql<{ id: number }>`INSERT INTO carts (guest_session_token, user_id,
      status, promotion_id)
    VALUES (${guestToken}, ${userId}, 'ACTIVE', ${promotionId}) RETURNING id`.execute(db)
  const cartId = rows[0]?.id ?? 0
  await sql`INSERT INTO cart_items (cart_id, article_id, variant_id, quantity, price_cents,
      prompt_price_cents, position)
    VALUES (${cartId}, ${line.articleId}, ${line.variantId}, 1, ${LINE_PRICE_CENTS}, 0, 1)`.execute(
    db,
  )
  return cartId
}

/** The request body the Vue store posts today, `phone: ""` and all. */
export function checkoutBody(overrides: { phone?: string; email?: string } = {}) {
  return {
    shippingAddress: {
      firstName: 'Ada',
      lastName: 'Lovelace',
      street: 'Musterweg',
      houseNumber: '12a',
      postalCode: '80331',
      city: 'München',
      country: 'DE',
      email: overrides.email ?? 'ada@example.org',
      phone: overrides.phone ?? '',
    },
    billingAddress: null,
  }
}
