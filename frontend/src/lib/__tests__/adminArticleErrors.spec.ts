import { describe, expect, it } from 'vitest'
import { firstErrorTab, mapSaveErrors, MUG_SPEC, TSHIRT_SPEC } from '@/lib/adminArticleErrors'

describe('mug save errors', () => {
  it('puts every reference problem on the field it names', () => {
    const errors = mapSaveErrors(
      {
        categoryId: ['Article category does not exist'],
        subcategoryId: ['Article subcategory does not exist in this article category'],
        supplierId: ['Supplier does not exist'],
        mugVariants: ['One or more variants do not belong to this article'],
        price: ['An active article requires a price'],
      },
      MUG_SPEC,
    )

    expect(errors.fields).toEqual({
      categoryId: 'Article category does not exist',
      subcategoryId: 'Article subcategory does not exist in this article category',
      supplierId: 'Supplier does not exist',
      mugVariants: 'One or more variants do not belong to this article',
      price: 'An active article requires a price',
    })
    expect(errors.variants).toEqual({})
    expect(errors.other).toEqual([])
  })

  it('routes an example-image path to the variant it indexes', () => {
    const errors = mapSaveErrors(
      {
        'mugVariants[0].exampleImageFilename': [
          'Example image filename must be the name of an uploaded image',
        ],
        'mugVariants[2].exampleImageFilename': ['Example image does not exist'],
      },
      MUG_SPEC,
    )

    expect(errors.variants).toEqual({
      0: 'Example image filename must be the name of an uploaded image',
      2: 'Example image does not exist',
    })
    expect(errors.fields).toEqual({})
  })

  it('folds a mug detail path onto the input that shows it', () => {
    const errors = mapSaveErrors(
      {
        'mugDetails.heightMm': ['Height must be positive'],
      },
      MUG_SPEC,
    )

    expect(errors.fields).toEqual({ heightMm: 'Height must be positive' })
  })

  it('collapses every price path onto the price field', () => {
    const errors = mapSaveErrors(
      {
        'price.salesVatId': ['VAT does not exist'],
      },
      MUG_SPEC,
    )

    expect(errors.fields).toEqual({ price: 'VAT does not exist' })
  })

  it('keeps a path the editor has no input for', () => {
    const errors = mapSaveErrors({ somethingElse: ['Unexpected'] }, MUG_SPEC)

    expect(errors.fields).toEqual({})
    expect(errors.other).toEqual(['Unexpected'])
  })

  it('puts the six formerly swallowed general and detail paths where the form shows them', () => {
    const errors = mapSaveErrors(
      {
        active: ['An active article requires a category'],
        supplierArticleName: ['Supplier article name is too long'],
        supplierArticleNumber: ['Supplier article number is too long'],
        'mugDetails.fillingQuantity': ['Filling quantity is too long'],
      },
      MUG_SPEC,
    )

    expect(errors.fields).toEqual({
      active: 'An active article requires a category',
      supplierArticleName: 'Supplier article name is too long',
      supplierArticleNumber: 'Supplier article number is too long',
      fillingQuantity: 'Filling quantity is too long',
    })
    expect(errors.other).toEqual([])
  })

  // The editor has no error slot for the dishwasher checkbox and no input at all for the nested
  // object as a whole, so filing these under `fields` would hide them. They go into the summary.
  it.each([
    ['mugDetails.dishwasherSafe', 'Dishwasher safe must be a boolean'],
    ['mugDetails', 'Mug details are incomplete'],
    ['dishwasherSafe', 'Dishwasher safe must be a boolean'],
  ])('routes %s into the summary instead of onto an invisible field', (path, message) => {
    const errors = mapSaveErrors({ [path]: [message] }, MUG_SPEC)

    expect(errors.fields).toEqual({})
    expect(errors.other).toEqual([message])
  })
})

describe('first mug error tab', () => {
  it.each([
    [{ categoryId: ['x'] }, 'general'],
    [{ 'mugDetails.heightMm': ['x'] }, 'details'],
    [{ mugVariants: ['x'] }, 'variants'],
    [{ 'mugVariants[1].exampleImageFilename': ['x'] }, 'variants'],
    [{ price: ['x'] }, 'price'],
  ])('opens the tab that owns %o', (fieldErrors, expected) => {
    expect(firstErrorTab(mapSaveErrors(fieldErrors, MUG_SPEC), MUG_SPEC)).toBe(expected)
  })

  it('opens the earliest tab when several fields were rejected', () => {
    const errors = mapSaveErrors({ price: ['x'], categoryId: ['y'] }, MUG_SPEC)

    expect(firstErrorTab(errors, MUG_SPEC)).toBe('general')
  })

  it('has no tab to open when nothing maps onto a field', () => {
    expect(firstErrorTab(mapSaveErrors({ somethingElse: ['x'] }, MUG_SPEC), MUG_SPEC)).toBeNull()
  })
})

