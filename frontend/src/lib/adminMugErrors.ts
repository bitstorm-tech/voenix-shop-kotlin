import type { ApiFieldErrors } from '@/lib/api'

/**
 * The field errors of a rejected mug write, sorted into the places the editor can show them.
 *
 * A mug write reports every problem it blames on the request as a `400` with field errors keyed by
 * the **JSON path** of the offending value: `categoryId`, `supplierId`, `mugDetails.heightMm`,
 * `price.salesVatId`, `mugVariants[0].exampleImageFilename`. Nothing arrives as a conflict — the
 * reorder is the only mug route with a `409` at all.
 */
export interface AdminMugSaveErrors {
  /** Messages keyed by the editor field: `name`, `categoryId`, `heightMm`, `price`, `mugVariants`. */
  fields: Record<string, string>
  /** Messages of one submitted variant, keyed by its index in the `mugVariants` array. */
  variants: Record<number, string>
  /** Messages whose path the editor has no field for. They belong next to the form. */
  other: string[]
}

/** The editor tab that owns a field, in the order the tabs are shown. */
export type AdminMugEditorTab = 'general' | 'details' | 'variants' | 'price'

const GENERAL_FIELDS = new Set([
  'name',
  'descriptionShort',
  'descriptionLong',
  'active',
  'categoryId',
  'subcategoryId',
  'supplierId',
  'supplierArticleName',
  'supplierArticleNumber',
])

const DETAILS_FIELDS = new Set([
  'heightMm',
  'diameterMm',
  'printTemplateWidthMm',
  'printTemplateHeightMm',
  'fillingQuantity',
  'dishwasherSafe',
  'documentFormatWidthMm',
  'documentFormatHeightMm',
  'documentFormatMarginBottomMm',
  'mugDetails',
])

const VARIANT_PATH = /^mugVariants\[(\d+)\]/

/**
 * Folds the backend's JSON paths onto the fields the editor renders.
 *
 * `mugDetails.heightMm` becomes `heightMm` because the details tab names its inputs that way, and
 * everything below `price` becomes one `price` message because the price editor calculates its own
 * inputs and can only report that the price as a whole was refused.
 */
export function mapMugSaveErrors(fieldErrors: ApiFieldErrors): AdminMugSaveErrors {
  const errors: AdminMugSaveErrors = { fields: {}, variants: {}, other: [] }

  for (const [path, messages] of Object.entries(fieldErrors)) {
    const message = messages[0]
    if (message === undefined) {
      continue
    }

    const variantMatch = VARIANT_PATH.exec(path)
    if (variantMatch) {
      const index = Number(variantMatch[1])
      errors.variants[index] ??= message
      continue
    }

    const field = path === 'price' || path.startsWith('price.') ? 'price' : stripDetailsPrefix(path)
    if (
      field === 'price' ||
      field === 'mugVariants' ||
      GENERAL_FIELDS.has(field) ||
      DETAILS_FIELDS.has(field)
    ) {
      errors.fields[field] ??= message
      continue
    }

    errors.other.push(message)
  }

  return errors
}

/** The tab a user has to open to see the first reported problem, or `null` when none is shown. */
export function firstMugErrorTab(errors: AdminMugSaveErrors): AdminMugEditorTab | null {
  const fields = Object.keys(errors.fields)

  if (fields.some((field) => GENERAL_FIELDS.has(field))) {
    return 'general'
  }

  if (fields.some((field) => DETAILS_FIELDS.has(field))) {
    return 'details'
  }

  if (fields.includes('mugVariants') || Object.keys(errors.variants).length > 0) {
    return 'variants'
  }

  return fields.includes('price') ? 'price' : null
}

function stripDetailsPrefix(path: string) {
  return path.startsWith('mugDetails.') ? path.slice('mugDetails.'.length) : path
}
