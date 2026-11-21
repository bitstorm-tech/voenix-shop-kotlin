import sharp from 'sharp'
import type {
  SpodAccess,
  SpodClient,
  SpodError,
  SpodResult,
  SpodView,
  SpodViews,
} from '@/modules/spod'
import { roundHalfUp } from '@/platform/money'
import type { PrintFrame } from './repository'

/**
 * Where the partner prints on a product type, measured from pairs of complementary previews (SPOD
 * sync decision 7). The API says nothing about where a hotspot lies on a mockup, nor about which
 * part of a design the product shows. A run uploads two complementary 4000 × 4000 PNGs (`A` and
 * `B = 255 - A`) once, lets the partner render both on each front hotspot, and subtracts them:
 *
 * - Outside the print area both previews render the same mockup pixels, so `B - A` is identically
 *   zero regardless of garment colour, texture, or transparent-background RGB bytes, and no blank
 *   mockup from `/views` is needed for calibration.
 * - Inside the print area the red channel flips uniformly from 0 to 255 (`dR = B.r - A.r > 0`)
 *   with no internal seams, yielding the exact visible mask; additive specular highlights cancel
 *   out and shadows only scale the positive signal.
 * - The green and blue channels carry a 13-unit self-synchronizing alternating binary ruler along
 *   X and Y respectively. Because every consecutive pair of `(sign, width_i, width_{i+1})` is
 *   unique, the zero-crossings of `A.g - B.g` and `A.b - B.b` recover the full 1:1 design frame
 *   to sub-pixel precision even under heavy shading, colour tinting, gamma, cutouts, zippers, or
 *   all-over cropping where large parts of the design are hidden.
 */

export const CALIBRATION_DESIGN_A_FILE_NAME = 'calibration-1x1-a.png'
export const CALIBRATION_DESIGN_B_FILE_NAME = 'calibration-1x1-b.png'

/**
 * Self-synchronizing 9-stripe alternating ruler (sum = 13 units). Every consecutive pair of stripe
 * widths together with its alternating sign `(-1)^i` occurs exactly once:
 * `(-,1,1), (+,1,2), (-,2,2), (+,2,2), (-,2,1), (+,1,1), (-,1,2), (+,2,1)`.
 */
export const RULER_WIDTHS = [1, 1, 2, 2, 2, 1, 1, 2, 1] as const
export const RULER_TOTAL = RULER_WIDTHS.reduce((sum, width) => sum + width, 0)

interface RulerTransition {
  /** Normalized coordinate in `(0, 1)` where the ruler changes sign. */
  u: number
  /** `+1` for `-1 -> +1` (rising), `-1` for `+1 -> -1` (falling). */
  polarity: 1 | -1
}

const RULER_TRANSITIONS: readonly RulerTransition[] = (() => {
  const transitions: RulerTransition[] = []
  let acc = 0
  for (let index = 0; index < RULER_WIDTHS.length - 1; index++) {
    acc += RULER_WIDTHS[index] ?? 0
    transitions.push({ u: acc / RULER_TOTAL, polarity: index % 2 === 0 ? 1 : -1 })
  }
  return transitions
})()

/** The ruler's sign (`-1` or `+1`) at normalized coordinate `t` in `[0, 1]`. */
export function rulerSign(t: number): 1 | -1 {
  const scaled = t * RULER_TOTAL
  let acc = 0
  for (let index = 0; index < RULER_WIDTHS.length; index++) {
    acc += RULER_WIDTHS[index] ?? 0
    if (scaled < acc || index === RULER_WIDTHS.length - 1) {
      return index % 2 === 0 ? -1 : 1
    }
  }
  return -1
}

/**
 * The RGB value of calibration design `variant` (`'a'` or `'b'`) at normalized `(u, v)` in
 * `[0, 1]²`. Design `'b'` is the exact bitwise complement `255 - a` at every pixel.
 */
export function calibrationColorAt(
  u: number,
  v: number,
  variant: 'a' | 'b',
): readonly [number, number, number] {
  const greenPositive = rulerSign(u) > 0
  const bluePositive = rulerSign(v) > 0
  if (variant === 'a') {
    return [0, greenPositive ? 255 : 0, bluePositive ? 255 : 0]
  }
  return [255, greenPositive ? 0 : 255, bluePositive ? 0 : 255]
}

let cachedDefaultDesigns: Promise<{ a: Buffer; b: Buffer }> | undefined

/**
 * Generates the complementary calibration PNG pair (`a` and `b`) at `edge × edge` pixels (default
 * `4000 × 4000`, the partner's maximum upload dimension so high-DPI non-apparel and large poster
 * hotspots accept the design). Built from the 13 × 13 unit grid with nearest-neighbour upscaling
 * so encoding takes milliseconds and each paletted PNG compresses to a few kilobytes.
 */
