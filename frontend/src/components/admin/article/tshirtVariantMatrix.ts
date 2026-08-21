/**
 * The variant matrix of a t-shirt: colours × sizes, generated instead of typed.
 *
 * A shirt is one garment in every colour and every size a supplier prints it in, so its variant list
 * is a product of two small lists rather than a set of individually described rows. An admin types
 * the colours once, the sizes once, and the SPOD product type once; this module turns the three into
 * the rows the editor shows and the write submits.
 *
 * The generator is a pure function on purpose: it is the one rule of the shirt editor that is worth
 * testing without a DOM, and the component around it stays a form.
 */

/** One colour of the matrix: the name a customer reads and the hex a swatch is painted in. */
export interface TshirtMatrixColor {
  name: string
  hex: string
}

/** What an admin typed into the generator. */
export interface TshirtVariantMatrixSpec {
  colors: TshirtMatrixColor[]
  sizes: string[]
  /**
   * The SPOD product type every generated row names. It is one value for the whole article: the
   * backend rejects a variant array whose entries disagree about it.
   */
  spodProductTypeId: number | null
}

/**
 * One editable row of the variant table.
 *
 * `key` is the list key of the editor and never leaves the browser. `id` is what makes the submitted
 * array a diff: a row that carries one updates that stored variant, a row without one inserts.
 *
 * The three `spod*` ids are `null` while they are unfilled, because the two per-row ids are exactly
 * what an admin still has to look up after generating the matrix.
 */
export interface TshirtVariantRow {
  key: number
  id: number | null
  colorName: string
  colorHex: string
  sizeLabel: string
  spodProductTypeId: number | null
  spodAppearanceId: number | null
  spodSizeId: number | null
  isDefault: boolean
  active: boolean
  exampleImageFilename: string | null
}

/** The pair that identifies a variant of one shirt, and the pair its unique index is built on. */
function pairKey(colorName: string, sizeLabel: string): string {
  return `${colorName.trim().toLowerCase()}\u001f${sizeLabel.trim().toLowerCase()}`
}

/**
 * Reads the colour list of the generator: one colour per line, `Name #rrggbb`.
 *
 * A line without a hex keeps the shop's default white, so a half-typed list still generates and the
 * missing colour is corrected in the row instead of in the textarea.
 */
export function parseMatrixColors(text: string): TshirtMatrixColor[] {
  const colors: TshirtMatrixColor[] = []
  const seen = new Set<string>()

  for (const line of text.split('\n')) {
    const trimmed = line.trim()
    if (trimmed === '') {
      continue
    }

    const match = /^(.*?)[\s,]*(#[0-9a-fA-F]{6})?$/.exec(trimmed)
    const name = (match?.[1] ?? trimmed).trim()
    if (name === '') {
      continue
    }

    const key = name.toLowerCase()
    if (seen.has(key)) {
      continue
    }
    seen.add(key)
    colors.push({ name, hex: (match?.[2] ?? '#ffffff').toLowerCase() })
  }

  return colors
}

/** Reads the size list: comma-, semicolon-, or whitespace-separated, in the order it was typed. */
export function parseMatrixSizes(text: string): string[] {
  const sizes: string[] = []
  const seen = new Set<string>()

  for (const raw of text.split(/[,;\s]+/)) {
    const size = raw.trim()
    if (size === '') {
      continue
    }

    const key = size.toLowerCase()
    if (seen.has(key)) {
      continue
    }
    seen.add(key)
    sizes.push(size)
  }

  return sizes
}

/**
 * Generates the complete variant matrix of a shirt: one row per colour and size, colour by colour.
 *
 * The result is the **complete intended state**, exactly as the write body is. A pair that is
 * already known keeps its row — its stored id, its two SPOD ids, and its example image — so that
 * regenerating after adding one size does not throw away the work done on the other rows. A pair the
 * new matrix does not contain is dropped, which deletes that variant when the article is saved.
 *
 * Every row names the article's one `spodProductTypeId`, and exactly one row is the default: the
 * previous default when its pair survived, the first row otherwise.
 */
export function generateTshirtVariantMatrix(
  spec: TshirtVariantMatrixSpec,
  existing: readonly TshirtVariantRow[],
  nextKey: () => number,
): TshirtVariantRow[] {
  const known = new Map(existing.map((row) => [pairKey(row.colorName, row.sizeLabel), row]))
  const rows: TshirtVariantRow[] = []

  for (const color of spec.colors) {
    for (const sizeLabel of spec.sizes) {
      const previous = known.get(pairKey(color.name, sizeLabel))

      rows.push(
        previous
          ? {
              ...previous,
              colorName: color.name,
              colorHex: color.hex,
              sizeLabel,
              spodProductTypeId: spec.spodProductTypeId,
            }
          : {
              key: nextKey(),
              id: null,
              colorName: color.name,
              colorHex: color.hex,
              sizeLabel,
              spodProductTypeId: spec.spodProductTypeId,
              spodAppearanceId: null,
              spodSizeId: null,
              isDefault: false,
              active: true,
              exampleImageFilename: null,
            },
      )
    }
  }

  return withSingleDefault(rows)
}

/**
 * Makes exactly one row the default, which is what the backend requires of a non-empty array.
 *
 * `preferredKey` wins when it is a row of the list — it is the row an admin just marked. Otherwise
 * the first row that already claims the flag keeps it, and a list without any default gets one.
 */
export function withSingleDefault(
  rows: readonly TshirtVariantRow[],
  preferredKey: number | null = null,
): TshirtVariantRow[] {
  if (rows.length === 0) {
    return []
  }

  const preferred = rows.find((row) => row.key === preferredKey)
  const defaultRow = preferred ?? rows.find((row) => row.isDefault) ?? rows[0]!

  return rows.map((row) => ({ ...row, isDefault: row.key === defaultRow.key }))
}
