import { describe, expect, it } from 'vitest'
import { computeCoverRect } from '@/lib/geometry'

describe('geometry', () => {
  it('cover-crops a portrait image to fill a wide print frame', () => {
    const rect = computeCoverRect({ x: 0, y: 0, width: 320, height: 144 }, 0.5)

    expect(rect).toEqual({
      x: 0,
      y: -248,
      width: 320,
      height: 640,
    })
  })

  it('cover-crops a wide image to fill a portrait print frame', () => {
    const rect = computeCoverRect({ x: 0, y: 0, width: 200, height: 400 }, 2)

    expect(rect).toEqual({
      x: -300,
      y: 0,
      width: 800,
      height: 400,
    })
  })
})
