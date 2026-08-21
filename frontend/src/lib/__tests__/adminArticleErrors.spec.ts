import { describe, expect, it } from 'vitest'
import {
  firstMugErrorTab,
  firstTshirtErrorTab,
  mapMugSaveErrors,
  mapTshirtSaveErrors,
} from '@/lib/adminArticleErrors'

describe('mapMugSaveErrors', () => {
  it('puts every reference problem on the field it names', () => {
    const errors = mapMugSaveErrors({
      categoryId: ['Article category does not exist'],
      subcategoryId: ['Article subcategory does not exist in this article category'],
      supplierId: ['Supplier does not exist'],
      mugVariants: ['One or more variants do not belong to this article'],
      price: ['An active article requires a price'],
    })

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
    const errors = mapMugSaveErrors({
      'mugVariants[0].exampleImageFilename': [
        'Example image filename must be the name of an uploaded image',
      ],
      'mugVariants[2].exampleImageFilename': ['Example image does not exist'],
    })

    expect(errors.variants).toEqual({
      0: 'Example image filename must be the name of an uploaded image',
      2: 'Example image does not exist',
    })
    expect(errors.fields).toEqual({})
  })

  it('folds a mug detail path onto the input that shows it', () => {
    const errors = mapMugSaveErrors({
      'mugDetails.heightMm': ['Height must be positive'],
    })

    expect(errors.fields).toEqual({ heightMm: 'Height must be positive' })
  })

  it('collapses every price path onto the price field', () => {
    const errors = mapMugSaveErrors({
      'price.salesVatId': ['VAT does not exist'],
    })

    expect(errors.fields).toEqual({ price: 'VAT does not exist' })
  })

  it('keeps a path the editor has no input for', () => {
    const errors = mapMugSaveErrors({ somethingElse: ['Unexpected'] })

    expect(errors.fields).toEqual({})
    expect(errors.other).toEqual(['Unexpected'])
  })

  it('puts the six formerly swallowed general and detail paths where the form shows them', () => {
    const errors = mapMugSaveErrors({
      active: ['An active article requires a category'],
      supplierArticleName: ['Supplier article name is too long'],
      supplierArticleNumber: ['Supplier article number is too long'],
      'mugDetails.fillingQuantity': ['Filling quantity is too long'],
    })

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
    const errors = mapMugSaveErrors({ [path]: [message] })

    expect(errors.fields).toEqual({})
    expect(errors.other).toEqual([message])
  })
})

describe('firstMugErrorTab', () => {
  it.each([
    [{ categoryId: ['x'] }, 'general'],
    [{ 'mugDetails.heightMm': ['x'] }, 'details'],
    [{ mugVariants: ['x'] }, 'variants'],
    [{ 'mugVariants[1].exampleImageFilename': ['x'] }, 'variants'],
    [{ price: ['x'] }, 'price'],
  ])('opens the tab that owns %o', (fieldErrors, expected) => {
    expect(firstMugErrorTab(mapMugSaveErrors(fieldErrors))).toBe(expected)
  })

  it('opens the earliest tab when several fields were rejected', () => {
    const errors = mapMugSaveErrors({ price: ['x'], categoryId: ['y'] })

    expect(firstMugErrorTab(errors)).toBe('general')
  })

  it('has no tab to open when nothing maps onto a field', () => {
    expect(firstMugErrorTab(mapMugSaveErrors({ somethingElse: ['x'] }))).toBeNull()
  })
})

describe('mapTshirtSaveErrors', () => {
  it('puts every reference problem on the field it names', () => {
    const errors = mapTshirtSaveErrors({
      categoryId: ['Article category does not exist'],
      supplierId: ['Supplier does not exist'],
      printAspectRatio: ['PrintAspectRatio must be one of 16:9, 1:1'],
      sizeChartImageFilename: ['Size chart does not exist'],
      tshirtVariants: ['All variants must share the same SpodProductTypeId'],
      price: ['An active article requires a price'],
    })

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
    const errors = mapTshirtSaveErrors({
      'printFrame.widthPct': ['LeftPct plus WidthPct must be at most 100'],
      'printFrame.topPct': ['TopPct is required'],
    })

    expect(errors.fields).toEqual({
      'printFrame.widthPct': 'LeftPct plus WidthPct must be at most 100',
      'printFrame.topPct': 'TopPct is required',
    })
    expect(errors.other).toEqual([])
  })

  it('routes a variant path to the row it indexes', () => {
    const errors = mapTshirtSaveErrors({
      'tshirtVariants[0].colorHex': ['ColorHex must be a six-digit hex color such as #1a2b3c'],
      'tshirtVariants[3].spodSizeId': ['SpodSizeId is required'],
    })

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
    const errors = mapTshirtSaveErrors({ [path]: [message] })

    expect(errors.fields).toEqual({})
    expect(errors.other).toEqual([message])
  })
})

describe('firstTshirtErrorTab', () => {
  it.each([
    [{ categoryId: ['x'] }, 'general'],
    [{ 'printFrame.leftPct': ['x'] }, 'print'],
    [{ printAspectRatio: ['x'] }, 'print'],
    [{ tshirtVariants: ['x'] }, 'variants'],
    [{ 'tshirtVariants[1].colorHex': ['x'] }, 'variants'],
    [{ price: ['x'] }, 'price'],
  ])('opens the tab that owns %o', (fieldErrors, expected) => {
    expect(firstTshirtErrorTab(mapTshirtSaveErrors(fieldErrors))).toBe(expected)
  })

  it('opens the earliest tab when several fields were rejected', () => {
    const errors = mapTshirtSaveErrors({ price: ['x'], 'printFrame.topPct': ['y'] })

    expect(firstTshirtErrorTab(errors)).toBe('print')
  })

  it('has no tab to open when nothing maps onto a field', () => {
    expect(firstTshirtErrorTab(mapTshirtSaveErrors({ somethingElse: ['x'] }))).toBeNull()
  })
})