export function calibrationDesignPngs(
  edge = CALIBRATION_EDGE_PIXELS,
): Promise<{ a: Buffer; b: Buffer }> {
  if (edge === CALIBRATION_EDGE_PIXELS) {
    cachedDefaultDesigns ??= buildCalibrationDesignPngs(edge)
    return cachedDefaultDesigns
  }
  return buildCalibrationDesignPngs(edge)
}

async function buildCalibrationDesignPngs(edge: number): Promise<{ a: Buffer; b: Buffer }> {
  const build = (variant: 'a' | 'b') => {
    const grid = Buffer.allocUnsafe(RULER_TOTAL * RULER_TOTAL * 3)
    const red = variant === 'a' ? 0 : 255
    for (let gy = 0; gy < RULER_TOTAL; gy++) {
      const blue = (rulerSign((gy + 0.5) / RULER_TOTAL) === 1) === (variant === 'a') ? 255 : 0
      for (let gx = 0; gx < RULER_TOTAL; gx++) {
        const offset = (gy * RULER_TOTAL + gx) * 3
        grid[offset] = red
        grid[offset + 1] =
          (rulerSign((gx + 0.5) / RULER_TOTAL) === 1) === (variant === 'a') ? 255 : 0
        grid[offset + 2] = blue
      }
    }
    return sharp(grid, { raw: { width: RULER_TOTAL, height: RULER_TOTAL, channels: 3 } })
      .resize(edge, edge, { kernel: 'nearest', fit: 'fill' })
      .png({ palette: true, colours: 8 })
      .toBuffer()
  }
  const [a, b] = await Promise.all([build('a'), build('b')])
  return { a, b }
}

/** Why a preview pair yielded no frame; the names travel into the sync report's warning detail. */
export type MeasurementFailure =
  | 'UNREADABLE_IMAGE'
  | 'NO_PRINT_AREA'
  | 'DISTORTED_PRINT'
  | 'OUTSIDE_PICTURE'

export type PrintFrameMeasurement =
  | { kind: 'measured'; frame: PrintFrame; mask: string | null; visiblePixels: number }
  | { kind: 'failed'; reason: MeasurementFailure }

/**
 * Measures where the full 1:1 design lies in a preview pair and which part of it the product shows.
 *
 * Both previews are decoded with premultiplied alpha so transparent pixels always compare equal.
 * The pixel difference `B - A` isolates the printed pixels (`max(max(dR, 0), |dG|, |dB|) > 12`),
 * removes isolated single-pixel speckles, and projects `dG` (columns) and `dB` (rows) to locate
 * sub-pixel zero-crossings of the 13-unit binary ruler. Matching those crossings to the known
 * transitions and refining by ordinary least squares recovers the full square frame, which is then
 * verified in 2D across every interior visible pixel.
 *
 * `mask` is the visible part of the frame as a 256 × 256 PNG data URL with smooth anti-aliased
 * boundary alpha (interior shaded pixels stay fully opaque), or `null` when the whole frame sits
 * inside the picture and at least 97 % of it is visible.
 */
