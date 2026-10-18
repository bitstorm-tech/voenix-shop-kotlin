import { describe, expect, test } from 'bun:test'
import { magentaBoxPng } from '../test-fixtures'
import { calibrationDesignPng, measurePrintFrame } from './calibration'

/** The measurement rule, held to pictures this test draws itself; no partner, no database. */

describe('measurePrintFrame', () => {
  test('measures an exact box in percent with two decimals', async () => {
    expect(
      await measurePrintFrame(await magentaBoxPng({ x: 285, y: 188, width: 425, height: 425 })),
    ).toEqual({
      kind: 'measured',
      frame: { leftPct: 28.5, topPct: 18.8, widthPct: 42.5, heightPct: 42.5 },
    })
  })

  test('leaves the translucent rim out', async () => {
    const png = await magentaBoxPng(
      { x: 338, y: 536, width: 324, height: 324 },
      { antiAliased: true },
    )
    expect(await measurePrintFrame(png)).toEqual({
      kind: 'measured',
      frame: { leftPct: 33.8, topPct: 53.6, widthPct: 32.4, heightPct: 32.4 },
    })
  })

  test('rounds every edge half up on its own, so a box at the border ends at exactly 100', async () => {
    const measured = await measurePrintFrame(
      await magentaBoxPng({ x: 1, y: 2, width: 2, height: 1 }, { edge: 8 }),
    )
    expect(measured).toEqual({
      kind: 'measured',
      frame: { leftPct: 12.5, topPct: 25, widthPct: 25, heightPct: 12.5 },
    })
    const border = await measurePrintFrame(
      await magentaBoxPng({ x: 1, y: 0, width: 2, height: 3 }, { edge: 3 }),
    )
    expect(border).toEqual({
      kind: 'measured',
      frame: { leftPct: 33.33, topPct: 0, widthPct: 66.67, heightPct: 100 },
    })
  })

  test('a hole is LOW_FILL; no magenta or a tiny box is NO_RECTANGLE; no picture is UNREADABLE_IMAGE', async () => {
    expect(
      await measurePrintFrame(
        await magentaBoxPng({ x: 300, y: 300, width: 300, height: 300 }, { holed: true }),
      ),
    ).toEqual({ kind: 'failed', reason: 'LOW_FILL' })
    expect(
      await measurePrintFrame(await magentaBoxPng({ x: 0, y: 0, width: 0, height: 0 })),
    ).toEqual({
      kind: 'failed',
      reason: 'NO_RECTANGLE',
    })
    expect(
      await measurePrintFrame(await magentaBoxPng({ x: 10, y: 10, width: 15, height: 15 })),
    ).toEqual({
      kind: 'failed',
      reason: 'NO_RECTANGLE',
    })
    for (const bytes of [new Uint8Array([1, 2, 3]), new Uint8Array()]) {
      expect(await measurePrintFrame(bytes)).toEqual({ kind: 'failed', reason: 'UNREADABLE_IMAGE' })
    }
  })

  test('the calibration design measures to the whole picture', async () => {
    expect(await measurePrintFrame(await calibrationDesignPng())).toEqual({
      kind: 'measured',
      frame: { leftPct: 0, topPct: 0, widthPct: 100, heightPct: 100 },
    })
  })
})
