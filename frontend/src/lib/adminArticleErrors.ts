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

/** The mug editor tab that owns a field, in the order the tabs are shown. */
export type AdminMugEditorTab = 'general' | 'details' | 'variants' | 'price'

/** The t-shirt editor tab that owns a field, in the order the tabs are shown. */
export type AdminTshirtEditorTab = 'general' | 'print' | 'variants' | 'price'

/**
 * What one editor can render, and how the backend's paths fold onto it.
 *
 * `renderable` is a promise about the editor: every key listed there has a place in the form that
 * shows `fields[key]`. A key the editor cannot render must **not** be listed — its message would be
 * filed under `fields`, no input would show it, and the user would be left with the backend's
 * constant "Validation failed". Unlisted paths land in `other`, which the form shows as a summary.
 */
interface ArticleErrorSpec {
  /** Matches `mugVariants[3]…` / `tshirtVariants[3]…` and captures the index. */
  variantPath: RegExp
  /** The renderable field keys of the editor, per tab, in tab order. */
  tabs: readonly { readonly tab: string; readonly fields: ReadonlySet<string> }[]
  /** The path of the variant array as a whole. It is rendered on the variants tab. */
  variantsField: string
  /** Folds one backend path onto the key the editor renders it under. */
  toField: (path: string) => string
}

/**
 * The general fields both editors share. A shirt has no supplier article name and no supplier
 * article number, so those two are mug-only.
 */
const SHARED_GENERAL_FIELDS = [
  'name',
  'descriptionShort',
  'descriptionLong',
  'active',
  'categoryId',
  'subcategoryId',
  'supplierId',
] as const

const MUG_GENERAL_FIELDS = new Set<string>([
  ...SHARED_GENERAL_FIELDS,
  'supplierArticleName',
  'supplierArticleNumber',
])

// `dishwasherSafe` is a checkbox with no error slot and `mugDetails` addresses the whole nested
// object rather than one input, so neither is renderable and both belong in `other`.
const MUG_DETAILS_FIELDS = new Set([
  'heightMm',
  'diameterMm',
  'printTemplateWidthMm',
  'printTemplateHeightMm',
  'fillingQuantity',
  'documentFormatWidthMm',
  'documentFormatHeightMm',
  'documentFormatMarginBottomMm',
])

const TSHIRT_GENERAL_FIELDS = new Set<string>(SHARED_GENERAL_FIELDS)

/**
 * The print tab of the shirt editor. The four frame percentages keep their full JSON path as their
 * key, because the calibrator renders one input per percentage and can therefore show the backend's
 * message exactly where the number is typed. `printFrame` itself has no input — the calibrator
 * shows it as the tab's own alert — but it is renderable and therefore listed.
 */
const TSHIRT_PRINT_FIELDS = new Set([
  'printAspectRatio',
  'sizeChartImageFilename',
  'printFrame',
  'printFrame.leftPct',
  'printFrame.topPct',
  'printFrame.widthPct',
  'printFrame.heightPct',
])

const MUG_SPEC: ArticleErrorSpec = {
  variantPath: /^mugVariants\[(\d+)\]/,
  variantsField: 'mugVariants',
  tabs: [
    { tab: 'general', fields: MUG_GENERAL_FIELDS },
    { tab: 'details', fields: MUG_DETAILS_FIELDS },
  ],
  toField: (path) =>
    path.startsWith('mugDetails.') ? path.slice('mugDetails.'.length) : collapsePrice(path),
}

const TSHIRT_SPEC: ArticleErrorSpec = {
  variantPath: /^tshirtVariants\[(\d+)\]/,
  variantsField: 'tshirtVariants',
  tabs: [
    { tab: 'general', fields: TSHIRT_GENERAL_FIELDS },
    { tab: 'print', fields: TSHIRT_PRINT_FIELDS },
  ],
  toField: collapsePrice,
}

/**
 * Everything below `price` becomes one `price` message, because the price editor calculates its own
 * inputs and can only report that the price as a whole was refused.
 */
function collapsePrice(path: string): string {
  return path === 'price' || path.startsWith('price.') ? 'price' : path
}

function mapSaveErrors(
  fieldErrors: ApiFieldErrors,
  spec: ArticleErrorSpec,
): AdminArticleSaveErrors {
  const errors: AdminArticleSaveErrors = { fields: {}, variants: {}, other: [] }

  for (const [path, messages] of Object.entries(fieldErrors)) {
    const message = messages[0]
    if (message === undefined) {
      continue
    }

    const variantMatch = spec.variantPath.exec(path)
    if (variantMatch) {
      const index = Number(variantMatch[1])
      errors.variants[index] ??= message
      continue
    }

    const field = spec.toField(path)
    const isRenderable =
      field === 'price' ||
      field === spec.variantsField ||
      spec.tabs.some(({ fields }) => fields.has(field))

    if (isRenderable) {
      errors.fields[field] ??= message
      continue
    }

    errors.other.push(message)
  }

  return errors
}

/** The tab a user has to open to see the first reported problem, or `null` when none is shown. */
function firstErrorTab(errors: AdminArticleSaveErrors, spec: ArticleErrorSpec): string | null {
  const reported = Object.keys(errors.fields)

  for (const { tab, fields } of spec.tabs) {
    if (reported.some((field) => fields.has(field))) {
      return tab
    }
  }

  if (reported.includes(spec.variantsField) || Object.keys(errors.variants).length > 0) {
    return 'variants'
  }

  return reported.includes('price') ? 'price' : null
}

/**
 * Folds the backend's JSON paths of a **mug** write onto the fields the mug editor renders.
 *
 * `mugDetails.heightMm` becomes `heightMm` because the details tab names its inputs that way.
 */
export function mapMugSaveErrors(fieldErrors: ApiFieldErrors): AdminArticleSaveErrors {
  return mapSaveErrors(fieldErrors, MUG_SPEC)
}

/** The mug tab a user has to open to see the first reported problem. */
export function firstMugErrorTab(errors: AdminArticleSaveErrors): AdminMugEditorTab | null {
  return firstErrorTab(errors, MUG_SPEC) as AdminMugEditorTab | null
}

/**
 * Folds the backend's JSON paths of a **t-shirt** write onto the fields the shirt editor renders.
 *
 * Unlike the mug's `mugDetails.*`, the four `printFrame.*` paths are kept whole: the calibrator has
 * one input per percentage, so the path is already the name of the input that shows the message.
 */
export function mapTshirtSaveErrors(fieldErrors: ApiFieldErrors): AdminArticleSaveErrors {
  return mapSaveErrors(fieldErrors, TSHIRT_SPEC)
}

/** The shirt tab a user has to open to see the first reported problem. */
export function firstTshirtErrorTab(errors: AdminArticleSaveErrors): AdminTshirtEditorTab | null {
  return firstErrorTab(errors, TSHIRT_SPEC) as AdminTshirtEditorTab | null
}
