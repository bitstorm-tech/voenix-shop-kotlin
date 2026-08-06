import type { CategoryDto, ArticleSubcategoryDto } from '@/stores/shop/articleCategories'
import type { MugDetailsDto, MugDto, MugVariantDto } from '@/stores/shop/mugs'
import type { PromptDto } from '@/stores/shop/prompts'

/**
 * Fixtures of the storefront catalog contracts, shaped exactly like the bare arrays the Kotlin
 * backend answers with (`docs/dev/backend/article-package.md`, `prompt-package.md`). Every optional
 * wire field is present and explicitly `null`, because the backend serializes it that way.
 */
export function createMugDetails(overrides: Partial<MugDetailsDto> = {}): MugDetailsDto {
  return {
    heightMm: 95,
    diameterMm: 82,
    printTemplateWidthMm: 200,
    printTemplateHeightMm: 90,
    fillingQuantity: '325ml',
    dishwasherSafe: true,
    documentFormatWidthMm: 200,
    documentFormatHeightMm: 90,
    documentFormatMarginBottomMm: null,
    ...overrides,
  }
}

export function createMugVariant(overrides: Partial<MugVariantDto> = {}): MugVariantDto {
  return {
    id: 11,
    name: 'White',
    outsideColorCode: '#ffffff',
    insideColorCode: '#ffffff',
    isDefault: true,
    exampleImageFilename: null,
    ...overrides,
  }
}

export function createShopMug(overrides: Partial<MugDto> = {}): MugDto {
  const id = overrides.id ?? 1

  return {
    id,
    position: id,
    name: `Mug ${id}`,
    descriptionShort: 'Short description',
    descriptionLong: 'Long description',
    categoryId: 10,
    subcategoryId: null,
    price: 1499,
    mugDetails: createMugDetails(),
    variants: [createMugVariant({ id: id * 10 + 1 })],
    ...overrides,
  }
}

export function createMugSubcategory(
  overrides: Partial<ArticleSubcategoryDto> = {},
): ArticleSubcategoryDto {
  return {
    id: 100,
    name: 'Classic',
    exampleImageFilename: null,
    position: 1,
    ...overrides,
  }
}

export function createMugCategory(overrides: Partial<CategoryDto> = {}): CategoryDto {
  return {
    id: 10,
    name: 'Mugs',
    position: 1,
    subcategories: [],
    ...overrides,
  }
}

export function createShopPrompt(overrides: Partial<PromptDto> = {}): PromptDto {
  const id = overrides.id ?? 1

  return {
    id,
    position: id,
    title: `Prompt ${id}`,
    category: { id: 10, name: 'Portraits', position: 1 },
    subcategory: null,
    exampleImageFilename: null,
    llm: null,
    price: null,
    ...overrides,
  }
}
