import { describe, expect, it } from 'vitest'
import { clampCropTransform, MAX_CROP_SCALE, MIN_CROP_SCALE } from '@/lib/cropTransform'
import type { Rect } from '@/lib/geometry'

describe('clampCropTransform', () => {
  const imageRect: Rect = { x: 0, y: 0, width: 320, height: 640 }
  const frameRect: Rect = { x: 0, y: 0, width: 320, height: 144 }
  const zeroDimensionCases: Array<{
    label: string
    imageRect: Rect
    frameRect: Rect
  }> = [
    {
      label: 'image width',
      imageRect: { x: 0, y: 0, width: 0, height: 100 },
      frameRect: { x: 0, y: 0, width: 80, height: 60 },
    },
    {
      label: 'image height',
      imageRect: { x: 0, y: 0, width: 100, height: 0 },
      frameRect: { x: 0, y: 0, width: 80, height: 60 },
    },
    {
      label: 'frame width',
      imageRect: { x: 0, y: 0, width: 100, height: 100 },
      frameRect: { x: 0, y: 0, width: 0, height: 60 },
    },
    {
      label: 'frame height',
      imageRect: { x: 0, y: 0, width: 100, height: 100 },
      frameRect: { x: 0, y: 0, width: 80, height: 0 },
    },
  ]

  it('clamps scale to the supported range', () => {
    expect(clampCropTransform({ scale: 0.25, panX: 0, panY: 0 }, imageRect, frameRect).scale).toBe(
      MIN_CROP_SCALE,
    )

    expect(clampCropTransform({ scale: 4, panX: 0, panY: 0 }, imageRect, frameRect).scale).toBe(
      MAX_CROP_SCALE,
    )
  })

  it('clamps pan to the scaled image overflow on each axis', () => {
    expect(clampCropTransform({ scale: 1, panX: 40, panY: 300 }, imageRect, frameRect)).toEqual({
      scale: 1,
      panX: 0,
      panY: 248,
    })

    expect(
      clampCropTransform(
        { scale: 3, panX: 200, panY: -200 },
        { x: 0, y: 0, width: 100, height: 100 },
        { x: 0, y: 0, width: 80, height: 60 },
      ),
    ).toEqual({
      scale: 3,
      panX: 110,
      panY: -120,
    })
  })

  it.each(zeroDimensionCases)(
    'keeps pan unchanged when $label is zero',
    ({ imageRect, frameRect }) => {
      expect(clampCropTransform({ scale: 2, panX: 75, panY: 150 }, imageRect, frameRect)).toEqual({
        scale: 2,
        panX: 75,
        panY: 150,
      })
    },
  )

  it('still clamps scale when a rect dimension is zero', () => {
    expect(
      clampCropTransform(
        { scale: 4, panX: 75, panY: 150 },
        { x: 0, y: 0, width: 100, height: 100 },
        { x: 0, y: 0, width: 80, height: 0 },
      ),
    ).toEqual({
      scale: MAX_CROP_SCALE,
      panX: 75,
      panY: 150,
    })
  })
})