describe('t-shirt save errors', () => {
  it('puts every reference problem on the field it names', () => {
    const errors = mapSaveErrors(
      {
        categoryId: ['Article category does not exist'],
        supplierId: ['Supplier does not exist'],
        printAspectRatio: ['PrintAspectRatio must be one of 16:9, 1:1'],
        sizeChartImageFilename: ['Size chart does not exist'],
        tshirtVariants: ['All variants must share the same SpodProductTypeId'],
        price: ['An active article requires a price'],
      },
      TSHIRT_SPEC,
    )

    expect(errors.fields).toEqual({
      categoryId: 'Article category does not exist',
      supplierId: 'Supplier does not exist',
      printAspectRatio: 'PrintAspectRatio must be one of 16:9, 1:1',
      sizeChartImageFilename: 'Size chart does not exist',
      tshirtVariants: 'All variants must share the same SpodProductTypeId',
      price: 'An active article requires a price',
    })
    expect(errors.other).toEqual([])
  })

  // The calibrator has one input per percentage, so the path is already the name of the input that
  // shows the message and is kept whole.
  it('keeps the four print-frame paths as the calibrator spells them', () => {
    const errors = mapSaveErrors(
      {
        'printFrame.widthPct': ['LeftPct plus WidthPct must be at most 100'],
        'printFrame.topPct': ['TopPct is required'],
      },
      TSHIRT_SPEC,
    )

    expect(errors.fields).toEqual({
      'printFrame.widthPct': 'LeftPct plus WidthPct must be at most 100',
      'printFrame.topPct': 'TopPct is required',
    })
    expect(errors.other).toEqual([])
  })

  it('routes a variant path to the row it indexes', () => {
    const errors = mapSaveErrors(
      {
        'tshirtVariants[0].colorHex': ['ColorHex must be a six-digit hex color such as #1a2b3c'],
        'tshirtVariants[3].spodSizeId': ['SpodSizeId is required'],
      },
      TSHIRT_SPEC,
    )

    expect(errors.variants).toEqual({
      0: 'ColorHex must be a six-digit hex color such as #1a2b3c',
      3: 'SpodSizeId is required',
    })
    expect(errors.fields).toEqual({})
  })

  // A mug path is not a shirt path: the shirt editor has no `mugVariants` and no `mugDetails`, so
  // such a message belongs in the summary rather than on an input that does not exist.
  it.each([
    ['mugVariants', 'One or more variants do not belong to this article'],
    ['mugDetails.heightMm', 'Height must be positive'],
  ])('routes the mug path %s into the summary', (path, message) => {
    const errors = mapSaveErrors({ [path]: [message] }, TSHIRT_SPEC)

    expect(errors.fields).toEqual({})
    expect(errors.other).toEqual([message])
  })
})

describe('first t-shirt error tab', () => {
  it.each([
    [{ categoryId: ['x'] }, 'general'],
    [{ 'printFrame.leftPct': ['x'] }, 'print'],
    [{ printAspectRatio: ['x'] }, 'print'],
    [{ tshirtVariants: ['x'] }, 'variants'],
    [{ 'tshirtVariants[1].colorHex': ['x'] }, 'variants'],
    [{ price: ['x'] }, 'price'],
  ])('opens the tab that owns %o', (fieldErrors, expected) => {
    expect(firstErrorTab(mapSaveErrors(fieldErrors, TSHIRT_SPEC), TSHIRT_SPEC)).toBe(expected)
  })

  it('opens the earliest tab when several fields were rejected', () => {
    const errors = mapSaveErrors({ price: ['x'], 'printFrame.topPct': ['y'] }, TSHIRT_SPEC)

    expect(firstErrorTab(errors, TSHIRT_SPEC)).toBe('print')
  })

  it('has no tab to open when nothing maps onto a field', () => {
    expect(
      firstErrorTab(mapSaveErrors({ somethingElse: ['x'] }, TSHIRT_SPEC), TSHIRT_SPEC),
    ).toBeNull()
  })
})