export async function measurePrintFrame(
  previewA: Uint8Array,
  previewB: Uint8Array,
): Promise<PrintFrameMeasurement> {
  let imageA
  let imageB
  try {
    // Inside the try: sharp throws synchronously for some inputs (an empty buffer), not only later.
    imageA = await pixelsOf(previewA)
    imageB = await pixelsOf(previewB)
  } catch {
    return { kind: 'failed', reason: 'UNREADABLE_IMAGE' }
  }
  const { width, height } = imageA.info
  if (width === 0 || height === 0 || imageB.info.width !== width || imageB.info.height !== height) {
    return { kind: 'failed', reason: 'UNREADABLE_IMAGE' }
  }

  const total = width * height
  const dG = new Float32Array(total)
  const dB = new Float32Array(total)
  const signal = new Float32Array(total)
  const rawVisible = new Uint8Array(total)

  for (let index = 0; index < total; index++) {
    const offset = index * 4
    const alphaA = (imageA.data[offset + 3] ?? 0) / 255
    const alphaB = (imageB.data[offset + 3] ?? 0) / 255
    const redDiff = (imageB.data[offset] ?? 0) * alphaB - (imageA.data[offset] ?? 0) * alphaA
    const greenDiff =
      (imageA.data[offset + 1] ?? 0) * alphaA - (imageB.data[offset + 1] ?? 0) * alphaB
    const blueDiff =
      (imageA.data[offset + 2] ?? 0) * alphaA - (imageB.data[offset + 2] ?? 0) * alphaB
    dG[index] = greenDiff
    dB[index] = blueDiff
    const mag = Math.max(redDiff > 0 ? redDiff : 0, Math.abs(greenDiff), Math.abs(blueDiff))
    signal[index] = mag
    if (mag > MIN_SIGNAL) rawVisible[index] = 1
  }

  // Drop isolated 1-pixel noise specks while keeping every real multi-pixel feature intact.
  const visible = new Uint8Array(total)
  let totalVisible = 0
  for (let y = 0; y < height; y++) {
    const row = y * width
    for (let x = 0; x < width; x++) {
      const index = row + x
      if (rawVisible[index] !== 1) continue
      if (hasVisibleNeighbor(rawVisible, width, height, x, y)) {
        visible[index] = 1
        totalVisible++
      }
    }
  }
  if (totalVisible * 10_000 < MIN_BOX_PERCENT * MIN_BOX_PERCENT * total) {
    return { kind: 'failed', reason: 'NO_PRINT_AREA' }
  }

  const sigX = new Float64Array(width)
  const denX = new Float64Array(width)
  const weightX = new Float64Array(width)
  const sigY = new Float64Array(height)
  const denY = new Float64Array(height)
  const weightY = new Float64Array(height)

  for (let y = 0; y < height; y++) {
    const row = y * width
    for (let x = 0; x < width; x++) {
      const index = row + x
      if (visible[index] !== 1) continue
      const g = dG[index] ?? 0
      const b = dB[index] ?? 0
      const m = signal[index] ?? 0
      sigX[x] = (sigX[x] ?? 0) + g
      denX[x] = (denX[x] ?? 0) + Math.max(Math.abs(g), m)
      weightX[x] = (weightX[x] ?? 0) + 1
      sigY[y] = (sigY[y] ?? 0) + b
      denY[y] = (denY[y] ?? 0) + Math.max(Math.abs(b), m)
      weightY[y] = (weightY[y] ?? 0) + 1
    }
  }
  for (let x = 0; x < width; x++) {
    const den = denX[x] ?? 0
    if (den > 0) sigX[x] = (sigX[x] ?? 0) / den
  }
  for (let y = 0; y < height; y++) {
    const den = denY[y] ?? 0
    if (den > 0) sigY[y] = (sigY[y] ?? 0) / den
  }

  const axisX = decodeAxis(sigX, weightX, width)
  const axisY = decodeAxis(sigY, weightY, height)
  if (axisX === null || axisY === null) return { kind: 'failed', reason: 'DISTORTED_PRINT' }
  if (axisX.kind === 'distorted' || axisY.kind === 'distorted') {
    return { kind: 'failed', reason: 'DISTORTED_PRINT' }
  }

  const left = axisX.start
  const frameWidth = axisX.span
  const right = left + frameWidth
  const top = axisY.start
  const frameHeight = axisY.span
  const bottom = top + frameHeight

  if (frameWidth * 100 < MIN_BOX_PERCENT * width || frameHeight * 100 < MIN_BOX_PERCENT * height) {
    return { kind: 'failed', reason: 'NO_PRINT_AREA' }
  }

  // The calibration design is 1:1; reject wildly skewed or warped fits.
  const aspectRatio = frameWidth / width / (frameHeight / height)
  if (aspectRatio < MIN_ASPECT_RATIO || aspectRatio > MAX_ASPECT_RATIO) {
    return { kind: 'failed', reason: 'DISTORTED_PRINT' }
  }

  const leftPct = hundredths(left, width)
  const topPct = hundredths(top, height)
  const rightPct = hundredths(right, width)
  const bottomPct = hundredths(bottom, height)
  const widthPct = rightPct - leftPct
  const heightPct = bottomPct - topPct

  if (
    widthPct <= 0 ||
    heightPct <= 0 ||
    widthPct > MAX_FRAME_HUNDREDTHS ||
    heightPct > MAX_FRAME_HUNDREDTHS ||
    leftPct < MIN_ORIGIN_HUNDREDTHS ||
    topPct < MIN_ORIGIN_HUNDREDTHS ||
    leftPct >= 10_000 ||
    topPct >= 10_000 ||
    rightPct <= 0 ||
    bottomPct <= 0
  ) {
    return { kind: 'failed', reason: 'OUTSIDE_PICTURE' }
  }

  if (
    !verifyIn2D(visible, dG, dB, width, height, totalVisible, left, top, frameWidth, frameHeight)
  ) {
    return { kind: 'failed', reason: 'DISTORTED_PRINT' }
  }

  const boxLeft = Math.round(left)
  const boxTop = Math.round(top)
  const boxRight = Math.round(right)
  const boxBottom = Math.round(bottom)
  const boxWidth = boxRight - boxLeft
  const boxHeight = boxBottom - boxTop
  if (boxWidth <= 0 || boxHeight <= 0) return { kind: 'failed', reason: 'NO_PRINT_AREA' }

  const maskPixels = Buffer.alloc(boxWidth * boxHeight * 4)
  let visiblePixels = 0
  for (let y = 0; y < boxHeight; y++) {
    const srcY = boxTop + y
    if (srcY < 0 || srcY >= height) continue
    for (let x = 0; x < boxWidth; x++) {
      const srcX = boxLeft + x
      if (srcX < 0 || srcX >= width) continue
      const srcIndex = srcY * width + srcX
      if (visible[srcIndex] !== 1) continue
      const alpha = boundaryAwareAlpha(visible, signal, width, height, srcX, srcY)
      maskPixels[(y * boxWidth + x) * 4 + 3] = alpha
      if (alpha >= 128) visiblePixels++
    }
  }
  if (visiblePixels === 0) return { kind: 'failed', reason: 'NO_PRINT_AREA' }

  const whollyInside = leftPct >= 0 && topPct >= 0 && rightPct <= 10_000 && bottomPct <= 10_000
  const mask =
    whollyInside && visiblePixels * 100 >= MIN_FILL_PERCENT * boxWidth * boxHeight
      ? null
      : await sharp(maskPixels, { raw: { width: boxWidth, height: boxHeight, channels: 4 } })
          .resize(MASK_EDGE_PIXELS, MASK_EDGE_PIXELS, { fit: 'fill' })
          .png({ palette: true, colours: MASK_COLOURS })
          .toBuffer()

  return {
    kind: 'measured',
    frame: {
      leftPct: leftPct / 100,
      topPct: topPct / 100,
      widthPct: widthPct / 100,
      heightPct: heightPct / 100,
    },
    mask: mask === null ? null : `data:image/png;base64,${mask.toString('base64')}`,
    visiblePixels,
  }
}

