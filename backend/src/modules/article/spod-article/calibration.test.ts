import { describe, expect, test } from 'bun:test'
import sharp from 'sharp'
import { blankMockupPng, calibrationPreviewPng } from '../test-fixtures'
import { calibrationDesignPng, measurePrintFrame } from './calibration'

/** The measurement rule, held to pictures this test draws itself; no partner, no database. */

type Preview = Parameters<typeof calibrationPreviewPng>
const measure = async (box: Preview[0], options: Preview[1] = {}) =>
  measurePrintFrame(await calibrationPreviewPng(box, options), await blankMockupPng(options.edge))

describe('measurePrintFrame', () => {
  test('measures the frame from the marker in percent with two decimals, wholly visible', async () => {
    expect(await measure({ x: 284, y: 188, width: 424, height: 424 })).toEqual({
      kind: 'measured',
      frame: { leftPct: 28.4, topPct: 18.8, widthPct: 42.4, heightPct: 42.4 },
      mask: null,
      visiblePixels: 424 * 424,
    })
  })

  test('leaves the translucent rim out', async () => {
    expect(
      await measure({ x: 338, y: 536, width: 324, height: 324 }, { antiAliased: true }),
    ).toMatchObject({
      frame: { leftPct: 33.8, topPct: 53.6, widthPct: 32.4, heightPct: 32.4 },
      mask: null,
      visiblePixels: 324 * 324,
    })
  })

  test('rounds every edge half up on its own, so a frame at the border ends at exactly 100', async () => {
    expect(await measure({ x: 4, y: 0, width: 8, height: 8 }, { edge: 12 })).toMatchObject({
      frame: { leftPct: 33.33, topPct: 0, widthPct: 66.67, heightPct: 66.67 },
    })
  })

  test('a round product keeps the whole frame and answers what it shows as a mask', async () => {
    const measured = await measure(
      { x: 200, y: 200, width: 600, height: 600 },
      { visibleRadius: 280 },
    )
    if (measured.kind !== 'measured' || measured.mask === null) throw new Error('No mask measured')
    expect(measured.frame).toEqual({ leftPct: 20, topPct: 20, widthPct: 60, heightPct: 60 })
    // The circle of radius 280, not the 600 × 600 frame around it.
    expect(measured.visiblePixels).toBeGreaterThan(245_000)
    expect(measured.visiblePixels).toBeLessThan(247_500)
    expect(measured.mask).toStartWith('data:image/png;base64,')
    const { data, info } = await sharp(Buffer.from(measured.mask.split(',')[1] ?? '', 'base64'))
      .ensureAlpha()
      .raw()
      .toBuffer({ resolveWithObject: true })
    const alpha = (x: number, y: number) => data[(y * info.width + x) * 4 + 3]
    expect([info.width, info.height]).toEqual([256, 256])
    expect([alpha(128, 128), alpha(128, 20), alpha(3, 3), alpha(252, 252)]).toEqual([
      255, 255, 0, 0,
    ])
  })

  test('a hole or a cut marker is LOW_FILL; no or a tiny marker is NO_RECTANGLE', async () => {
    expect(await measure({ x: 300, y: 300, width: 300, height: 300 }, { holed: true })).toEqual({
      kind: 'failed',
      reason: 'LOW_FILL',
    })
    // The picture's edge runs through the marker: nothing of the design lies left of it.
    expect(await measure({ x: -300, y: 300, width: 600, height: 400 })).toEqual({
      kind: 'failed',
      reason: 'LOW_FILL',
    })
    expect(await measure({ x: 0, y: 0, width: 0, height: 0 })).toEqual({
      kind: 'failed',
      reason: 'NO_RECTANGLE',
    })
    expect(await measure({ x: 10, y: 10, width: 16, height: 16 })).toEqual({
      kind: 'failed',
      reason: 'NO_RECTANGLE',
    })
  })

  test('a design that leaves the picture is OUTSIDE_PICTURE', async () => {
    expect(await measure({ x: -50, y: 300, width: 400, height: 400 })).toEqual({
      kind: 'failed',
      reason: 'OUTSIDE_PICTURE',
    })
  })

  test('no picture, or a mockup of another size, is UNREADABLE_IMAGE', async () => {
    const preview = await calibrationPreviewPng({ x: 284, y: 188, width: 424, height: 424 })
    const unreadable = { kind: 'failed', reason: 'UNREADABLE_IMAGE' } as const
    for (const bytes of [new Uint8Array([1, 2, 3]), new Uint8Array()]) {
      expect(await measurePrintFrame(bytes, await blankMockupPng())).toEqual(unreadable)
      expect(await measurePrintFrame(preview, bytes)).toEqual(unreadable)
    }
    expect(await measurePrintFrame(preview, await blankMockupPng(500))).toEqual(unreadable)
  })

  test('the calibration design measures to the whole picture', async () => {
    expect(
      await measurePrintFrame(await calibrationDesignPng(), await blankMockupPng(2000)),
    ).toMatchObject({
      frame: { leftPct: 0, topPct: 0, widthPct: 100, heightPct: 100 },
      mask: null,
    })
  })
})
