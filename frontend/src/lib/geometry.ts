export interface Rect {
  x: number
  y: number
  width: number
  height: number
}

export function computeFrameRect(imageRect: Rect, frameAspectRatio: number): Rect {
  if (imageRect.width === 0 || imageRect.height === 0) {
    return { x: 0, y: 0, width: 0, height: 0 }
  }

  const imgAspect = imageRect.width / imageRect.height
  let fw: number, fh: number

  if (frameAspectRatio > imgAspect) {
    fw = imageRect.width
    fh = fw / frameAspectRatio
  } else {
    fh = imageRect.height
    fw = fh * frameAspectRatio
  }

  return {
    x: imageRect.x + (imageRect.width - fw) / 2,
    y: imageRect.y + (imageRect.height - fh) / 2,
    width: fw,
    height: fh,
  }
}

export function computeCoverRect(frameRect: Rect, contentAspectRatio: number): Rect {
  if (
    frameRect.width === 0 ||
    frameRect.height === 0 ||
    !Number.isFinite(contentAspectRatio) ||
    contentAspectRatio <= 0
  ) {
    return { x: frameRect.x, y: frameRect.y, width: 0, height: 0 }
  }

  const frameAspectRatio = frameRect.width / frameRect.height
  let width: number
  let height: number

  if (contentAspectRatio > frameAspectRatio) {
    height = frameRect.height
    width = height * contentAspectRatio
  } else {
    width = frameRect.width
    height = width / contentAspectRatio
  }

  return {
    x: frameRect.x + (frameRect.width - width) / 2,
    y: frameRect.y + (frameRect.height - height) / 2,
    width,
    height,
  }
}
