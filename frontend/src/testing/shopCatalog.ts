import type { CategoryDto, ArticleSubcategoryDto } from '@/stores/shop/articleCategories'
import type {
  MugDetailsDto,
  MugDto,
  MugVariantDto,
  SpodAppearanceDto,
  SpodArticleDto,
  SpodVariantDto,
} from '@/stores/shop/catalog'
import type { PromptDto } from '@/stores/shop/prompts'

/**
 * Fixtures of the storefront catalog contracts, shaped exactly like the bare arrays the
 * backend answers with (`docs/dev/backend/packages/article-package.md`, `prompt-package.md`). Every optional
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
    regularPrice: null,
    mugDetails: createMugDetails(),
    variants: [createMugVariant({ id: id * 10 + 1 })],
    ...overrides,
  }
}

export function createSpodAppearance(
  overrides: Partial<SpodAppearanceDto> = {},
): SpodAppearanceDto {
  const id = overrides.id ?? 201

  return {
    id,
    colorName: 'Black',
    colorHex: '#101010',
    mockupImageFilename: `mockup-${id}.webp`,
    listingImageFilenames: [`listing-${id}-front.webp`, `listing-${id}-back.webp`],
    ...overrides,
  }
}

export function createSpodVariant(overrides: Partial<SpodVariantDto> = {}): SpodVariantDto {
  return {
    id: 21,
    name: 'Black / M',
    appearanceId: 201,
    size: 'M',
    isDefault: true,
    ...overrides,
  }
}

/**
 * Every colour in every size, the way the backend orders them (colour first), with ids counting up
 * from `articleId * 10 + 1` and the first variant as the default.
 */
function spodVariantsOf(
  articleId: number,
  appearances: SpodAppearanceDto[],
  sizes: string[],
): SpodVariantDto[] {
  return appearances
    .flatMap((appearance) => sizes.map((size) => ({ appearance, size })))
    .map(({ appearance, size }, index) =>
      createSpodVariant({
        id: articleId * 10 + 1 + index,
        name: `${appearance.colorName} / ${size}`,
        appearanceId: appearance.id,
        size,
        isDefault: index === 0,
      }),
    )
}

/** Overridden `appearances` get every size too, unless the overrides bring their own `variants`. */
function createShopSpodArticle(
  id: number,
  defaultAppearances: SpodAppearanceDto[],
  sizes: string[],
  overrides: Partial<SpodArticleDto>,
): SpodArticleDto {
  const appearances = overrides.appearances ?? defaultAppearances

  return {
    articleType: 'SPOD',
    id,
    position: id,
    name: `SPOD article ${id}`,
    descriptionShort: 'Short description',
    descriptionLong: 'Long description',
    categoryId: 20,
    subcategoryId: null,
    price: 1990,
    regularPrice: null,
    productTypeName: "Men's Tee",
    sizeChartImageFilename: null,
    printFrame: { leftPct: 25, topPct: 20, widthPct: 50, heightPct: 40.5 },
    appearances,
    variants: spodVariantsOf(id, appearances, sizes),
    ...overrides,
  }
}

/** A tee in two colours (black, white), each in three sizes (S, M, L); variant ids `id*10+1..6`. */
export function createShopTee(overrides: Partial<SpodArticleDto> = {}): SpodArticleDto {
  const id = overrides.id ?? 2
  const appearances = [
    createSpodAppearance({ id: id * 100 + 1, colorName: 'Black', colorHex: '#101010' }),
    createSpodAppearance({ id: id * 100 + 2, colorName: 'White', colorHex: '#ffffff' }),
  ]

  return createShopSpodArticle(id, appearances, ['S', 'M', 'L'], overrides)
}

const TOTE_COLORS: [string, string][] = [
  ['Natural', '#f3ead8'],
  ['Black', '#101010'],
  ['Navy', '#1f2a44'],
  ['Red', '#c0392b'],
  ['Bottle Green', '#1e5631'],
  ['Royal Blue', '#2752a8'],
  ['Grey', '#8a8a8a'],
  ['Pink', '#f4a6c1'],
]

/** A tote bag in eight colours and one size ("One size"); variant ids `id*10+1..8`. */
export function createShopTote(overrides: Partial<SpodArticleDto> = {}): SpodArticleDto {
  const id = overrides.id ?? 3
  const appearances = TOTE_COLORS.map(([colorName, colorHex], index) =>
    createSpodAppearance({ id: id * 100 + 1 + index, colorName, colorHex }),
  )

  return createShopSpodArticle(id, appearances, ['One size'], {
    name: `Tote ${id}`,
    productTypeName: 'Tote Bag',
    ...overrides,
  })
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
