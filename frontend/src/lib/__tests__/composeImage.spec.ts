import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { composeImage } from '@/lib/composeImage'

function mockCanvasAndImage() {
  const ctx = {
    save: vi.fn(),
    restore: vi.fn(),
    translate: vi.fn(),
    scale: vi.fn(),
    rotate: vi.fn(),
    drawImage: vi.fn(),
    fillText: vi.fn(),
    fillRect: vi.fn(),
    measureText: vi.fn(() => ({ width: 100, alphabeticBaseline: 10 })),
    font: '',
    fillStyle: '',
    textAlign: '',
    textBaseline: '',
    shadowColor: '',
    shadowOffsetX: 0,
    shadowOffsetY: 0,
    shadowBlur: 0,
    globalCompositeOperation: 'source-over',
  }

  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(
    ctx as unknown as CanvasRenderingContext2D,
  )

  class FakeImage {
    naturalWidth = 1024
    naturalHeight = 1024
    onload: (() => void) | null = null
    onerror: ((err: unknown) => void) | null = null
    set src(_value: string) {
      queueMicrotask(() => this.onload?.())
    }
  }
  vi.stubGlobal('Image', FakeImage)
}

describe('composeImage', () => {
  beforeEach(() => {
    mockCanvasAndImage()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  async function composeAt(frameAspectRatio: number, outputWidth?: number) {
    return composeImage({
      imageUrl: 'blob:test',
      frameAspectRatio,
      cropTransform: { scale: 1, panX: 0, panY: 0 },
      screenFrameWidth: 400,
      textOverlays: [],
      cliparts: [],
      imageFrame: null,
      outputWidth,
    })
  }

  it('caps the longest edge at 4000 px for square, landscape, and portrait frames', async () => {
    const square = await composeAt(1)
    expect({ width: square.width, height: square.height }).toEqual({ width: 4000, height: 4000 })

    const landscape = await composeAt(1.5)
    expect({ width: landscape.width, height: landscape.height }).toEqual({
      width: 4000,
      height: 2667,
    })

    const portrait = await composeAt(0.5)
    expect({ width: portrait.width, height: portrait.height }).toEqual({
      width: 2000,
      height: 4000,
    })

    const teePortrait = await composeAt(0.75)
    expect({ width: teePortrait.width, height: teePortrait.height }).toEqual({
      width: 3000,
      height: 4000,
    })
  })

  it('falls back to a square canvas for non-positive or non-finite aspect ratios', async () => {
    const zero = await composeAt(0)
    expect({ width: zero.width, height: zero.height }).toEqual({ width: 4000, height: 4000 })

    const nan = await composeAt(Number.NaN, 2000)
    expect({ width: nan.width, height: nan.height }).toEqual({ width: 2000, height: 2000 })
  })
})
