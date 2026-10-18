import { describe, expect, test } from 'bun:test'
import type { z } from 'zod'
import { validationFailed } from '@/platform/http/respond'
import { articleCategoryInput, articleSubcategoryInput } from './category/routes'
import { mugArticleInput } from './mug/routes'
import { reorderInput } from './routes'
import { spodArticleInput } from './spod-article/routes'

const errorsOf = (schema: z.ZodType, input: unknown) => {
  const result = schema.safeParse(input)
  return result.success ? {} : validationFailed(result.error).errors
}

describe('articleCategoryInput', () => {
  test('trims, turns a blank description into null, and defaults active to true', () => {
    expect(articleCategoryInput.parse({ name: ' Mugs ', description: '  ' })).toEqual({
      name: 'Mugs',
      description: null,
      active: true,
    })
  })

  test('reports the name and description rules', () => {
    expect(errorsOf(articleCategoryInput, {})).toEqual({ name: ['Name is required'] })
    expect(errorsOf(articleCategoryInput, { name: '   ' })).toEqual({ name: ['Name is required'] })
    expect(
      errorsOf(articleCategoryInput, { name: 'A'.repeat(201), description: 'D'.repeat(1001) }),
    ).toEqual({
      name: ['Name must be at most 200 characters'],
      description: ['Description must be at most 1000 characters'],
    })
    expect(errorsOf(articleCategoryInput, { name: 'Mugs', position: 3 })).toEqual({
      position: ['Unknown property'],
    })
  })
})

describe('articleSubcategoryInput', () => {
  test('normalizes texts and the example image name', () => {
    expect(
      articleSubcategoryInput.parse({
        categoryId: 7,
        name: ' Classic ',
        exampleImageFilename: ' ',
      }),
    ).toEqual({
      categoryId: 7,
      name: 'Classic',
      description: null,
      exampleImageFilename: null,
      active: true,
    })
  })

  test('reports the category id and the text rules', () => {
    expect(errorsOf(articleSubcategoryInput, {})).toEqual({
      categoryId: ['CategoryId is required'],
      name: ['Name is required'],
    })
    expect(errorsOf(articleSubcategoryInput, { categoryId: 0, name: 'A'.repeat(201) })).toEqual({
      categoryId: ['CategoryId must be positive'],
      name: ['Name must be at most 200 characters'],
    })
  })
})

describe('reorderInput', () => {
  test('requires two positive, different ids', () => {
    expect(reorderInput.parse({ sourceId: 2, targetId: 1 })).toEqual({ sourceId: 2, targetId: 1 })
    expect(errorsOf(reorderInput, {})).toEqual({
      sourceId: ['SourceId is required'],
      targetId: ['TargetId is required'],
    })
    expect(errorsOf(reorderInput, { sourceId: 0, targetId: -1 })).toEqual({
      sourceId: ['SourceId must be positive'],
      targetId: ['TargetId must be positive'],
    })
    expect(errorsOf(reorderInput, { sourceId: 3, targetId: 3 })).toEqual({
      targetId: ['TargetId must be different from SourceId'],
    })
  })
})

const mug = {
  name: ' Classic mug ',
  descriptionShort: 'A mug',
  descriptionLong: 'A classic white mug',
  mugVariants: [
    { name: 'White', insideColorCode: '#fff', outsideColorCode: '#fff', isDefault: true },
  ],
}
const details = {
  heightMm: 95,
  diameterMm: 82,
  printTemplateWidthMm: 200,
  printTemplateHeightMm: 90,
}

