import type { CropFrameTransform } from '@/stores/shop/cropFrame'
import type { Rect } from '@/lib/geometry'

export const MIN_CROP_SCALE = 1
export const MAX_CROP_SCALE = 3

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value))
}

export function clampCropTransform(
  transform: CropFrameTransform,
  imageRect: Rect,
  frameRect: Rect,
): CropFrameTransform {
  const scale = clamp(transform.scale, MIN_CROP_SCALE, MAX_CROP_SCALE)

  if (
    imageRect.width === 0 ||
    imageRect.height === 0 ||
    frameRect.width === 0 ||
    frameRect.height === 0
  ) {
    return { scale, panX: transform.panX, panY: transform.panY }
  }

  const maxPanX = Math.max(0, (imageRect.width * scale - frameRect.width) / 2)
  const maxPanY = Math.max(0, (imageRect.height * scale - frameRect.height) / 2)

  return {
    scale,
    panX: clamp(transform.panX, -maxPanX, maxPanX),
    panY: clamp(transform.panY, -maxPanY, maxPanY),
  }
}