/** True when at least one of the 8 neighbours of `(x, y)` is also marked visible. */
function hasVisibleNeighbor(
  visible: Uint8Array,
  width: number,
  height: number,
  x: number,
  y: number,
): boolean {
  const minY = Math.max(0, y - 1)
  const maxY = Math.min(height - 1, y + 1)
  const minX = Math.max(0, x - 1)
  const maxX = Math.min(width - 1, x + 1)
  for (let ny = minY; ny <= maxY; ny++) {
    const row = ny * width
    for (let nx = minX; nx <= maxX; nx++) {
      if ((nx !== x || ny !== y) && visible[row + nx] === 1) return true
    }
  }
  return false
}

/**
 * Interior visible pixels (all four cardinal neighbours visible) stay 100 % opaque even across dark
 * folds; only boundary pixels get smooth anti-aliased alpha scaled to their local interior signal.
 */
function boundaryAwareAlpha(
  visible: Uint8Array,
  signal: Float32Array,
  width: number,
  height: number,
  x: number,
  y: number,
): number {
  const isInterior =
    x > 0 &&
    x < width - 1 &&
    y > 0 &&
    y < height - 1 &&
    visible[y * width + (x - 1)] === 1 &&
    visible[y * width + (x + 1)] === 1 &&
    visible[(y - 1) * width + x] === 1 &&
    visible[(y + 1) * width + x] === 1
  if (isInterior) return 255

  const minY = Math.max(0, y - 1)
  const maxY = Math.min(height - 1, y + 1)
  const minX = Math.max(0, x - 1)
  const maxX = Math.min(width - 1, x + 1)
  let localMax = signal[y * width + x] ?? 0
  for (let ny = minY; ny <= maxY; ny++) {
    const row = ny * width
    for (let nx = minX; nx <= maxX; nx++) {
      const value = signal[row + nx] ?? 0
      if (value > localMax) localMax = value
    }
  }
  if (localMax <= 0) return 255
  const current = signal[y * width + x] ?? 0
  return Math.max(1, Math.min(255, Math.round((current / localMax) * 255)))
}

type AxisDecodeResult =
  | { kind: 'decoded'; start: number; span: number }
  | { kind: 'distorted' }
  | null

/**
 * Finds sub-pixel zero-crossings of the 1D ruler profile, aligns pairs of crossings against the
 * 8 known transitions, scores every candidate across all visible columns/rows, and refines the
 * winning hypothesis by ordinary least squares over all matching transitions.
 */