describe('mugArticleInput', () => {
  test('normalizes and fills the defaults: inactive, no price, the 16:9 print', () => {
    expect(
      mugArticleInput.parse({ ...mug, supplierArticleName: ' ', mugDetails: details }),
    ).toEqual({
      name: 'Classic mug',
      descriptionShort: 'A mug',
      descriptionLong: 'A classic white mug',
      active: false,
      categoryId: null,
      subcategoryId: null,
      supplierId: null,
      supplierArticleName: null,
      supplierArticleNumber: null,
      printAspectRatio: '16:9',
      mugDetails: {
        ...details,
        fillingQuantity: null,
        dishwasherSafe: false,
        documentFormatWidthMm: null,
        documentFormatHeightMm: null,
        documentFormatMarginBottomMm: null,
      },
      mugVariants: [
        {
          id: null,
          name: 'White',
          insideColorCode: '#fff',
          outsideColorCode: '#fff',
          isDefault: true,
          active: false,
          exampleImageFilename: null,
        },
      ],
      price: null,
    })
    expect(mugArticleInput.parse({ ...mug, printAspectRatio: ' 1:1 ' }).printAspectRatio).toBe(
      '1:1',
    )
  })

  test('reports the field rules with their paths', () => {
    expect(errorsOf(mugArticleInput, { mugVariants: [] })).toEqual({
      name: ['Name is required'],
      descriptionShort: ['DescriptionShort is required'],
      descriptionLong: ['DescriptionLong is required'],
    })
    expect(
      errorsOf(mugArticleInput, {
        ...mug,
        descriptionLong: 'D'.repeat(5001),
        categoryId: 0,
        supplierArticleNumber: 'N'.repeat(256),
        printAspectRatio: '4:3',
        mugDetails: {
          diameterMm: 82,
          printTemplateWidthMm: 200,
          printTemplateHeightMm: 90,
          documentFormatWidthMm: 0,
        },
        mugVariants: [
          { id: -1, name: ' ', insideColorCode: '#fff', outsideColorCode: '#fff', isDefault: true },
        ],
        price: { salesVatId: 1, salesTotalInputCents: -1 },
      }),
    ).toEqual({
      descriptionLong: ['DescriptionLong must be at most 5000 characters'],
      categoryId: ['CategoryId must be positive'],
      supplierArticleNumber: ['SupplierArticleNumber must be at most 255 characters'],
      printAspectRatio: ['PrintAspectRatio must be one of 16:9, 1:1'],
      'mugDetails.heightMm': ['HeightMm must be greater than zero'],
      'mugDetails.documentFormatWidthMm': ['DocumentFormatWidthMm must be greater than zero'],
      'mugVariants[0].id': ['Id must be positive'],
      'mugVariants[0].name': ['Name is required'],
      'price.purchaseVatId': ['Purchase VAT id is required'],
      'price.salesTotalInputCents': ['Sales total input must not be negative'],
    })
  })

  test('reports the rules about the article as a whole', () => {
    const variant = { name: 'White', insideColorCode: '#fff', outsideColorCode: '#fff' }
    expect(
      errorsOf(mugArticleInput, {
        ...mug,
        active: true,
        subcategoryId: 4,
        mugVariants: [
          { ...variant, id: 3 },
          { ...variant, id: 3 },
        ],
      }),
    ).toEqual({
      subcategoryId: ['SubcategoryId requires CategoryId'],
      mugVariants: ['Exactly one variant must be marked as default', 'Variant ids must be unique'],
      active: [
        'An active article requires complete mug details',
        'An active article requires at least one active variant',
        'An active article requires a category',
      ],
    })
  })

  test('has no priceId and no articleType', () => {
    expect(errorsOf(mugArticleInput, { ...mug, priceId: 3, articleType: 'MUG' })).toEqual({
      priceId: ['Unknown property'],
      articleType: ['Unknown property'],
    })
  })
})

describe('spodArticleInput', () => {
  test('defaults to an inactive article that keeps its price', () => {
    expect(spodArticleInput.parse({})).toEqual({
      active: false,
      categoryId: null,
      subcategoryId: null,
      defaultVariantId: null,
      price: null,
    })
  })

  test('reports the positive ids, the subcategory rule, and the two activation rules', () => {
    expect(
      errorsOf(spodArticleInput, { categoryId: 0, subcategoryId: -2, defaultVariantId: 0 }),
    ).toEqual({
      categoryId: ['CategoryId must be positive'],
      subcategoryId: ['SubcategoryId must be positive'],
      defaultVariantId: ['DefaultVariantId must be positive'],
    })
    expect(errorsOf(spodArticleInput, { active: true, subcategoryId: 3 })).toEqual({
      subcategoryId: ['SubcategoryId requires CategoryId'],
      active: [
        'An active article requires an active default variant',
        'An active article requires a category',
      ],
    })
  })

  test('carries no field the sync owns', () => {
    expect(
      errorsOf(spodArticleInput, { name: 'x', printFrame: {}, printAspectRatio: '1:1' }),
    ).toEqual({
      name: ['Unknown property'],
      printFrame: ['Unknown property'],
      printAspectRatio: ['Unknown property'],
    })
  })
})
