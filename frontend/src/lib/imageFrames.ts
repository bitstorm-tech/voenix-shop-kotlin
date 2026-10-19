/**
 * Image frames break the hard, straight edge where the design meets the product. A frame is an
 * alpha mask over the print image: opaque where the image stays, transparent where the product
 * shows through.
 *
 * Every mask is an SVG built only from the frame's aspect ratio: its short side is always
 * `MASK_SHORT_SIDE` units and all frame geometry is a fraction of it. The mask therefore fits any
 * image size, and the editor preview (a CSS `mask-image`) and the composed print image (the same
 * SVG drawn onto the canvas) are cut identically.
 */

export const IMAGE_FRAME_IDS = ['soft', 'oval', 'torn', 'stamp', 'scallop'] as const

export type ImageFrameId = (typeof IMAGE_FRAME_IDS)[number]

const MASK_SHORT_SIDE = 1000

interface MaskSize {
  width: number
  height: number
}

function maskSize(aspectRatio: number): MaskSize {
  const ratio = Number.isFinite(aspectRatio) && aspectRatio > 0 ? aspectRatio : 1
  return ratio >= 1
    ? { width: Math.round(MASK_SHORT_SIDE * ratio), height: MASK_SHORT_SIDE }
    : { width: MASK_SHORT_SIDE, height: Math.round(MASK_SHORT_SIDE / ratio) }
}

/** Rounds path coordinates so the SVG stays short. */
function n(value: number): string {
  return String(Math.round(value * 10) / 10)
}

function blurFilter(size: MaskSize, stdDeviation: number): string {
  return (
    `<filter id="f" filterUnits="userSpaceOnUse" x="0" y="0" width="${size.width}" height="${size.height}">` +
    `<feGaussianBlur stdDeviation="${n(stdDeviation)}"/></filter>`
  )
}