function decodeAxis(
  profile: Float64Array,
  weights: Float64Array,
  length: number,
): AxisDecodeResult {
  let maxWeight = 0
  for (let i = 0; i < length; i++) {
    const w = weights[i] ?? 0
    if (w > maxWeight) maxWeight = w
  }
  if (maxWeight === 0) return null
  const minWeight = Math.max(1, Math.min(3, Math.floor(maxWeight * 0.1)))

  const crossings: { pos: number; polarity: 1 | -1 }[] = []
  let lastSolidSign = 0
  let lastSolidIdx = -1
  for (let i = 0; i < length; i++) {
    if ((weights[i] ?? 0) < minWeight) continue
    const value = profile[i] ?? 0
    if (Math.abs(value) < MIN_SOLID_PROFILE) continue
    const sign = value > 0 ? 1 : -1
    if (lastSolidSign !== 0 && sign !== lastSolidSign && i - lastSolidIdx <= MAX_CROSSING_GAP) {
      let pos = (lastSolidIdx + i) / 2 + 0.5
      for (let k = lastSolidIdx; k < i; k++) {
        const v0 = profile[k] ?? 0
        const v1 = profile[k + 1] ?? 0
        if ((v0 <= 0 && v1 >= 0) || (v0 >= 0 && v1 <= 0)) {
          const denom = v1 - v0
          pos = Math.abs(denom) > 1e-6 ? k + 0.5 + -v0 / denom : k + 0.5
          break
        }
      }
      crossings.push({ pos, polarity: sign === 1 ? 1 : -1 })
    }
    lastSolidSign = sign
    lastSolidIdx = i
  }

  if (crossings.length < 2) return null

  let best: { start: number; span: number; score: number } | null = null
  for (let i = 0; i < crossings.length; i++) {
    const ci = crossings[i]
    if (ci === undefined) continue
    for (let j = i + 1; j < crossings.length; j++) {
      const cj = crossings[j]
      if (cj === undefined || cj.pos - ci.pos < MIN_CROSSING_SEPARATION) continue
      for (let a = 0; a < RULER_TRANSITIONS.length; a++) {
        const ta = RULER_TRANSITIONS[a]
        if (ta === undefined || ta.polarity !== ci.polarity) continue
        for (let b = a + 1; b < RULER_TRANSITIONS.length; b++) {
          const tb = RULER_TRANSITIONS[b]
          if (tb === undefined || tb.polarity !== cj.polarity) continue
          const span = (cj.pos - ci.pos) / (tb.u - ta.u)
          if (span * 100 < MIN_BOX_PERCENT * length || span > length * 4) continue
          const start = ci.pos - ta.u * span
          const score = scoreAxisCandidate(profile, weights, length, minWeight, start, span)
          if (best === null || score > best.score) {
            best = { start, span, score }
          }
        }
      }
    }
  }

  if (best === null || best.score < MIN_AXIS_SCORE) return { kind: 'distorted' }

  const tolerance = Math.max(3, best.span * 0.025)
  const inliers: { u: number; x: number }[] = []
  for (const transition of RULER_TRANSITIONS) {
    const predicted = best.start + transition.u * best.span
    let closest: { pos: number; err: number } | null = null
    for (const crossing of crossings) {
      if (crossing.polarity !== transition.polarity) continue
      const err = Math.abs(crossing.pos - predicted)
      if (err <= tolerance && (closest === null || err < closest.err)) {
        closest = { pos: crossing.pos, err }
      }
    }
    if (closest !== null) inliers.push({ u: transition.u, x: closest.pos })
  }

  if (inliers.length < 2) return { kind: 'distorted' }
  const meanU = inliers.reduce((sum, pt) => sum + pt.u, 0) / inliers.length
  const meanX = inliers.reduce((sum, pt) => sum + pt.x, 0) / inliers.length
  let sUU = 0
  let sUX = 0
  for (const pt of inliers) {
    sUU += (pt.u - meanU) ** 2
    sUX += (pt.u - meanU) * (pt.x - meanX)
  }
  if (sUU <= 0) return { kind: 'distorted' }
  const span = sUX / sUU
  const start = meanX - span * meanU
  return { kind: 'decoded', start, span }
}

function scoreAxisCandidate(
  profile: Float64Array,
  weights: Float64Array,
  length: number,
  minWeight: number,
  start: number,
  span: number,
): number {
  let numerator = 0
  let denominator = 0
  const edgeMargin = Math.max(1.5, span * 0.01)
  for (let i = 0; i < length; i++) {
    const w = weights[i] ?? 0
    if (w < minWeight) continue
    const center = i + 0.5
    const t = (center - start) / span
    if (t < 0 || t > 1) {
      denominator += w
      continue
    }
    let nearTransition = false
    for (const transition of RULER_TRANSITIONS) {
      if (Math.abs(center - (start + transition.u * span)) < edgeMargin) {
        nearTransition = true
        break
      }
    }
    if (nearTransition) continue
    numerator += w * (profile[i] ?? 0) * rulerSign(t)
    denominator += w
  }
  return denominator === 0 ? -1 : numerator / denominator
}

/**
 * Checks that the 1D ruler fits hold across the 2D visible surface: nearly all visible pixels must
 * lie inside the fitted frame, and away from stripe transitions both channels must match the
 * predicted ruler signs. Catches strongly rotated, curved, or warped mockups.
 */
