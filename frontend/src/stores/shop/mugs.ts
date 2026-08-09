import { ref, shallowRef } from 'vue'
import { defineStore } from 'pinia'
import { fetchJson } from '@/lib/api'
import { formatPrice } from '@/lib/formatPrice'

/**
 * One variant a customer can order. The storefront never receives an inactive variant, so the
 * `active` flag of the admin contract has no counterpart here.
 */
export interface MugVariantDto {
  id: number
  name: string
  outsideColorCode: string
  insideColorCode: string
  isDefault: boolean
  exampleImageFilename: string | null
}

/** The physical description of a mug. It is one value: a mug either has all of it or none. */
export interface MugDetailsDto {
  heightMm: number
  diameterMm: number
  printTemplateWidthMm: number
  printTemplateHeightMm: number
  fillingQuantity: string | null
  dishwasherSafe: boolean
  documentFormatWidthMm: number | null
  documentFormatHeightMm: number | null
  documentFormatMarginBottomMm: number | null
}

/**
 * One mug as the storefront sees it: the admin mug without what a customer may not see. There are
 * no supplier fields and no `active` flags, and the list only holds mugs that are buyable.
 *
 * `categoryId`, `mugDetails`, and `price` are always present, because the database refuses an
 * active mug without them. `price` is the gross sales total in integer cents; a `0` is a real
 * calculated price, not the legacy placeholder for a missing one.
 */
export interface MugDto {
  id: number
  position: number
  name: string
  descriptionShort: string
  descriptionLong: string
  categoryId: number
  subcategoryId: number | null
  price: number
  mugDetails: MugDetailsDto
  variants: MugVariantDto[]
}

function compareMugsByPosition(a: MugDto, b: MugDto): number {
  return a.position - b.position || a.id - b.id
}

function compareMugsByName(a: MugDto, b: MugDto): number {
  return a.name.localeCompare(b.name) || a.id - b.id
}

export const useMugsStore = defineStore('mugs', () => {
  const mugs = ref<MugDto[]>([])
  const isLoading = shallowRef(false)
  const error = shallowRef<string | null>(null)
  const hasFetched = shallowRef(false)

  const STALE_MS = 5 * 60 * 1000
  let lastFetchedAt = 0

  async function fetchMugs() {
    if (isLoading.value) return

    const now = Date.now()
    const isStale = now - lastFetchedAt > STALE_MS
    const isFirstLoad = !hasFetched.value

    if (hasFetched.value && !isStale) return

    if (isFirstLoad) {
      isLoading.value = true
      error.value = null
    }

    try {
      mugs.value = await fetchJson<MugDto[]>('/api/articles/mugs')
      hasFetched.value = true
      lastFetchedAt = now
      error.value = null
    } catch (err) {
      if (isFirstLoad) error.value = err instanceof Error ? err.message : 'Unknown error'
    } finally {
      if (isFirstLoad) isLoading.value = false
    }
  }

  function getDisplayMugs(
    categoryId: number | null,
    subcategoryId: number | null = null,
  ): MugDto[] {
    const hasActiveFilter = categoryId !== null || subcategoryId !== null
    const displayedMugs = hasActiveFilter
      ? mugs.value.filter(
          (mug) =>
            (categoryId === null || mug.categoryId === categoryId) &&
            (subcategoryId === null || mug.subcategoryId === subcategoryId),
        )
      : [...mugs.value]

    return displayedMugs.sort(hasActiveFilter ? compareMugsByName : compareMugsByPosition)
  }

  function getMugById(id: number): MugDto | undefined {
    return mugs.value.find((mug) => mug.id === id)
  }

  function upsertMug(mug: MugDto) {
    const existingIndex = mugs.value.findIndex((item) => item.id === mug.id)

    if (existingIndex === -1) {
      mugs.value.push(mug)
      return
    }

    mugs.value[existingIndex] = mug
  }

  return {
    mugs,
    isLoading,
    error,
    fetchMugs,
    getDisplayMugs,
    getMugById,
    upsertMug,
    formatPrice,
  }
})
