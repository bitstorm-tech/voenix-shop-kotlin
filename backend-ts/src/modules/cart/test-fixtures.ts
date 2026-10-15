import { mkdtemp } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { sql, type Kysely } from 'kysely'
import { catalogKey, type ArticleVariantReference, type CatalogVariant } from '@/modules/article'
import { createImageModule } from '@/modules/image'
import { createOrderModule } from '@/modules/order'
import { createPromotionModule } from '@/modules/promotion'
import type { Tables } from '@/platform/db/database'
import { testAuth } from '@test-support/http'
import { createCartModule } from './index'

/**
 * What the cart tests share: the module wired to the real promotion codes, the real order reads,
 * and the real private image storage (in a temporary root), with plain-object fakes for the two
 * catalogs a test edits, and the rows a test seeds with SQL. Test-only.
 */

const { guestTokens } = testAuth

export const MUG: CatalogVariant = {
  articleType: 'MUG',
  articleName: 'Classic mug',
  variantName: 'White',
  purchasable: true,
  grossSalesPriceCents: 1490,
  supplierId: 3,
  supplierArticleNumber: 'MUG-001',
  printTemplateWidthMm: 200,
  printTemplateHeightMm: 80,
  documentFormatWidthMm: 239,
  documentFormatHeightMm: 99,
  documentFormatMarginBottomMm: 5,
  outsideColorCode: '#ffffff',
  insideColorCode: '#ff0000',
  spodProduct: null,
  spodHotspot: null,
}

export const SPOD: CatalogVariant = {
  ...MUG,
  articleType: 'SPOD',
  articleName: 'Shirt Organic',
  variantName: 'Black / M',
  grossSalesPriceCents: 2500,
  outsideColorCode: null,
  insideColorCode: null,
  spodProduct: { productTypeId: 812, appearanceId: 2, sizeId: 3 },
  spodHotspot: 'MEDIUM_FRONT',
}

/** Article 1 / variant 1 is a mug, 2 / 5 a SPOD shirt; 1 / 6 exists in the database only. */
export const MUG_REF = { articleId: 1, variantId: 1 }
export const SPOD_REF = { articleId: 2, variantId: 5 }
export const UNKNOWN_REF = { articleId: 1, variantId: 6 }
export const PROMPT_ID = 4

/** The two catalogs as plain objects; a test edits `variants` and `promptPrices` in place. */
export function fakeCatalogs() {
  const variants = new Map<string, CatalogVariant>([
    [catalogKey(MUG_REF), MUG],
    [catalogKey(SPOD_REF), SPOD],
  ])
  const promptPrices = new Map<number, number>([[PROMPT_ID, 500]])
  return {
    variants,
    promptPrices,
    articleCatalog: {
      find: (references: Iterable<ArticleVariantReference>) =>
        Promise.resolve(
          new Map(
            [...references].flatMap((reference) => {
              const variant = variants.get(catalogKey(reference))
              return variant === undefined ? [] : [[catalogKey(reference), variant] as const]
            }),
          ),
        ),
    },
    promptCatalog: {
      findSalesGrossPriceCents: (ids: Iterable<number>) =>
        Promise.resolve(
          new Map(
            [...ids].flatMap((id) => {
              const price = promptPrices.get(id)
              return price === undefined ? [] : [[id, price] as const]
            }),
          ),
        ),
    },
  }
}

/** The module under test with its fakes and its private storage. */
export async function cartModuleFor(db: Kysely<Tables>) {
  const root = await mkdtemp(join(tmpdir(), 'cart-test-'))
  const images = createImageModule({
    settings: {
      publicRoot: join(root, 'public'),
      privateRoot: join(root, 'private'),
      cacheRoot: join(root, 'cache'),
    },
    guestTokens,
  })
  const promotionCodes = createPromotionModule({ db }).promotionCodes
  const { orders } = createOrderModule({
    db,
    settings: {
      mollie: { apiKey: '', redirectUrl: '', webhookUrl: '', webhookSecret: 'unused-secret' },
      frontendBaseUrl: 'https://shop.voenix.test',
    },
    articleCatalog: { find: () => Promise.resolve(new Map()) },
    promotionCodes,
    // The order reads are what the cart uses; nothing here places an order or sends its mail.
    email: { outbox: { enqueue: () => Promise.reject(new Error('The cart tests send no mail')) } },
    images,
    guestTokens,
    mollie: {
      create: () => Promise.reject(new Error('The cart tests start no payment')),
      find: () => Promise.resolve(null),
      cancel: () => Promise.resolve(false),
    },
  })
  const catalogs = fakeCatalogs()
  const cart = createCartModule({
    db,
    articleCatalog: catalogs.articleCatalog,
    promptCatalog: catalogs.promptCatalog,
    promotionCodes,
    orders,
    images,
    guestTokens,
  })
  return { ...cart, ...catalogs, promotionCodes, privateStorage: images.privateStorage, root }
}