function verifyIn2D(
  visible: Uint8Array,
  dG: Float32Array,
  dB: Float32Array,
  width: number,
  height: number,
  totalVisible: number,
  left: number,
  top: number,
  frameWidth: number,
  frameHeight: number,
): boolean {
  const edgeMarginX = Math.max(1.5, frameWidth * 0.012)
  const edgeMarginY = Math.max(1.5, frameHeight * 0.012)
  const boundMargin = 3

  const expectedX = new Int8Array(width)
  for (let x = 0; x < width; x++) {
    const center = x + 0.5
    if (center < left || center > left + frameWidth) continue
    let nearEdge = false
    for (const transition of RULER_TRANSITIONS) {
      if (Math.abs(center - (left + transition.u * frameWidth)) < edgeMarginX) {
        nearEdge = true
        break
      }
    }
    if (!nearEdge) expectedX[x] = rulerSign((center - left) / frameWidth)
  }

  const expectedY = new Int8Array(height)
  for (let y = 0; y < height; y++) {
    const center = y + 0.5
    if (center < top || center > top + frameHeight) continue
    let nearEdge = false
    for (const transition of RULER_TRANSITIONS) {
      if (Math.abs(center - (top + transition.u * frameHeight)) < edgeMarginY) {
        nearEdge = true
        break
      }
    }
    if (!nearEdge) expectedY[y] = rulerSign((center - top) / frameHeight)
  }

  let insideCount = 0
  let checkedCount = 0
  let matchedCount = 0
  for (let y = 0; y < height; y++) {
    const inY = y + 0.5 >= top - boundMargin && y + 0.5 <= top + frameHeight + boundMargin
    const ey = expectedY[y] ?? 0
    const row = y * width
    for (let x = 0; x < width; x++) {
      const index = row + x
      if (visible[index] !== 1) continue
      if (inY && x + 0.5 >= left - boundMargin && x + 0.5 <= left + frameWidth + boundMargin) {
        insideCount++
      }
      const ex = expectedX[x] ?? 0
      if (ex === 0 || ey === 0) continue
      checkedCount++
      const gOk = (dG[index] ?? 0) * ex > 0
      const bOk = (dB[index] ?? 0) * ey > 0
      if (gOk && bOk) matchedCount++
    }
  }

  if (insideCount * 100 < MIN_CONTAINMENT_PERCENT * totalVisible) return false
  if (checkedCount === 0) return false
  return matchedCount * 100 >= MIN_2D_AGREEMENT_PERCENT * checkedCount
}

const pixelsOf = (image: Uint8Array) =>
  sharp(image).ensureAlpha().raw().toBuffer({ resolveWithObject: true })

/** `pixel` as hundredths of a percent of `edge`, rounded half up. */
const hundredths = (pixel: number, edge: number) => roundHalfUp((pixel * 10_000) / edge)

/**
 * What one product type's calibration came to. `failed` is a measurement: the partner answered and
 * the type cannot be measured, so the article is written without a frame (`CALIBRATION_FAILED`).
 * `unavailable` is transport: the partner did not answer, nothing is known, and the article is
 * skipped (`CALIBRATION_UNAVAILABLE`). The detail is in the shop's own words.
 */
export type Calibration =
  | { kind: 'measured'; hotspot: string; frame: PrintFrame; mask: string | null }
  | { kind: 'failed'; detail: string }
  | { kind: 'unavailable'; detail: string }

export type SpodCalibration = ReturnType<typeof createCalibration>

interface UploadedCalibrationDesigns {
  a: string
  b: string
}

/**
 * The calibration half of one run; one instance lives exactly as long as the run. The two
 * complementary designs are uploaded once, on the first product type that needs them, and every
 * product type is measured once, on its first article. Only answers are cached: a refused call is
 * asked again by the next article that needs it, so one hiccup costs one article. Per product type
 * that is `2 + 2 * candidates` paced calls (views, hotspot check, two complementary previews per
 * candidate) plus the unpaced downloads of those previews.
 */
