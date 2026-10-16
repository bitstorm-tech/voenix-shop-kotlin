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
 * Where the partner prints on a product type, measured from its own previews (SPOD sync decision
 * 7). The API says nothing about where a hotspot lies on a mockup, so a run uploads a flat magenta
 * square as a design, lets the partner render it on each front hotspot, and measures the magenta
 * rectangle in the rendered picture. Magenta is on no garment and no mockup background.
 */

export const CALIBRATION_DESIGN_FILE_NAME = 'calibration-1x1.png'

/** The design: an opaque magenta 2000 × 2000 PNG, square because a SPOD article prints `1:1`. */
export const calibrationDesignPng = (): Promise<Buffer> =>
  sharp({
    create: {
      width: CALIBRATION_EDGE_PIXELS,
      height: CALIBRATION_EDGE_PIXELS,
      channels: 3,
      background: '#ff00ff',
    },
  })
    .png()
    .toBuffer()

/** Why a preview yielded no frame; the names travel into the sync report's warning detail. */
export type MeasurementFailure = 'UNREADABLE_IMAGE' | 'NO_RECTANGLE' | 'LOW_FILL'

export type PrintFrameMeasurement =
  | { kind: 'measured'; frame: PrintFrame }
  | { kind: 'failed'; reason: MeasurementFailure }

/**
 * Measures the magenta rectangle of a preview as a frame in percent of the picture. A pixel counts
 * when it is opaque (alpha ≥ 250, which leaves out the renderer's translucent rim) and within 24 of
 * `#FF00FF` on each channel. The bounding box has to span 2 % of the picture both ways and be 97 %
 * magenta, otherwise it is no flat print of the square and the article gets no frame rather than a
 * wrong one. Edges are rounded half up to two decimals on their own and the width is the difference
 * of two rounded edges, so `left + width` never exceeds 100 by a rounding hair.
 */
export async function measurePrintFrame(image: Uint8Array): Promise<PrintFrameMeasurement> {
  let decoded
  try {
    // Inside the try: sharp throws synchronously for some inputs (an empty buffer), not only later.
    decoded = await sharp(image).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
  } catch {
    return { kind: 'failed', reason: 'UNREADABLE_IMAGE' }
  }
  const { data, info } = decoded
  const { width, height, channels } = info
  let minX = Number.MAX_SAFE_INTEGER
  let minY = Number.MAX_SAFE_INTEGER
  let maxX = -1
  let maxY = -1
  let magenta = 0
  for (let index = 0; index < width * height; index++) {
    const offset = index * channels
    const red = data[offset] ?? 0
    const green = data[offset + 1] ?? 0
    const blue = data[offset + 2] ?? 0
    const alpha = data[offset + 3] ?? 0
    if (
      alpha >= MIN_ALPHA &&
      255 - red <= CHANNEL_TOLERANCE &&
      green <= CHANNEL_TOLERANCE &&
      255 - blue <= CHANNEL_TOLERANCE
    ) {
      const x = index % width
      const y = Math.floor(index / width)
      minX = Math.min(minX, x)
      minY = Math.min(minY, y)
      maxX = Math.max(maxX, x)
      maxY = Math.max(maxY, y)
      magenta++
    }
  }
  if (magenta === 0) return { kind: 'failed', reason: 'NO_RECTANGLE' }
  const boxWidth = maxX - minX + 1
  const boxHeight = maxY - minY + 1
  if (boxWidth * 100 < MIN_BOX_PERCENT * width || boxHeight * 100 < MIN_BOX_PERCENT * height) {
    return { kind: 'failed', reason: 'NO_RECTANGLE' }
  }
  if (magenta * 100 < MIN_FILL_PERCENT * boxWidth * boxHeight) {
    return { kind: 'failed', reason: 'LOW_FILL' }
  }
  const left = hundredths(minX, width)
  const top = hundredths(minY, height)
  return {
    kind: 'measured',
    frame: {
      leftPct: left / 100,
      topPct: top / 100,
      widthPct: (hundredths(maxX + 1, width) - left) / 100,
      heightPct: (hundredths(maxY + 1, height) - top) / 100,
    },
  }
}

/** `pixel` as hundredths of a percent of `edge`, rounded half up. */
const hundredths = (pixel: number, edge: number) => roundHalfUp((pixel * 10_000) / edge)

/**
 * What one product type's calibration came to. `failed` is a measurement: the partner answered and
 * the type cannot be measured, so the article is written without a frame (`CALIBRATION_FAILED`).
 * `unavailable` is transport: the partner did not answer, nothing is known, and the article is
 * skipped (`CALIBRATION_UNAVAILABLE`). The detail is in the shop's own words.
 */
export type Calibration =
  | { kind: 'measured'; hotspot: string; frame: PrintFrame }
  | { kind: 'failed'; detail: string }
  | { kind: 'unavailable'; detail: string }

export type SpodCalibration = ReturnType<typeof createCalibration>

