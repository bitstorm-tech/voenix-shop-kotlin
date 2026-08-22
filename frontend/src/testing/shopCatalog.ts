import type { CategoryDto, ArticleSubcategoryDto } from '@/stores/shop/articleCategories'
import type {
  MugDetailsDto,
  MugDto,
  MugVariantDto,
  TshirtDto,
  TshirtVariantDto,
} from '@/stores/shop/catalog'
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
    articleType: 'MUG',
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

export function createTshirtVariant(overrides: Partial<TshirtVariantDto> = {}): TshirtVariantDto {
  return {
    id: 21,
    name: 'Black / M',
    colorName: 'Black',
    colorHex: '#101010',
    size: 'M',
    isDefault: true,
    exampleImageFilename: null,
    ...overrides,
  }
}

export function createShopTshirt(overrides: Partial<TshirtDto> = {}): TshirtDto {
  const id = overrides.id ?? 2

  return {
    articleType: 'TSHIRT',
    id,
    position: id,
    name: `Tshirt ${id}`,
    descriptionShort: 'Short description',
    descriptionLong: 'Long description',
    categoryId: 20,
    subcategoryId: null,
    price: 1990,
    printAspectRatio: '16:9',
    sizeChartImageFilename: null,
    printFrame: { leftPct: 25, topPct: 20, widthPct: 50, heightPct: 40.5 },
    variants: [createTshirtVariant({ id: id * 10 + 1 })],
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