/**
 * Empties every table the cart tests write and seeds the article identities the fake catalog
 * answers for, one prompt, and the users 7 and 8.
 */
export async function resetCarts(db: Kysely<Tables>): Promise<void> {
  await sql`TRUNCATE orders, promotion_redemptions, promotion_reservations, promotions,
    print_images, carts, users, article_identities, prompts, prompt_categories
    RESTART IDENTITY CASCADE`.execute(db)
  await sql`INSERT INTO article_identities (id, article_type) VALUES (1, 'MUG'), (2, 'SPOD')`.execute(
    db,
  )
  await sql`INSERT INTO article_variant_identities (id, article_id, article_type)
    VALUES (1, 1, 'MUG'), (5, 2, 'SPOD'), (6, 1, 'MUG')`.execute(db)
  await sql`INSERT INTO prompt_categories (id, name, position, active)
    VALUES (1, 'Portraits', 1, TRUE)`.execute(db)
  await sql`INSERT INTO prompts (id, position, title, prompt_text, category_id, active, archived)
    VALUES (${PROMPT_ID}, 1, 'Portrait', 'text', 1, TRUE, FALSE)`.execute(db)
  await sql`INSERT INTO users (id, email, password_hash)
    VALUES (7, 'seven@example.com', 'v1$1$salt$hash'), (8, 'eight@example.com', 'v1$1$salt$hash')`.execute(
    db,
  )
}

/** A promotion; an active 10 % coupon `SAVE10` unless `overrides` says otherwise. */
export async function seedPromotion(
  db: Kysely<Tables>,
  overrides: {
    code?: string
    active?: boolean
    endsAt?: Date | null
    usageLimitTotal?: number | null
    usageLimitPerUser?: number | null
  } = {},
): Promise<number> {
  const code = overrides.code ?? 'SAVE10'
  const { rows } = await sql<{ id: number }>`INSERT INTO promotions (name, discount_type,
      discount_value, coupon_code, coupon_code_normalized, ends_at, usage_limit_total,
      usage_limit_per_user, is_active)
    VALUES ('Summer', 'PERCENTAGE', 10.00, ${code}, ${code.toUpperCase()},
      ${overrides.endsAt ?? null}, ${overrides.usageLimitTotal ?? null},
      ${overrides.usageLimitPerUser ?? null}, ${overrides.active ?? true})
    RETURNING id`.execute(db)
  return rows[0]?.id ?? 0
}

/**
 * A placed order of one line, owned by `owner`, and the id of that line: the ordered references a
 * reorder starts from.
 */
export async function seedOrderItem(
  db: Kysely<Tables>,
  owner: { guestToken: string | null; userId: number | null },
  line: {
    articleId: number
    variantId: number
    promptId: number | null
    printImageId: number | null
  },
): Promise<number> {
  const { rows: carts } = await sql<{ id: number }>`INSERT INTO carts
      (guest_session_token, user_id, status)
    VALUES (${owner.userId === null ? owner.guestToken : null}, ${owner.userId}, 'CHECKED_OUT')
    RETURNING id`.execute(db)
  const { rows: orders } = await sql<{ id: number }>`INSERT INTO orders (cart_id,
      guest_session_token, user_id, access_token, status,
      shipping_first_name, shipping_last_name, shipping_street, shipping_house_number,
      shipping_postal_code, shipping_city, shipping_country,
      billing_first_name, billing_last_name, billing_street, billing_house_number,
      billing_postal_code, billing_city, billing_country,
      email, subtotal_cents, shipping_cost_cents, discount_cents, total_cents)
    VALUES (${carts[0]?.id ?? 0}, ${owner.guestToken}, ${owner.userId},
      ${`token-${String(Math.random())}`}, 'PAID',
      'Erika', 'Musterfrau', 'Lieferweg', '5', '20095', 'Hamburg', 'DE',
      'Erika', 'Musterfrau', 'Lieferweg', '5', '20095', 'Hamburg', 'DE',
      'kunde@example.com', 990, 490, 0, 1480)
    RETURNING id`.execute(db)
  const { rows } = await sql<{ id: number }>`INSERT INTO order_items (order_id, position,
      article_id, variant_id, article_type, article_name, variant_name, quantity, price_cents,
      prompt_id, print_image_id)
    VALUES (${orders[0]?.id ?? 0}, 1, ${line.articleId}, ${line.variantId}, 'MUG', 'Classic mug',
      'White', 1, 990, ${line.promptId}, ${line.printImageId})
    RETURNING id`.execute(db)
  return rows[0]?.id ?? 0
}
