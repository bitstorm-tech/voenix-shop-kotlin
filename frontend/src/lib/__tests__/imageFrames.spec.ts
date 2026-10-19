import { describe, expect, it } from 'vitest'
import {
  IMAGE_FRAME_IDS,
  imageFrameMaskStyle,
  imageFrameMaskSvg,
  imageFrameMaskUrl,
} from '@/lib/imageFrames'

function viewBox(svg: string) {
  const [, , width, height] = svg
    .match(/viewBox="([^"]+)"/)![1]!
    .split(' ')
    .map(Number)
  return { width: width!, height: height! }
}

/** Every coordinate pair of the frame's outline (polygon points or path commands). */
function outlinePoints(svg: string): Array<[number, number]> {
  const outline = svg.match(/points="([^"]+)"/)?.[1] ?? svg.match(/ d="([^"]+)"/)?.[1]
  if (!outline) return []

  // Drop the arc radii, they are not coordinates
  return [...outline.replace(/A[\d.]+,[\d.]+/g, 'A').matchAll(/(-?[\d.]+),(-?[\d.]+)/g)].map(
    (match) => [Number(match[1]), Number(match[2])],
  )
}

describe('imageFrames', () => {
  it('sizes every mask to the frame aspect ratio with a constant short side', () => {
    for (const frame of IMAGE_FRAME_IDS) {
      expect(viewBox(imageFrameMaskSvg(frame, 1))).toEqual({ width: 1000, height: 1000 })
      expect(viewBox(imageFrameMaskSvg(frame, 200 / 90))).toEqual({ width: 2222, height: 1000 })
      expect(viewBox(imageFrameMaskSvg(frame, 0.5))).toEqual({ width: 1000, height: 2000 })
    }
  })

  it('falls back to a square mask for an unusable aspect ratio', () => {
    expect(viewBox(imageFrameMaskSvg('soft', 0))).toEqual({ width: 1000, height: 1000 })
    expect(viewBox(imageFrameMaskSvg('soft', Number.NaN))).toEqual({ width: 1000, height: 1000 })
  })

  it('keeps every outlined frame inside the mask and close to its edges', () => {
    for (const frame of ['torn', 'stamp', 'scallop'] as const) {
      for (const aspectRatio of [1, 200 / 90, 0.75]) {
        const svg = imageFrameMaskSvg(frame, aspectRatio)
        const { width, height } = viewBox(svg)
        const points = outlinePoints(svg)

        expect(points.length).toBeGreaterThan(40)
        for (const [x, y] of points) {
          expect(x).toBeGreaterThanOrEqual(0)
          expect(x).toBeLessThanOrEqual(width)
          expect(y).toBeGreaterThanOrEqual(0)
          expect(y).toBeLessThanOrEqual(height)
        }
        // The frame only eats into the border, it does not shrink the image to a small island.
        expect(Math.min(...points.map(([x]) => x))).toBeLessThan(80)
        expect(Math.max(...points.map(([x]) => x))).toBeGreaterThan(width - 80)
      }
    }
  })

  it('builds the same mask every time, so preview and print match', () => {
    for (const frame of IMAGE_FRAME_IDS) {
      expect(imageFrameMaskSvg(frame, 1.5)).toBe(imageFrameMaskSvg(frame, 1.5))
    }
  })

  it('exposes the mask as a stretched CSS mask image', () => {
    const style = imageFrameMaskStyle('oval', 1)

    expect(style.maskImage).toBe(`url("${imageFrameMaskUrl('oval', 1)}")`)
    expect(style.maskSize).toBe('100% 100%')
    expect(style.maskRepeat).toBe('no-repeat')
    expect(decodeURIComponent(imageFrameMaskUrl('oval', 1))).toContain('<ellipse')
  })
})