/**
 * The calibration half of one run; one instance lives exactly as long as the run. The design is
 * uploaded once, on the first product type that needs it, and every product type is measured once,
 * on its first article. Only answers are cached: a refused call is asked again by the next article
 * that needs it, so one hiccup costs one article. Per product type that is `2 + candidates` paced
 * calls (views, hotspot check, one preview per candidate) plus unpaced preview downloads.
 */
export function createCalibration(client: SpodClient, access: SpodAccess) {
  let design: string | undefined
  const views = new Map<number, SpodViews>()
  const calibrations = new Map<number, Calibration>()

  const viewsOf = async (productTypeId: number): Promise<SpodResult<SpodViews>> => {
    const cached = views.get(productTypeId)
    if (cached !== undefined) return { kind: 'answered', value: cached }
    const answer = await client.views(access, productTypeId)
    if (answer.kind === 'answered') views.set(productTypeId, answer.value)
    return answer
  }

  const designId = async (): Promise<SpodResult<string>> => {
    if (design !== undefined) return { kind: 'answered', value: design }
    const answer = await client.uploadDesign(
      access,
      CALIBRATION_DESIGN_FILE_NAME,
      await calibrationDesignPng(),
    )
    if (answer.kind === 'answered') design = answer.value
    return answer
  }

  /** One hotspot rendered on one colour at 1000 × 1000 and measured, or why it could not be. */
  const preview = async (
    productTypeId: number,
    appearanceId: number,
    front: SpodView,
    design: string,
    hotspot: string,
  ): Promise<Calibration> => {
    const answer = await client.previews(access, productTypeId, {
      configurations: [{ designId: design, hotspot }],
      appearanceId: String(appearanceId),
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
    const measurement = await measurePrintFrame(png.value.bytes)
    return measurement.kind === 'measured'
      ? { kind: 'measured', hotspot, frame: measurement.frame }
      : { kind: 'failed', detail: `${hotspot}: ${measurement.reason}` }
  }

  /**
   * Every front hotspot that accepts the design, previewed and measured; the largest box wins and a
   * tie goes to the first in the view's order. When nothing measured, the reasons of every attempt
   * are the detail, and the result is `unavailable` only when no attempt got as far as a picture.
   */
  const measure = async (productTypeId: number, appearanceId: number): Promise<Calibration> => {
    const answeredViews = await viewsOf(productTypeId)
    if (answeredViews.kind === 'failed') {
      return { kind: 'unavailable', detail: `views: ${answeredViews.error}` }
    }
    const front = frontView(answeredViews.value)
    if (front === undefined) return { kind: 'failed', detail: 'NO_FRONT_VIEW' }
    const design = await designId()
    if (design.kind === 'failed') {
      return { kind: 'unavailable', detail: `designs/upload: ${design.error}` }
    }
    const available = await client.availableHotspots(access, productTypeId, design.value)
    if (available.kind === 'failed') {
      return { kind: 'unavailable', detail: `hotspots: ${available.error}` }
    }
    const candidates = front.hotspots
      .map((hotspot) => hotspot.name)
      .filter((name) => available.value.includes(name))
    if (candidates.length === 0) return { kind: 'failed', detail: 'NO_PRINTABLE_HOTSPOT' }

    const attempts: Calibration[] = []
    for (const hotspot of candidates) {
      attempts.push(await preview(productTypeId, appearanceId, front, design.value, hotspot))
    }
    let largest: Extract<Calibration, { kind: 'measured' }> | undefined
    for (const attempt of attempts) {
      if (attempt.kind !== 'measured') continue
      const area = attempt.frame.widthPct * attempt.frame.heightPct
      if (largest === undefined || area > largest.frame.widthPct * largest.frame.heightPct) {
        largest = attempt
      }
    }
    if (largest !== undefined) return largest
    const detail = attempts
      .flatMap((attempt) => (attempt.kind === 'measured' ? [] : [attempt.detail]))
      .join(', ')
    return attempts.every((attempt) => attempt.kind === 'unavailable')
      ? { kind: 'unavailable', detail }
      : { kind: 'failed', detail }
  }

  return {
    /** The blank front mockup of one colour, or `null` when the views answer none. */
    mockupUrl: async (productTypeId: number, appearanceId: number): Promise<string | null> => {
      const answer = await viewsOf(productTypeId)
      if (answer.kind === 'failed') return null
      const image = frontView(answer.value)?.images.find(
        (candidate) => candidate.appearanceId === String(appearanceId),
      )
      return image?.image ?? null
    },

    /**
     * The hotspot and frame of `productTypeId`, measured once per run on the colour of its first
     * article; the box is the same on every colour. An unavailable answer is not cached.
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

const UNREADABLE = 'PROVIDER_ANSWER_UNREADABLE'
const CALIBRATION_EDGE_PIXELS = 2000
/** The partner takes multiples of 50; 1000 px makes a pixel a tenth of a percent. */
const PREVIEW_EDGE_PIXELS = 1000
/** Below this alpha a pixel belongs to the renderer's anti-aliased edge, not to the design. */
const MIN_ALPHA = 250
/** How far a channel may stray from `#FF00FF` and still be the design: the renderer resamples. */
const CHANNEL_TOLERANCE = 24
const MIN_BOX_PERCENT = 2
const MIN_FILL_PERCENT = 97
