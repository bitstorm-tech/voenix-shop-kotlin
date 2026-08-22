import { describe, expect, it } from 'vitest'
import {
  generateTshirtVariantMatrix,
  parseMatrixColors,
  parseMatrixSizes,
  type TshirtVariantRow,
  withSingleDefault,
} from '../tshirtVariantMatrix'

function keySequence() {
  let key = 0
  return () => ++key
}

function row(overrides: Partial<TshirtVariantRow> = {}): TshirtVariantRow {
  return {
    key: 1,
    id: null,
    colorName: 'Black',
    colorHex: '#000000',
    sizeLabel: 'M',
    spodProductTypeId: 812,
    spodAppearanceId: null,
    spodSizeId: null,
    isDefault: false,
    active: true,
    exampleImageFilename: null,
    ...overrides,
  }
}

describe('parseMatrixColors', () => {
  it('reads one colour per line and keeps the typed order', () => {
    expect(parseMatrixColors('Black #000000\nWhite #FFFFFF\n')).toEqual([
      { name: 'Black', hex: '#000000' },
      { name: 'White', hex: '#ffffff' },
    ])
  })

  it('falls back to white for a line without a hex and drops a repeated colour', () => {
    expect(parseMatrixColors('Navy\nNavy #001f3f')).toEqual([{ name: 'Navy', hex: '#ffffff' }])
  })
})

describe('parseMatrixSizes', () => {
  it('accepts commas, semicolons, and spaces and drops repetitions', () => {
    expect(parseMatrixSizes('S, M; L  XL\nXXL, M')).toEqual(['S', 'M', 'L', 'XL', 'XXL'])
  })
})

describe('generateTshirtVariantMatrix', () => {
  it('generates a 2 × 5 matrix with one uniform product type and exactly one default', () => {
    const rows = generateTshirtVariantMatrix(
      {
        colors: [
          { name: 'Black', hex: '#000000' },
          { name: 'White', hex: '#ffffff' },
        ],
        sizes: ['S', 'M', 'L', 'XL', 'XXL'],
        spodProductTypeId: 812,
      },
      [],
      keySequence(),
    )

    expect(rows).toHaveLength(10)
    expect(rows.map((entry) => `${entry.colorName}/${entry.sizeLabel}`)).toEqual([
      'Black/S',
      'Black/M',
      'Black/L',
      'Black/XL',
      'Black/XXL',
      'White/S',
      'White/M',
      'White/L',
      'White/XL',
      'White/XXL',
    ])
    expect(new Set(rows.map((entry) => entry.spodProductTypeId))).toEqual(new Set([812]))
    expect(rows.filter((entry) => entry.isDefault)).toHaveLength(1)
    expect(rows[0]!.isDefault).toBe(true)
    expect(rows.every((entry) => entry.active)).toBe(true)
    expect(rows.every((entry) => entry.spodAppearanceId === null)).toBe(true)
    expect(new Set(rows.map((entry) => entry.key)).size).toBe(10)
  })

  it('keeps the stored id, the looked-up ids, and the picture of a pair that survives', () => {
    const stored = row({
      key: 7,
      id: 42,
      sizeLabel: 'S',
      spodAppearanceId: 99,
      spodSizeId: 5,
      isDefault: true,
      exampleImageFilename: 'black-s.webp',
    })

    const rows = generateTshirtVariantMatrix(
      {
        colors: [{ name: 'Black', hex: '#111111' }],
        sizes: ['S', 'M'],
        spodProductTypeId: 900,
      },
      [stored],
      keySequence(),
    )

    expect(rows[0]).toMatchObject({
      key: 7,
      id: 42,
      colorHex: '#111111',
      spodProductTypeId: 900,
      spodAppearanceId: 99,
      spodSizeId: 5,
      exampleImageFilename: 'black-s.webp',
      isDefault: true,
    })
    expect(rows[1]).toMatchObject({ id: null, sizeLabel: 'M', isDefault: false })
  })

  it('drops a pair the new matrix no longer contains and moves the default onto a surviving row', () => {
    const rows = generateTshirtVariantMatrix(
      { colors: [{ name: 'Black', hex: '#000000' }], sizes: ['M'], spodProductTypeId: 812 },
      [row({ key: 1, id: 1, sizeLabel: 'S', isDefault: true }), row({ key: 2, id: 2 })],
      keySequence(),
    )

    expect(rows).toHaveLength(1)
    expect(rows[0]).toMatchObject({ key: 2, id: 2, sizeLabel: 'M', isDefault: true })
  })

  it('generates nothing while one of the two lists is empty', () => {
    expect(
      generateTshirtVariantMatrix(
        { colors: [{ name: 'Black', hex: '#000000' }], sizes: [], spodProductTypeId: 812 },
        [],
        keySequence(),
      ),
    ).toEqual([])
  })
})

describe('withSingleDefault', () => {
  it('moves the flag onto the preferred row', () => {
    const rows = withSingleDefault([row({ key: 1, isDefault: true }), row({ key: 2 })], 2)

    expect(rows.map((entry) => entry.isDefault)).toEqual([false, true])
  })

  it('keeps the current default when the preferred row is not in the list', () => {
    const rows = withSingleDefault([row({ key: 1 }), row({ key: 2, isDefault: true })], 9)

    expect(rows.map((entry) => entry.isDefault)).toEqual([false, true])
  })
})
