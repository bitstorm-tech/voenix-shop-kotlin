import { describe, expect, test } from 'bun:test'
import sharp from 'sharp'
import { calibrationPreviewPair, type Box, type CalibrationPreviewOptions } from '../test-fixtures'
import {
  calibrationDesignPngs,
  calibrationSpecFromProductType,
  dimensionsForAspectRatio,
  measurePrintFrame,
} from './calibration'

/** The measurement rule, held to preview pairs this test draws itself; no partner, no database. */

const measure = async (
  box: Box,
  options: CalibrationPreviewOptions = {},
  expectedAspectRatio = 1,
) => {
  const { a, b } = await calibrationPreviewPair(box, options)
  return measurePrintFrame(a, b, expectedAspectRatio)
}

const expectFrameClose = (
  actual: { leftPct: number; topPct: number; widthPct: number; heightPct: number },
  expected: { leftPct: number; topPct: number; widthPct: number; heightPct: number },
  tolerance = 0.15,
) => {
  expect(Math.abs(actual.leftPct - expected.leftPct)).toBeLessThanOrEqual(tolerance)
  expect(Math.abs(actual.topPct - expected.topPct)).toBeLessThanOrEqual(tolerance)
  expect(Math.abs(actual.widthPct - expected.widthPct)).toBeLessThanOrEqual(tolerance)
  expect(Math.abs(actual.heightPct - expected.heightPct)).toBeLessThanOrEqual(tolerance)
}

