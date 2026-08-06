import { clampCropTransform } from '@/lib/cropTransform'
import { computeCoverRect } from '@/lib/geometry'
import type { CropFrameTransform } from '@/stores/shop/cropFrame'
import type { TextOverlay } from '@/stores/shop/textOverlays'

const UNDERLINE_OFFSET = 0.075

export async function composeImage(options: {
  imageUrl: string
  frameAspectRatio: number
  cropTransform: CropFrameTransform
  screenFrameWidth: number
  textOverlays: ReadonlyArray<TextOverlay>
  outputWidth?: number
}): Promise<HTMLCanvasElement> {
  const {
    imageUrl,
    frameAspectRatio,
    cropTransform,
    screenFrameWidth,
    textOverlays,
    outputWidth = 2048,
  } = options

  const img = await loadImage(imageUrl)

  const imageAspect = img.naturalWidth / img.naturalHeight
  const canvas = document.createElement('canvas')
  canvas.width = outputWidth
  canvas.height = Math.round(outputWidth / frameAspectRatio)
  const ctx = canvas.getContext('2d')!
  const frameRect = { x: 0, y: 0, width: canvas.width, height: canvas.height }
  const imageRect = computeCoverRect(frameRect, imageAspect)

  // Scale pan values from screen pixels to virtual pixels
  const screenToCanvas = canvas.width / (screenFrameWidth || 1)
  const virtualTransform = clampCropTransform(
    {
      scale: cropTransform.scale,
      panX: cropTransform.panX * screenToCanvas,
      panY: cropTransform.panY * screenToCanvas,
    },
    imageRect,
    frameRect,
  )

  // Draw cropped image
  ctx.save()
  // Apply crop transform (replicates CSS transform-origin: center + scale(S) translate(panX/S, panY/S))
  const cx = canvas.width / 2
  const cy = canvas.height / 2
  ctx.translate(cx, cy)
  ctx.scale(virtualTransform.scale, virtualTransform.scale)
  ctx.translate(
    virtualTransform.panX / virtualTransform.scale,
    virtualTransform.panY / virtualTransform.scale,
  )
  ctx.translate(-cx, -cy)
  ctx.drawImage(img, imageRect.x, imageRect.y, imageRect.width, imageRect.height)
  ctx.restore()

  // Draw text overlays
  if (textOverlays.length > 0) {
    await document.fonts.ready

    for (const overlay of textOverlays) {
      const canvasX = overlay.rx * canvas.width
      const canvasY = overlay.ry * canvas.height

      const canvasFontSize = overlay.fontSize * (canvas.width / 1024)

      const weight = overlay.bold ? 'bold' : 'normal'
      const style = overlay.italic ? 'italic' : 'normal'
      ctx.font = `${style} ${weight} ${canvasFontSize}px '${overlay.fontFamily}'`
      ctx.fillStyle = overlay.color
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'

      // Replicate CSS text-shadow: 0 1px 3px oklch(0 0 0 / 0.3)
      ctx.shadowColor = 'rgba(0, 0, 0, 0.3)'
      ctx.shadowOffsetX = 0
      ctx.shadowOffsetY = 1 * screenToCanvas
      ctx.shadowBlur = 3 * screenToCanvas

      ctx.save()
      ctx.translate(canvasX, canvasY)
      if (overlay.rotation) {
        ctx.rotate((overlay.rotation * Math.PI) / 180)
      }

      ctx.fillText(overlay.text, 0, 0)

      if (overlay.underline) {
        const metrics = ctx.measureText(overlay.text)
        const baselineY = -metrics.alphabeticBaseline
        const lineY = Math.round(baselineY + canvasFontSize * UNDERLINE_OFFSET)
        const screenFontSize = canvasFontSize / screenToCanvas
        const cssThickness = Math.max(1, Math.round(screenFontSize / 20))
        const lineThickness = Math.max(1, Math.round(cssThickness * screenToCanvas))
        const halfWidth = metrics.width / 2
        ctx.shadowColor = 'transparent'
        ctx.fillRect(-halfWidth, lineY, metrics.width, lineThickness)
      }
      ctx.restore()
    }

    ctx.shadowColor = 'transparent'
    ctx.shadowOffsetX = 0
    ctx.shadowOffsetY = 0
    ctx.shadowBlur = 0
  }

  return canvas
}

function loadImage(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = reject
    img.src = url
  })
}
