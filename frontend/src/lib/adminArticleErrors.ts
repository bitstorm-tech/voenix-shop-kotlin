import type { ApiFieldErrors } from '@/lib/api'

/**
 * The field errors of a rejected article write, sorted into the places an editor can show them.
 *
 * An article write reports every problem it blames on the request as a `400` with field errors keyed
 * by the **JSON path** of the offending value: `categoryId`, `supplierId`, `mugDetails.heightMm`,
 * `printFrame.widthPct`, `price.salesVatId`, `tshirtVariants[0].colorHex`. Nothing arrives as a
 * conflict — the reorder is the only article route with a `409` at all.
 *
 * The shape is shared by both types because both editors show errors the same way: on an input, on
 * a variant row, or — for a path no input owns — in a summary next to the form.
 */
export interface AdminArticleSaveErrors {
  /** Messages keyed by the editor field, e.g. `name`, `categoryId`, `heightMm`, `printFrame.topPct`. */
  fields: Record<string, string>
  /** Messages of one submitted variant, keyed by its index in the variant array. */
  variants: Record<number, string>
  /** Messages whose path the editor has no field for. They belong next to the form. */
  other: string[]
}

/**
 * What one editor can render, and how the backend's paths fold onto it.
 *
 * `renderable` is a promise about the editor: every key listed there has a place in the form that
 * shows `fields[key]`. A key the editor cannot render must **not** be listed — its message would be
 * filed under `fields`, no input would show it, and the user would be left with the backend's
 * constant "Validation failed". Unlisted paths land in `other`, which the form shows as a summary.
 */
export interface ArticleErrorSpec {
  /**
   * Matches `mugVariants[3]…` and captures the index. Only a type whose editor writes its variants
   * has one — a t-shirt's variants belong to the sync, so a shirt write cannot be refused for them.
   */
  variantPath?: RegExp
  /** The renderable field keys of the editor. */
  fields: ReadonlySet<string>
  /** The path of the variant array as a whole. It is rendered in the variants section. */
  variantsField?: string
  /** Folds one backend path onto the key the editor renders it under. */
  toField: (path: string) => string
}

/**
 * Everything a mug write can be refused for by name. `dishwasherSafe` is a checkbox with no error
 * slot and `mugDetails` addresses the whole nested object rather than one input, so neither is
 * renderable and both belong in `other`.
 */
const MUG_FIELDS = new Set([
  'name',
  'descriptionShort',
  'descriptionLong',
  'active',
  'categoryId',
  'subcategoryId',
  'supplierId',
  'supplierArticleName',
  'supplierArticleNumber',
  'heightMm',
  'diameterMm',
  'printTemplateWidthMm',
  'printTemplateHeightMm',
  'fillingQuantity',
  'documentFormatWidthMm',
  'documentFormatHeightMm',
  'documentFormatMarginBottomMm',
])

/**
 * The shop-owned half of a synced shirt, and no more. The name, the descriptions, the supplier, and
 * the variants are the partner's (ADR 0003), so a shirt write never carries them and the editor has
 * no input that could show a message about them.
 *
 * The four frame percentages keep their full JSON path as their key, because the calibrator renders
 * one input per percentage and can therefore show the backend's message exactly where the number is
 * typed. `printFrame` itself has no input — the print section shows it as its own alert — but it is
 * renderable and therefore listed.
 */
const TSHIRT_FIELDS = new Set([
  'active',
  'categoryId',
  'subcategoryId',
  'defaultVariantId',
  'printAspectRatio',
  'printFrame',
  'printFrame.leftPct',
  'printFrame.topPct',
  'printFrame.widthPct',
  'printFrame.heightPct',
])

/** How a **mug** write's paths fold onto the mug editor. `mugDetails.heightMm` becomes `heightMm`
 * because the details section names its inputs that way. */
export const MUG_SPEC: ArticleErrorSpec = {
  variantPath: /^mugVariants\[(\d+)\]/,
  variantsField: 'mugVariants',
  fields: MUG_FIELDS,
  toField: (path) =>
    path.startsWith('mugDetails.') ? path.slice('mugDetails.'.length) : collapsePrice(path),
}

/** How a **t-shirt** write's paths fold onto the shirt editor. Unlike the mug's `mugDetails.*`, the
 * four `printFrame.*` paths are kept whole: the calibrator has one input per percentage, so the path
 * is already the name of the input that shows the message. */
export const TSHIRT_SPEC: ArticleErrorSpec = {
  fields: TSHIRT_FIELDS,
  toField: collapsePrice,
}

/**
 * Everything below `price` becomes one `price` message, because the price editor calculates its own
 * inputs and can only report that the price as a whole was refused.
 */
function collapsePrice(path: string): string {
  return path === 'price' || path.startsWith('price.') ? 'price' : path
}

/** Folds the backend's JSON paths of a rejected write onto the fields the editor renders. */
export function mapSaveErrors(
  fieldErrors: ApiFieldErrors,
  spec: ArticleErrorSpec,
): AdminArticleSaveErrors {
  const errors: AdminArticleSaveErrors = { fields: {}, variants: {}, other: [] }

  for (const [path, messages] of Object.entries(fieldErrors)) {
    const message = messages[0]
    if (message === undefined) {
      continue
    }

    const variantMatch = spec.variantPath?.exec(path)
    if (variantMatch) {
      const index = Number(variantMatch[1])
      errors.variants[index] ??= message
      continue
    }

    const field = spec.toField(path)
    const isRenderable = field === 'price' || field === spec.variantsField || spec.fields.has(field)

    if (isRenderable) {
      errors.fields[field] ??= message
      continue
    }

    errors.other.push(message)
  }

  return errors
}

/** Whether any message landed on an input or a variant row, i.e. the form itself shows the problem. */
export function hasFormErrors(errors: AdminArticleSaveErrors): boolean {
  return Object.keys(errors.fields).length > 0 || Object.keys(errors.variants).length > 0
}