describe('measurePrintFrame', () => {
  test('measures a wholly visible frame cleanly without needing a mask', async () => {
    // 416 = 32 * 13: exact integer stripe widths in the 13-unit ruler.
    const measured = await measure({ x: 284, y: 188, width: 416, height: 416 })
    expect(measured).toEqual({
      kind: 'measured',
      frame: { leftPct: 28.4, topPct: 18.8, widthPct: 41.6, heightPct: 41.6 },
      mask: null,
      visiblePixels: 416 * 416,
      visibleBounds: { widthPct: 41.6, heightPct: 41.6 },
    })
  })

  test('stays accurate under dark shading, warm lighting, specular glare, and gamma 2.2', async () => {
    const measured = await measure(
      { x: 284, y: 188, width: 424, height: 424 },
      {
        garmentRgb: [35, 35, 40],
        shadingAt: (x, y) => {
          const s = 0.68 + 0.22 * Math.sin(x / 35) * Math.cos(y / 50)
          return [s, s * 0.94, s * 0.88]
        },
        highlightAt: (x, y) => (Math.hypot(x - 450, y - 350) < 90 ? 40 : 0),
        gamma: 2.2,
        noiseSpeck: true,
      },
    )
    if (measured.kind !== 'measured') throw new Error(`Expected measured, got ${measured.reason}`)
    expectFrameClose(measured.frame, {
      leftPct: 28.4,
      topPct: 18.8,
      widthPct: 42.4,
      heightPct: 42.4,
    })
    expect(measured.mask).toBeNull()
    expect(measured.visiblePixels).toBe(424 * 424)
  })

  test('is unaffected by neon green or neon magenta garment colours', async () => {
    for (const garmentRgb of [
      [0, 255, 0],
      [255, 0, 255],
    ] as const) {
      const measured = await measure({ x: 300, y: 200, width: 390, height: 390 }, { garmentRgb })
      if (measured.kind !== 'measured') throw new Error(`Expected measured, got ${measured.reason}`)
      expectFrameClose(measured.frame, { leftPct: 30, topPct: 20, widthPct: 39, heightPct: 39 })
      expect(measured.mask).toBeNull()
      expect(measured.visiblePixels).toBe(390 * 390)
    }
  })

  test('rounds every edge half up on its own, so a frame at the border ends at exactly 100', async () => {
    // edge = 390, x = 130 (33.33%), width = 260 (66.67%, 20 px per ruler unit).
    const measured = await measure({ x: 130, y: 0, width: 260, height: 260 }, { edge: 390 })
    expect(measured).toMatchObject({
      kind: 'measured',
      frame: { leftPct: 33.33, topPct: 0, widthPct: 66.67, heightPct: 66.67 },
      mask: null,
    })
  })

  test('a glossy round product keeps the whole frame and answers an anti-aliased mask', async () => {
    const measured = await measure(
      { x: 196, y: 208, width: 780, height: 780 },
      {
        visibleRadius: 342,
        shadingAt: (x, y) => {
          const d = Math.hypot(x - 586, y - 598) / 342
          const s = Math.max(0.5, 1 - 0.35 * d * d)
          return [s, s, s]
        },
        highlightAt: (x, y) =>
          Math.max(0, 60 * Math.exp(-((x - 470) ** 2 + (y - 450) ** 2) / 14_000)),
        transparentBorder: 20,
      },
    )
    if (measured.kind !== 'measured' || measured.mask === null) throw new Error('No mask measured')
    expectFrameClose(measured.frame, { leftPct: 19.6, topPct: 20.8, widthPct: 78, heightPct: 78 })
    expect(measured.visiblePixels).toBeGreaterThan(360_000)
    expect(measured.visiblePixels).toBeLessThan(375_000)
    expect(measured.mask).toStartWith('data:image/png;base64,')
    const { data, info } = await sharp(Buffer.from(measured.mask.split(',')[1] ?? '', 'base64'))
      .ensureAlpha()
      .raw()
      .toBuffer({ resolveWithObject: true })
    const alpha = (x: number, y: number) => data[(y * info.width + x) * 4 + 3]
    expect([info.width, info.height]).toEqual([256, 256])
    expect([alpha(128, 128), alpha(128, 30), alpha(3, 3), alpha(252, 252)]).toEqual([
      255, 255, 0, 0,
    ])
  })

  test('measures shaped products with cutouts (phone case camera hole, zip hoodie)', async () => {
    const phoneCase = await measure(
      { x: 150, y: 120, width: 700, height: 700 },
      {
        garmentRgb: [30, 30, 30],
        visibleAt: (x, y) => {
          if (x < 340 || x > 660 || y < 150 || y > 790) return 0
          if (x >= 360 && x <= 460 && y >= 170 && y <= 310) return 0
          return 1
        },
      },
    )
    if (phoneCase.kind !== 'measured' || phoneCase.mask === null) {
      throw new Error('Phone case should measure with mask')
    }
    expectFrameClose(
      phoneCase.frame,
      { leftPct: 15, topPct: 12, widthPct: 70, heightPct: 70 },
      0.25,
    )

    const zipHoodie = await measure(
      { x: 280, y: 200, width: 440, height: 440 },
      {
        visibleAt: (x) => (Math.abs(x - 500) < 12 ? 0 : 1),
      },
    )
    if (zipHoodie.kind !== 'measured' || zipHoodie.mask === null) {
      throw new Error('Zip hoodie should measure with mask')
    }
    expectFrameClose(zipHoodie.frame, { leftPct: 28, topPct: 20, widthPct: 44, heightPct: 44 })
  })

  test('allows full-bleed all-over overhang past the mockup edges and masks it to the picture', async () => {
    const measured = await measure({ x: -26, y: 50, width: 1040, height: 1040 })
    if (measured.kind !== 'measured' || measured.mask === null) {
      throw new Error('Overhanging full-bleed frame should measure with mask')
    }
    expectFrameClose(measured.frame, { leftPct: -2.6, topPct: 5, widthPct: 104, heightPct: 104 })
  })

  test('rejects strongly rotated mockups as DISTORTED_PRINT and empty/tiny areas as NO_PRINT_AREA', async () => {
    const rotated = await measure({ x: 280, y: 200, width: 440, height: 440 }, { rotationDeg: 25 })
    expect(rotated).toEqual({ kind: 'failed', reason: 'DISTORTED_PRINT' })

    expect(await measure({ x: 0, y: 0, width: 0, height: 0 })).toEqual({
      kind: 'failed',
      reason: 'NO_PRINT_AREA',
    })
    expect(await measure({ x: 10, y: 10, width: 14, height: 14 })).toEqual({
      kind: 'failed',
      reason: 'NO_PRINT_AREA',
    })
  })

  test('no picture, or previews of different sizes, is UNREADABLE_IMAGE', async () => {
    const { a } = await calibrationPreviewPair({ x: 284, y: 188, width: 416, height: 416 })
    const { b: smaller } = await calibrationPreviewPair(
      { x: 142, y: 94, width: 208, height: 208 },
      { edge: 500 },
    )
    const unreadable = { kind: 'failed', reason: 'UNREADABLE_IMAGE' } as const
    for (const bytes of [new Uint8Array([1, 2, 3]), new Uint8Array()]) {
      expect(await measurePrintFrame(bytes, a)).toEqual(unreadable)
      expect(await measurePrintFrame(a, bytes)).toEqual(unreadable)
    }
    expect(await measurePrintFrame(a, smaller)).toEqual(unreadable)
  })

  test('the calibration design pair is 4000 × 4000, compact, and measures to the whole picture', async () => {
    const { a, b } = await calibrationDesignPngs()
    const metaA = await sharp(a).metadata()
    const metaB = await sharp(b).metadata()
    expect([metaA.width, metaA.height, metaB.width, metaB.height]).toEqual([4000, 4000, 4000, 4000])
    expect(a.length).toBeLessThan(100_000)
    expect(b.length).toBeLessThan(100_000)

    const small = await calibrationDesignPngs(260)
    expect(await measurePrintFrame(small.a, small.b)).toMatchObject({
      kind: 'measured',
      frame: { leftPct: 0, topPct: 0, widthPct: 100, heightPct: 100 },
      mask: null,
    })
  })

  test('measures non-square designs (3:2 pencil case, 1:2 phone case) and reports visibleBounds', async () => {
    expect(dimensionsForAspectRatio(1.5)).toEqual({ width: 4000, height: 2667 })
    expect(dimensionsForAspectRatio(0.75)).toEqual({ width: 3000, height: 4000 })
    expect(dimensionsForAspectRatio(0.1)).toEqual({ width: 667, height: 4000 })

    // 3:2 landscape pencil case (390 × 260 px, exact 13-unit multiples)
    const pencilCase = await measure({ x: 305, y: 450, width: 390, height: 260 }, {}, 1.5)
    expect(pencilCase).toEqual({
      kind: 'measured',
      frame: { leftPct: 30.5, topPct: 45, widthPct: 39, heightPct: 26 },
      mask: null,
      visiblePixels: 390 * 260,
      visibleBounds: { widthPct: 39, heightPct: 26 },
    })

    // A 3:2 box measured against an expected 1:1 design is within tolerance (1.5 <= 1.55),
    // while a 2:1 box against expected 1:1 is rejected as DISTORTED_PRINT.
    expect(await measure({ x: 240, y: 370, width: 520, height: 260 }, {}, 1)).toEqual({
      kind: 'failed',
      reason: 'DISTORTED_PRINT',
    })
    // The same 2:1 box passes when expectedAspectRatio is 2.
    expect(await measure({ x: 240, y: 370, width: 520, height: 260 }, {}, 2)).toMatchObject({
      kind: 'measured',
      frame: { leftPct: 24, topPct: 37, widthPct: 52, heightPct: 26 },
      mask: null,
    })

    // Tall phone case visibleBounds under 1:1 design reveals 0.50 ratio
    const phoneMask = await measure(
      { x: 150, y: 120, width: 700, height: 700 },
      {
        visibleAt: (x, y) => (x >= 340 && x < 660 && y >= 150 && y < 790 ? 1 : 0),
      },
      1,
    )
    if (phoneMask.kind !== 'measured') throw new Error('Expected phone case measurement')
    expect(phoneMask.visibleBounds).toEqual({ widthPct: 32, heightPct: 64 })
  })

  test('calibrationSpecFromProductType sizes large/high-DPI and wide/back-only products accurately', () => {
    // Poster 60x60 cm @ 200 DPI needs >= 4756 px -> rounds up to 5000x5000.
    expect(
      calibrationSpecFromProductType(
        {
          printType: [{ dpi: 200 }],
          printAreas: [
            { view: 'FRONT', widthMm: 604, heightMm: 604, widthInch: 23.78, heightInch: 23.78 },
          ],
        },
        'FRONT',
      ),
    ).toMatchObject({ width: 5000, height: 5000, maxEdge: 5000 })

    // Rectangular puzzle 28x19 cm @ 400 DPI needs >= 4492 px -> 4750x3250.
    expect(
      calibrationSpecFromProductType(
        {
          printType: [{ dpi: 400 }],
          printAreas: [
            {
              view: 'FRONT',
              widthMm: 285.15,
              heightMm: 195.07,
              widthInch: 11.23,
              heightInch: 7.68,
            },
          ],
        },
        'FRONT',
      ),
    ).toMatchObject({ width: 4750, height: 3249, maxEdge: 4750 })

    // Bumper sticker 29.2x7.6 cm @ 200 DPI (3.82:1 ratio) stays at 4000 px max edge.
    expect(
      calibrationSpecFromProductType(
        {
          printType: [{ dpi: 200 }],
          printAreas: [
            {
              view: 'FRONT',
              widthMm: 292.12,
              heightMm: 76.54,
              widthInch: 11.5,
              heightInch: 3.01,
            },
          ],
        },
        'FRONT',
      ),
    ).toMatchObject({ width: 4000, height: 1048, maxEdge: 4000 })

    // Back-only dog t-shirt selects the BACK printArea.
    expect(
      calibrationSpecFromProductType(
        {
          printType: [{ dpi: 120 }, { dpi: 200 }],
          printAreas: [
            { view: 'BACK', widthMm: 110.36, heightMm: 150.79, widthInch: 4.35, heightInch: 5.94 },
          ],
        },
        'BACK',
      ),
    ).toMatchObject({ width: 2928, height: 4000, maxEdge: 4000 })
  })
})
