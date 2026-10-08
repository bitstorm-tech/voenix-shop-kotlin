/**
 * The order a shop lists clothing sizes in. The catalog keeps sizes in the order the variants were
 * created, which is rarely the order a customer reads them ("S · 3XL · L · M"). A size this table
 * does not know - a children's size such as "110/116" - keeps its catalog order, after the known ones.
 */
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

export function sortSizes(sizes: readonly string[]): string[] {
  const rank = (size: string) => SIZE_RANK[size.toUpperCase()] ?? Number.MAX_SAFE_INTEGER

  return sizes
    .map((size, index) => ({ size, index }))
    .sort((a, b) => rank(a.size) - rank(b.size) || a.index - b.index)
    .map(({ size }) => size)
}

/** "S – 5XL" for several sizes, the size itself for one, `null` for none. */
export function sizeRange(sizes: readonly string[]): string | null {
  const sorted = sortSizes(sizes)
  if (sorted.length === 0) return null
  if (sorted.length === 1) return sorted[0]!
  return `${sorted[0]} – ${sorted[sorted.length - 1]}`
}