export function createCalibration(client: SpodClient, access: SpodAccess) {
  let uploadedDesigns: UploadedCalibrationDesigns | undefined
  const views = new Map<number, SpodViews>()
  const calibrations = new Map<number, Calibration>()

  const viewsOf = async (productTypeId: number): Promise<SpodResult<SpodViews>> => {
    const cached = views.get(productTypeId)
    if (cached !== undefined) return { kind: 'answered', value: cached }
    const answer = await client.views(access, productTypeId)
    if (answer.kind === 'answered') views.set(productTypeId, answer.value)
    return answer
  }

  const designIds = async (): Promise<SpodResult<UploadedCalibrationDesigns>> => {
    if (uploadedDesigns !== undefined) return { kind: 'answered', value: uploadedDesigns }
    const pngs = await calibrationDesignPngs()
    const answerA = await client.uploadDesign(access, CALIBRATION_DESIGN_A_FILE_NAME, pngs.a)
    if (answerA.kind === 'failed') return answerA
    const answerB = await client.uploadDesign(access, CALIBRATION_DESIGN_B_FILE_NAME, pngs.b)
    if (answerB.kind === 'failed') return answerB
    uploadedDesigns = { a: answerA.value, b: answerB.value }
    return { kind: 'answered', value: uploadedDesigns }
  }

  /** Renders one calibration design on `hotspot` and downloads its front view as PNG. */
  const renderPreview = async (
    productTypeId: number,
    appearanceId: string,
    front: SpodView,
    designId: string,
    hotspot: string,
  ): Promise<
    | { kind: 'image'; bytes: Uint8Array }
    | { kind: 'failed'; detail: string }
    | { kind: 'unavailable'; detail: string }
  > => {
    const answer = await client.previews(access, productTypeId, {
      configurations: [{ designId, hotspot }],
      appearanceId,
      width: PREVIEW_EDGE_PIXELS,
      height: PREVIEW_EDGE_PIXELS,
    })
    if (answer.kind === 'failed') {
      return { kind: 'unavailable', detail: `${hotspot} previews: ${answer.error}` }
    }
    const url = answer.value.images.find((image) => image.viewId === front.id)?.url
    if (url === undefined) {
      return { kind: 'failed', detail: `${hotspot}: ${UNREADABLE satisfies SpodError}` }
    }
    // Previews come as WebP unless the URL asks for PNG.
    const png = await client.download(`${url},mediaType=png`, access.timeoutSeconds)
    if (png.kind === 'failed') {
      return { kind: 'unavailable', detail: `${hotspot} preview download: ${png.error}` }
    }
    return { kind: 'image', bytes: png.value.bytes }
  }

  /**
   * One hotspot rendered with both complementary designs at 1000 × 1000 and measured against each
   * other. `visiblePixels` is what the hotspots of a type are compared by.
   */
  const preview = async (
    productTypeId: number,
    appearanceId: string,
    front: SpodView,
    designs: UploadedCalibrationDesigns,
    hotspot: string,
  ): Promise<{ calibration: Calibration; visiblePixels?: number }> => {
    const renderedA = await renderPreview(productTypeId, appearanceId, front, designs.a, hotspot)
    if (renderedA.kind !== 'image') return { calibration: renderedA }
    const renderedB = await renderPreview(productTypeId, appearanceId, front, designs.b, hotspot)
    if (renderedB.kind !== 'image') return { calibration: renderedB }
    const measurement = await measurePrintFrame(renderedA.bytes, renderedB.bytes)
    return measurement.kind === 'measured'
      ? {
          calibration: {
            kind: 'measured',
            hotspot,
            frame: measurement.frame,
            mask: measurement.mask,
          },
          visiblePixels: measurement.visiblePixels,
        }
      : { calibration: { kind: 'failed', detail: `${hotspot}: ${measurement.reason}` } }
  }

  /**
   * Every front hotspot that accepts the calibration design, previewed with both complementary
   * designs on an available colour of the front view; the hotspot that shows the most of the design
   * wins (an all-over print of a round button beats the square inside it) and a tie goes to the
   * first in the view's order. When nothing measured, the reasons of every attempt are the detail,
   * and the result is `unavailable` only when no attempt got as far as a picture.
   */
  const measure = async (productTypeId: number, appearanceId: number): Promise<Calibration> => {
    const answeredViews = await viewsOf(productTypeId)
    if (answeredViews.kind === 'failed') {
      return { kind: 'unavailable', detail: `views: ${answeredViews.error}` }
    }
    const front = frontView(answeredViews.value)
    if (front === undefined) return { kind: 'failed', detail: 'NO_FRONT_VIEW' }
    const designs = await designIds()
    if (designs.kind === 'failed') {
      return { kind: 'unavailable', detail: `designs/upload: ${designs.error}` }
    }
    const available = await client.availableHotspots(access, productTypeId, designs.value.a)
    if (available.kind === 'failed') {
      return { kind: 'unavailable', detail: `hotspots: ${available.error}` }
    }
    const candidates = front.hotspots
      .map((hotspot) => hotspot.name)
      .filter((name) => available.value.includes(name))
    if (candidates.length === 0) return { kind: 'failed', detail: 'NO_PRINTABLE_HOTSPOT' }

    // Prefer the article's colour when it appears on the front view, otherwise any colour the view
    // offers so one colour without a mockup cannot fail calibration for the whole product type.
    const calibrationAppearance =
      front.images.find((image) => image.appearanceId === String(appearanceId))?.appearanceId ??
      front.images[0]?.appearanceId ??
      String(appearanceId)

    const attempts: Awaited<ReturnType<typeof preview>>[] = []
    for (const hotspot of candidates) {
      attempts.push(
        await preview(productTypeId, calibrationAppearance, front, designs.value, hotspot),
      )
    }
    let largest: { calibration: Calibration; visiblePixels: number } | undefined
    for (const attempt of attempts) {
      if (
        attempt.visiblePixels !== undefined &&
        attempt.visiblePixels > (largest?.visiblePixels ?? -1)
      ) {
        largest = { calibration: attempt.calibration, visiblePixels: attempt.visiblePixels }
      }
    }
    if (largest !== undefined) return largest.calibration
    const detail = attempts
      .flatMap((attempt) =>
        attempt.calibration.kind === 'measured' ? [] : [attempt.calibration.detail],
      )
      .join(', ')
    return attempts.every((attempt) => attempt.calibration.kind === 'unavailable')
      ? { kind: 'unavailable', detail }
      : { kind: 'failed', detail }
  }

  return {
    /** The blank front mockup of one colour, or `null` when the views answer none. */
    mockupUrl: async (productTypeId: number, appearanceId: number): Promise<string | null> => {
      const answer = await viewsOf(productTypeId)
      if (answer.kind === 'failed') return null
      const front = frontView(answer.value)
      return front === undefined ? null : (mockupOf(front, appearanceId) ?? null)
    },

    /**
     * The hotspot, frame, and mask of `productTypeId`, measured once per run on an available colour
     * of its front view; they are the same on every colour. An unavailable answer is not cached.
     */
    calibrate: async (productTypeId: number, appearanceId: number): Promise<Calibration> => {
      const cached = calibrations.get(productTypeId)
      if (cached !== undefined) return cached
      const calibration = await measure(productTypeId, appearanceId)
      if (calibration.kind !== 'unavailable') calibrations.set(productTypeId, calibration)
      return calibration
    },
  }
}

