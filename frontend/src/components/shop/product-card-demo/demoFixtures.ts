import type { TshirtDto, TshirtVariantDto } from '@/stores/shop/catalog'

/**
 * Shirts for the product card demo page when no backend catalog is reachable. The men's shirt
 * mirrors the real catalog shape the current card struggles with: 18 colours and eight sizes in
 * an unsorted order.
 */
interface ColorSpec {
  name: string
  hex: string
}

function buildVariants(baseId: number, colors: ColorSpec[], sizes: string[]): TshirtVariantDto[] {
  return colors.flatMap((color, colorIndex) =>
    sizes.map((size, sizeIndex) => ({
      id: baseId + colorIndex * 100 + sizeIndex,
      name: `${color.name} / ${size}`,
      colorName: color.name,
      colorHex: color.hex,
      size,
      isDefault: colorIndex === 0 && size === 'M',
      exampleImageFilename: null,
    })),
  )
}

function buildShirt(
  id: number,
  fields: Pick<TshirtDto, 'name' | 'descriptionShort' | 'price' | 'regularPrice'>,
  colors: ColorSpec[],
  sizes: string[],
): TshirtDto {
  return {
    articleType: 'TSHIRT',
    id,
    position: id,
    descriptionLong: fields.descriptionShort,
    categoryId: 1,
    subcategoryId: null,
    printAspectRatio: '1:1',
    sizeChartImageFilename: null,
    printFrame: { leftPct: 25, topPct: 20, widthPct: 50, heightPct: 40 },
    variants: buildVariants(id * 10_000, colors, sizes),
    ...fields,
  }
}

const MEN_COLORS: ColorSpec[] = [
  { name: 'Schwarz', hex: '#141414' },
  { name: 'Dunkelgrau', hex: '#4a4a4c' },
  { name: 'Hellblau', hex: '#8fb3e0' },
  { name: 'Royalblau', hex: '#2453a6' },
  { name: 'Denim', hex: '#3d5a80' },
  { name: 'Gelb', hex: '#f2e600' },
  { name: 'Anthrazit meliert', hex: '#6b6463' },
  { name: 'Hellgrau', hex: '#c8c8c8' },
  { name: 'Grün', hex: '#1e9b4f' },
  { name: 'Lila', hex: '#6a3d9a' },
  { name: 'Oliv', hex: '#6b7a4b' },
  { name: 'Navy', hex: '#13254a' },
  { name: 'Rosa', hex: '#f39bbf' },
  { name: 'Rot', hex: '#e3172d' },
  { name: 'Salbei', hex: '#b9cfa5' },
  { name: 'Sand', hex: '#b8ab92' },
  { name: 'Weiß', hex: '#ffffff' },
  { name: 'Bordeaux', hex: '#8c1d2e' },
]

export function createDemoShirts(): TshirtDto[] {
  return [
    buildShirt(
      1,
      {
        name: 'Männer T-Shirt',
        descriptionShort:
          'Unser T-Shirt-Klassiker schlechthin: Der lockere Schnitt macht dieses Shirt zum Liebling für jeden Tag.',
        price: 2499,
        regularPrice: null,
      },
      MEN_COLORS,
      ['S', '3XL', '4XL', '5XL', 'L', 'M', 'XL', 'XXL'],
    ),
    buildShirt(
      2,
      {
        name: 'Frauen T-Shirt',
        descriptionShort: 'Tailliert geschnitten, weich und atmungsaktiv aus 100 % Bio-Baumwolle.',
        price: 2299,
        regularPrice: 2799,
      },
      MEN_COLORS.filter((_, index) => index % 2 === 0),
      ['XS', 'S', 'M', 'L', 'XL', 'XXL'],
    ),
    buildShirt(
      3,
      {
        name: 'Kinder T-Shirt',
        descriptionShort: 'Robust, waschbar bei 60 °C und bereit für jedes Abenteuer.',
        price: 1799,
        regularPrice: null,
      },
      [MEN_COLORS[16]!, MEN_COLORS[5]!, MEN_COLORS[2]!, MEN_COLORS[12]!],
      ['98/104', '110/116', '122/128', '134/146'],
    ),
  ]
}
