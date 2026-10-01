import { computed, ref, toValue, type MaybeRefOrGetter } from 'vue'
import { variantExampleImageUrl } from '@/lib/variantExampleImage'
import type { TshirtDto, TshirtVariantDto } from '@/stores/shop/catalog'

/**
 * The state every product card alternative on the demo page shares: which variant is active, the
 * shirt's colours (one per colour, not one per variant), and its sizes in the order people read
 * them. The demo cards own their selection themselves, so they can be compared side by side
 * without a parent view wiring each one up.
 */

/** The order a shop lists sizes in. A size that is not listed keeps its catalog order, at the end. */
const SIZE_RANK: Record<string, number> = {
  XXS: 0,
  XS: 1,
  S: 2,
  M: 3,
  L: 4,
  XL: 5,
  XXL: 6,
  '2XL': 6,
  XXXL: 7,
  '3XL': 7,
  '4XL': 8,
  '5XL': 9,
}

export function sortSizes(sizes: string[]): string[] {
  const rank = (size: string) => SIZE_RANK[size.toUpperCase()] ?? Number.MAX_SAFE_INTEGER
  return sizes
    .map((size, index) => ({ size, index }))
    .sort((a, b) => rank(a.size) - rank(b.size) || a.index - b.index)
    .map(({ size }) => size)
}

export interface ShirtColor {
  name: string
  hex: string
  selected: boolean
}

export function useTshirtCardModel(source: MaybeRefOrGetter<TshirtDto>) {
  const article = computed(() => toValue(source))

  const activeVariantId = ref<number | null>(
    (article.value.variants.find((variant) => variant.isDefault) ?? article.value.variants[0])
      ?.id ?? null,
  )

  const activeVariant = computed<TshirtVariantDto | null>(
    () => article.value.variants.find((variant) => variant.id === activeVariantId.value) ?? null,
  )

  const colors = computed<ShirtColor[]>(() => {
    const byName = new Map<string, ShirtColor>()
    for (const variant of article.value.variants) {
      if (!byName.has(variant.colorName)) {
        byName.set(variant.colorName, {
          name: variant.colorName,
          hex: variant.colorHex,
          selected: variant.colorName === activeVariant.value?.colorName,
        })
      }
    }
    return [...byName.values()]
  })

  const sizes = computed(() =>
    sortSizes([...new Set(article.value.variants.map((variant) => variant.size))]),
  )

  /** "S – 5XL" for a range, the one size for a single one, `null` for none. */
  const sizeRange = computed(() => {
    const list = sizes.value
    if (list.length === 0) return null
    if (list.length === 1) return list[0]!
    return `${list[0]} – ${list[list.length - 1]}`
  })

  const activeColor = computed(() => activeVariant.value?.colorHex ?? '#cccccc')

  const imageUrl = computed(() =>
    activeVariant.value?.exampleImageFilename
      ? variantExampleImageUrl('TSHIRT', activeVariant.value.exampleImageFilename, 400)
      : null,
  )

  /** Selecting a colour keeps the selected size when that colour is offered in it. */
  function selectColor(colorName: string) {
    const candidates = article.value.variants.filter((variant) => variant.colorName === colorName)
    const next =
      candidates.find((variant) => variant.size === activeVariant.value?.size) ?? candidates[0]
    if (next) activeVariantId.value = next.id
  }

  return { activeVariant, activeColor, colors, sizes, sizeRange, imageUrl, selectColor }
}
