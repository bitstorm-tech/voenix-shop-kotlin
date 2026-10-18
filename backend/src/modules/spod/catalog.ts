import { z } from 'zod'

/**
 * The merchant's backoffice catalog as this shop reads it: what `articles` and `sizeChart` answer,
 * plus `parseColorHex`, the one piece of interpretation the answers need.
 *
 * Two decisions run through every schema here (SPOD sync decisions 6 and 4). **Every field except
 * an id has a default**: a sync that refuses to decode a page writes nothing at all, so a missing
 * field becomes an empty value the sync can judge. **Ids are strings**: the partner answers them as
 * numbers in some fields and as quoted strings in others, and both read into the same string. The
 * three product ids are numbers instead, because an order is placed with them.
 */

/** An id the partner writes as a number or as a string, read as a string either way. */
export const lenientId = z.union([z.string(), z.number()]).transform(String)

/** A product id the partner writes as a number or as a quoted number, read as a number. */
const productId = z.union([
  z.number().int(),
  z
    .string()
    .regex(/^-?\d+$/)
    .transform(Number),
])

const spodCatalogVariant = z.object({
  id: lenientId,
  productTypeId: productId.default(0),
  productTypeName: z.string().default(''),
  appearanceId: productId.default(0),
  appearanceName: z.string().default(''),
  appearanceColorValue: z.string().nullable().default(null),
  sizeId: productId.default(0),
  sizeName: z.string().default(''),
  sku: z.string().nullable().default(null),
})

const spodCatalogImage = z.object({
  id: lenientId,
  productId: productId.default(0),
  appearanceId: productId.nullable().default(null),
  perspective: z.string().nullable().default(null),
  imageUrl: z.string().default(''),
})

const spodCatalogArticle = z.object({
  id: lenientId,
  title: z.string().default(''),
  description: z.string().default(''),
  variants: z.array(spodCatalogVariant).default([]),
  images: z.array(spodCatalogImage).default([]),
})

/**
 * One page of the merchant's articles. `count` is the size of the whole catalog, not of the page,
 * and the one field without a usable default: a page without it says nothing about completeness,
 * so it reads as `null` and the sync gives up on it rather than deactivating on an invented number.
 */
export const spodCatalogPage = z.object({
  items: z.array(spodCatalogArticle).default([]),
  count: z.number().int().nullable().default(null),
})

/** The size chart of a product type, in the one field this shop stores. */
export const spodSizeChart = z.object({ sizeImageUrl: z.string().nullable().default(null) })

export type SpodCatalogPage = z.output<typeof spodCatalogPage>
export type SpodCatalogArticle = z.output<typeof spodCatalogArticle>
/**
 * One colour × size combination. `productTypeId`, `appearanceId` and `sizeId` are the triple an
 * order is placed with and the key a variant is matched on, never `id` (SPOD sync decision 4).
 */
export type SpodCatalogVariant = z.output<typeof spodCatalogVariant>
/** One image of an article: which colour, which side, and the Spreadshirt product it renders. */
export type SpodCatalogImage = z.output<typeof spodCatalogImage>
export type SpodSizeChart = z.output<typeof spodSizeChart>

/** One downloaded image: its bytes and the content type they were served with, both bounded. */
export interface SpodBinary {
  bytes: Uint8Array
  contentType: string
}

const LOOKUP_ID_SEGMENT = '/products/lookupId/'

/**
 * The URL this image is fetched from. The partner writes the image server's URL with the literal
 * placeholder `lookupId` where the product id belongs, and the CDN answers that with a `404`; the
 * id is in the same answer as `productId`. A URL without the placeholder, or an image without a
 * product id, is used as it is (seen on staging 2026-08-25, still so on 2026-10-06).
 */
export function downloadUrl(image: Pick<SpodCatalogImage, 'productId' | 'imageUrl'>): string {
  return image.productId > 0
    ? image.imageUrl.replace(LOOKUP_ID_SEGMENT, `/products/${String(image.productId)}/`)
    : image.imageUrl
}

const HEX_COLOR = /^(?:[0-9a-f]{3}|[0-9a-f]{6})$/i

/**
 * The partner's `appearanceColorValue` as a lowercase `#rrggbb`, or `null` when it is not one. A
 * two-tone garment lists its colours separated by commas and only the first is taken; the `#` is
 * optional and three-digit hex is expanded. Anything else is no colour at all, never a guess.
 */
export function parseColorHex(value: string | null): string | null {
  if (value === null) return null
  const first = (value.split(',', 1)[0] ?? '').trim()
  const digits = first.startsWith('#') ? first.slice(1) : first
  if (!HEX_COLOR.test(digits)) return null
  const sixDigits = digits.length === 3 ? digits.replace(/./g, '$&$&') : digits
  return `#${sixDigits.toLowerCase()}`
}