/** Deterministic PRNG (mulberry32), so a torn edge looks the same in the preview and the print. */
function seededRandom(seed: number): () => number {
  let state = seed >>> 0
  return () => {
    state = (state + 0x6d2b79f5) >>> 0
    let t = state
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

interface Edge {
  /** Start corner. */
  x: number
  y: number
  /** Unit direction along the edge (clockwise). */
  dx: number
  dy: number
  /** Unit normal pointing into the image. */
  nx: number
  ny: number
  length: number
}

/** The four edges of a rectangle inset by `inset`, walked clockwise from the top-left corner. */
function insetEdges(size: MaskSize, inset: number): Edge[] {
  const left = inset
  const top = inset
  const right = size.width - inset
  const bottom = size.height - inset
  const width = right - left
  const height = bottom - top

  return [
    { x: left, y: top, dx: 1, dy: 0, nx: 0, ny: 1, length: width },
    { x: right, y: top, dx: 0, dy: 1, nx: -1, ny: 0, length: height },
    { x: right, y: bottom, dx: -1, dy: 0, nx: 0, ny: -1, length: width },
    { x: left, y: bottom, dx: 0, dy: -1, nx: 1, ny: 0, length: height },
  ]
}

function softMask(size: MaskSize): string {
  const inset = MASK_SHORT_SIDE * 0.09
  return (
    blurFilter(size, MASK_SHORT_SIDE * 0.045) +
    `<rect x="${n(inset)}" y="${n(inset)}" width="${n(size.width - 2 * inset)}" height="${n(size.height - 2 * inset)}" ` +
    `rx="${n(MASK_SHORT_SIDE * 0.04)}" filter="url(#f)"/>`
  )
}

function ovalMask(size: MaskSize): string {
  const inset = MASK_SHORT_SIDE * 0.06
  return (
    blurFilter(size, MASK_SHORT_SIDE * 0.03) +
    `<ellipse cx="${n(size.width / 2)}" cy="${n(size.height / 2)}" rx="${n(size.width / 2 - inset)}" ` +
    `ry="${n(size.height / 2 - inset)}" filter="url(#f)"/>`
  )
}

/**
 * A torn paper edge: a slow random walk gives the tear its uneven depth, a fine jitter on top its
 * ragged fibres.
 */
function tornMask(size: MaskSize): string {
  const random = seededRandom(0x7e4a)
  const step = MASK_SHORT_SIDE * 0.012
  const jitter = MASK_SHORT_SIDE * 0.008
  const maxWalk = MASK_SHORT_SIDE * 0.04
  let walk = maxWalk / 2
  const points: string[] = []

  // Walking a rectangle inset by the shallowest tear keeps the corners off the image border too
  for (const edge of insetEdges(size, MASK_SHORT_SIDE * 0.012)) {
    const tear = () => {
      walk += (random() - 0.5) * MASK_SHORT_SIDE * 0.012
      walk = Math.min(maxWalk, Math.max(0, walk))
      return walk + random() * jitter
    }
    const point = (along: number, depth: number) =>
      `${n(edge.x + edge.dx * along + edge.nx * depth)},${n(edge.y + edge.dy * along + edge.ny * depth)}`

    // The corner tears in diagonally; the edge's own points keep out of both corner zones, so
    // neighbouring edges never cross each other.
    const corner = tear()
    points.push(point(corner, corner))
    const cornerZone = maxWalk + jitter
    const count = Math.max(1, Math.round((edge.length - 2 * cornerZone) / step))
    for (let index = 0; index <= count; index++) {
      points.push(point(cornerZone + ((edge.length - 2 * cornerZone) * index) / count, tear()))
    }
  }

  return `<polygon points="${points.join(' ')}"/>`
}

/**
 * A rectangle whose edges are made of semicircles: bitten inward like a stamp's perforation, or
 * bulging outward like a scalloped edge. Each edge gets a whole number of arcs, so the pattern
 * stretches slightly instead of ending in a cut-off arc at a corner.
 */
function arcEdgePath(size: MaskSize, inset: number, radius: number, gap: number, inward: boolean) {
  const edges = insetEdges(size, inset)
  const sweep = inward ? 0 : 1
  const commands = [`M${n(edges[0]!.x)},${n(edges[0]!.y)}`]

  for (const edge of edges) {
    const count = Math.max(1, Math.round(edge.length / (2 * radius + gap)))
    const segment = edge.length / count
    const arcRadius = Math.min(radius, segment / 2)
    const straight = (segment - 2 * arcRadius) / 2

    for (let index = 0; index < count; index++) {
      const start = segment * index
      const arcStart = start + straight
      const arcEnd = arcStart + 2 * arcRadius
      const end = start + segment
      commands.push(
        `L${n(edge.x + edge.dx * arcStart)},${n(edge.y + edge.dy * arcStart)}`,
        `A${n(arcRadius)},${n(arcRadius)} 0 0 ${sweep} ${n(edge.x + edge.dx * arcEnd)},${n(edge.y + edge.dy * arcEnd)}`,
        `L${n(edge.x + edge.dx * end)},${n(edge.y + edge.dy * end)}`,
      )
    }
  }

  return `<path d="${commands.join(' ')}Z"/>`
}

function stampMask(size: MaskSize): string {
  const radius = MASK_SHORT_SIDE * 0.022
  return arcEdgePath(size, MASK_SHORT_SIDE * 0.02, radius, radius * 0.9, true)
}

function scallopMask(size: MaskSize): string {
  const radius = MASK_SHORT_SIDE * 0.035
  return arcEdgePath(size, radius + MASK_SHORT_SIDE * 0.005, radius, 0, false)
}

const MASK_BUILDERS: Record<ImageFrameId, (size: MaskSize) => string> = {
  soft: softMask,
  oval: ovalMask,
  torn: tornMask,
  stamp: stampMask,
  scallop: scallopMask,
}

/** The frame's alpha mask as a standalone SVG document for a frame of the given aspect ratio. */
export function imageFrameMaskSvg(frameId: ImageFrameId, aspectRatio: number): string {
  const size = maskSize(aspectRatio)
  return (
    `<svg xmlns="http://www.w3.org/2000/svg" width="${size.width}" height="${size.height}" ` +
    `viewBox="0 0 ${size.width} ${size.height}" preserveAspectRatio="none">` +
    MASK_BUILDERS[frameId](size) +
    '</svg>'
  )
}

export function imageFrameMaskUrl(frameId: ImageFrameId, aspectRatio: number): string {
  return `data:image/svg+xml,${encodeURIComponent(imageFrameMaskSvg(frameId, aspectRatio))}`
}

/** Inline style that masks an element - stretched to its full box - with the frame. */
export function imageFrameMaskStyle(frameId: ImageFrameId, aspectRatio: number) {
  const image = `url("${imageFrameMaskUrl(frameId, aspectRatio)}")`
  return {
    maskImage: image,
    WebkitMaskImage: image,
    maskSize: '100% 100%',
    WebkitMaskSize: '100% 100%',
    maskRepeat: 'no-repeat',
    WebkitMaskRepeat: 'no-repeat',
  }
}