/**
 * The view the sync prints on: the one carrying a `…_FRONT` hotspot. Not matched by name, because
 * `views` names the sides in the account's language (`Vorne`), and not by id, because the partner
 * numbers views per type; the hotspot names are the API's own constants. A preview image belongs
 * to this view when it carries the view's id.
 */
const frontView = (views: SpodViews): SpodView | undefined =>
  views.views.find((view) => view.hotspots.some((hotspot) => hotspot.name.endsWith('_FRONT')))

/** The URL of the blank mockup `view` shows of one colour. */
const mockupOf = (view: SpodView, appearanceId: number): string | undefined =>
  view.images.find((candidate) => candidate.appearanceId === String(appearanceId))?.image

const UNREADABLE = 'PROVIDER_ANSWER_UNREADABLE'
/** Partner's maximum pixel dimension so high-DPI non-apparel and large posters accept calibration. */
const CALIBRATION_EDGE_PIXELS = 4000
/** The partner takes multiples of 50; 1000 px makes a pixel a tenth of a percent. */
const PREVIEW_EDGE_PIXELS = 1000
/** Minimum differential channel change (out of 255) that counts as a printed pixel. */
const MIN_SIGNAL = 12
/** Minimum normalized 1D profile magnitude considered firmly inside a positive or negative stripe. */
const MIN_SOLID_PROFILE = 0.25
/** Maximum column/row gap across which a sign flip still counts as one clean zero-crossing. */
const MAX_CROSSING_GAP = 12
/** Minimum pixel separation between two paired zero-crossings during hypothesis generation. */
const MIN_CROSSING_SEPARATION = 4
/** Minimum 1D sign-correlation score for a valid ruler match. */
const MIN_AXIS_SCORE = 0.75
/** Minimum percent of visible pixels that must fall within the fitted frame (with a small margin). */
const MIN_CONTAINMENT_PERCENT = 90
/** Minimum percent of 2D interior visible pixels whose green and blue signs match the ruler. */
const MIN_2D_AGREEMENT_PERCENT = 85
/** Allowed aspect ratio range of the fitted 1:1 design frame before counting as distorted. */
const MIN_ASPECT_RATIO = 0.65
const MAX_ASPECT_RATIO = 1.55
const MIN_BOX_PERCENT = 2
const MIN_FILL_PERCENT = 97
/** Match `ck_article_spod_articles_print_frame` (V39): up to 400 % wide/tall, origin ≥ -300 %. */
const MAX_FRAME_HUNDREDTHS = 40_000
const MIN_ORIGIN_HUNDREDTHS = -30_000
/** The mask is stretched over the frame by the browser; 256 px keeps a round edge smooth. */
const MASK_EDGE_PIXELS = 256
/** Sixteen shades of alpha smooth the edge and keep the mask near 2 kB. */
const MASK_COLOURS = 16
