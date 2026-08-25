import type { MugDto, ShopArticle, TshirtDto } from '@/stores/shop/catalog'

export const DEV_EDITOR_ARTICLE_ID = 900001
export const DEV_EDITOR_VARIANT_ID = 900011
export const DEV_EDITOR_DRAFT_ID = 'test'

export const DEV_EDITOR_TSHIRT_ARTICLE_ID = 900002
export const DEV_EDITOR_TSHIRT_VARIANT_ID = 900021
export const DEV_EDITOR_TSHIRT_DRAFT_ID = 'test-shirt'

export function createDevEditorMug(): MugDto {
  return {
    articleType: 'MUG',
    id: DEV_EDITOR_ARTICLE_ID,
    position: 1,
    name: 'Development Mug',
    descriptionShort: 'Local editor fixture',
    descriptionLong: 'Local editor fixture for quickly opening the product editor.',
    categoryId: 9000,
    subcategoryId: null,
    price: 1499,
    regularPrice: null,
    mugDetails: {
      heightMm: 95,
      diameterMm: 82,
      printTemplateWidthMm: 200,
      printTemplateHeightMm: 90,
      fillingQuantity: '325ml',
      dishwasherSafe: true,
      documentFormatWidthMm: 200,
      documentFormatHeightMm: 90,
      documentFormatMarginBottomMm: null,
    },
    variants: [
      {
        id: DEV_EDITOR_VARIANT_ID,
        name: 'Studio White',
        outsideColorCode: '#ffffff',
        insideColorCode: '#f4f0e8',
        isDefault: true,
        exampleImageFilename: null,
      },
    ],
  }
}

export function createDevEditorTshirt(): TshirtDto {
  return {
    articleType: 'TSHIRT',
    id: DEV_EDITOR_TSHIRT_ARTICLE_ID,
    position: 2,
    name: 'Development Shirt',
    descriptionShort: 'Local editor fixture',
    descriptionLong: 'Local editor fixture for quickly opening the product editor with a shirt.',
    categoryId: 9000,
    subcategoryId: null,
    price: 2499,
    regularPrice: null,
    printAspectRatio: '16:9',
    sizeChartImageFilename: null,
    printFrame: { leftPct: 30, topPct: 26, widthPct: 40, heightPct: 22.5 },
    variants: [
      {
        id: DEV_EDITOR_TSHIRT_VARIANT_ID,
        name: 'Black / M',
        colorName: 'Black',
        colorHex: '#101010',
        size: 'M',
        isDefault: true,
        exampleImageFilename: null,
      },
      {
        id: DEV_EDITOR_TSHIRT_VARIANT_ID + 1,
        name: 'Black / L',
        colorName: 'Black',
        colorHex: '#101010',
        size: 'L',
        isDefault: false,
        exampleImageFilename: null,
      },
    ],
  }
}

/** Every article the development fixtures need, so the editor can upsert them in one go. */
export function createDevEditorArticles(): ShopArticle[] {
  return [createDevEditorMug(), createDevEditorTshirt()]
}

const DEV_EDITOR_DRAFT_FIXTURES: Record<
  string,
  { id: string; articleId: number; variantId: number }
> = {
  [DEV_EDITOR_DRAFT_ID]: {
    id: DEV_EDITOR_DRAFT_ID,
    articleId: DEV_EDITOR_ARTICLE_ID,
    variantId: DEV_EDITOR_VARIANT_ID,
  },
  [DEV_EDITOR_TSHIRT_DRAFT_ID]: {
    id: DEV_EDITOR_TSHIRT_DRAFT_ID,
    articleId: DEV_EDITOR_TSHIRT_ARTICLE_ID,
    variantId: DEV_EDITOR_TSHIRT_VARIANT_ID,
  },
}

/** The draft a development route stands for, or `null` when the id is a real draft. */
export function findDevEditorDraftFixture(
  draftId: string,
): { id: string; articleId: number; variantId: number } | null {
  return DEV_EDITOR_DRAFT_FIXTURES[draftId] ?? null
}

export function createDevEditorImageBlob(): Blob {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="720" viewBox="0 0 1600 720">
  <defs>
    <linearGradient id="bg" x1="0" x2="1" y1="0" y2="1">
      <stop offset="0" stop-color="#ffe6c7"/>
      <stop offset="0.55" stop-color="#f0573d"/>
      <stop offset="1" stop-color="#171717"/>
    </linearGradient>
    <pattern id="grid" width="80" height="80" patternUnits="userSpaceOnUse">
      <path d="M 80 0 L 0 0 0 80" fill="none" stroke="#ffffff" stroke-opacity="0.2" stroke-width="2"/>
    </pattern>
  </defs>
  <rect width="1600" height="720" fill="url(#bg)"/>
  <rect width="1600" height="720" fill="url(#grid)"/>
  <circle cx="1260" cy="190" r="145" fill="#fff7ed" fill-opacity="0.88"/>
  <circle cx="255" cy="565" r="185" fill="#111111" fill-opacity="0.72"/>
  <rect x="190" y="155" width="1220" height="410" rx="52" fill="#ffffff" fill-opacity="0.14" stroke="#ffffff" stroke-opacity="0.52" stroke-width="4"/>
  <text x="800" y="328" text-anchor="middle" font-family="Arial, sans-serif" font-size="86" font-weight="800" fill="#ffffff">VOENIX DEV</text>
  <text x="800" y="420" text-anchor="middle" font-family="Arial, sans-serif" font-size="44" fill="#fff7ed">Editor test draft</text>
</svg>`

  return new Blob([svg], { type: 'image/svg+xml' })
}
