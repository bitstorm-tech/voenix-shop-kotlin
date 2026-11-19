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
 * 7). The API says nothing about where a hotspot lies on a mockup, nor about which part of a design
 * the product shows, so a run uploads a magenta square with a green square of half its edge in the
 * middle, lets the partner render it on each front hotspot, and compares each rendering with the
 * blank mockup. The green marker says where the whole design lies, even when the product cuts its
 * rim off; the pixels that differ from the blank mockup say which part of it is seen.
 */

export const CALIBRATION_DESIGN_FILE_NAME = 'calibration-1x1.png'

/**
 * The design: an opaque magenta 2000 × 2000 PNG, square because a SPOD article prints `1:1`, with
 * an opaque green 1000 × 1000 marker in its middle.
 */
export const calibrationDesignPng = async (): Promise<Buffer> => {
  const square = (edge: number, background: string) =>
    sharp({ create: { width: edge, height: edge, channels: 3, background } })
  return square(CALIBRATION_EDGE_PIXELS, '#ff00ff')
    .composite([
      {
        input: await square(CALIBRATION_EDGE_PIXELS / 2, '#00ff00')
          .png()
          .toBuffer(),
      },
    ])
    .png()
    .toBuffer()
}

/** Why a preview yielded no frame; the names travel into the sync report's warning detail. */
export type MeasurementFailure =
  | 'UNREADABLE_IMAGE'
  | 'NO_RECTANGLE'
  | 'LOW_FILL'
  | 'OUTSIDE_PICTURE'

export type PrintFrameMeasurement =
  | { kind: 'measured'; frame: PrintFrame; mask: string | null; visiblePixels: number }
  | { kind: 'failed'; reason: MeasurementFailure }

/**
 * Measures where the design lies in a preview and which part of it the product shows.
 *
 * The frame is the green marker's bounding box doubled around its middle, in percent of the
 * picture. A pixel is marker or design when it is opaque (alpha ≥ 250, which leaves out the
 * renderer's translucent rim) and within 24 of `#00FF00` or `#FF00FF` on each channel. The marker
 * has to span 1 % of the picture both ways, be 97 % green, and have design beyond each of its four
 * edges, otherwise it is no flat, whole print of the middle of the design and the article gets no
 * frame rather than a wrong one. Edges are rounded half up to two decimals on their own and the
 * width is the difference of two rounded edges, so `left + width` never exceeds 100 by a rounding
 * hair; a frame that leaves the picture is `OUTSIDE_PICTURE`.
 *
 * A pixel of the frame is visible when it differs from the same pixel of `blank`, the mockup
 * without a design, by more than 40 over its four channels: the renderer is deterministic, and
 * shading that takes a rim pixel out of the colour tolerance still leaves it changed. `mask` is
 * the visible part of the frame as a 256 × 256 PNG data URL, opaque where the design is seen, and
 * `null` when 97 % of the frame is: then the frame itself is what is printed.
 */
export async function measurePrintFrame(
  preview: Uint8Array,
  blank: Uint8Array,
): Promise<PrintFrameMeasurement> {
  let rendered
  let plain
  try {
    // Inside the try: sharp throws synchronously for some inputs (an empty buffer), not only later.
    rendered = await pixelsOf(preview)
    plain = await pixelsOf(blank)
  } catch {
    return { kind: 'failed', reason: 'UNREADABLE_IMAGE' }
  }
  const { width, height } = rendered.info
  if (plain.info.width !== width || plain.info.height !== height) {
    return { kind: 'failed', reason: 'UNREADABLE_IMAGE' }
  }
  const changed = new Uint8Array(width * height)
  const marker = emptyBox()
  const design = emptyBox()
  for (let index = 0; index < width * height; index++) {
    const offset = index * 4
    const red = rendered.data[offset] ?? 0
    const green = rendered.data[offset + 1] ?? 0
    const blue = rendered.data[offset + 2] ?? 0
    const alpha = rendered.data[offset + 3] ?? 0
    const difference =
      Math.abs(red - (plain.data[offset] ?? 0)) +
      Math.abs(green - (plain.data[offset + 1] ?? 0)) +
      Math.abs(blue - (plain.data[offset + 2] ?? 0)) +
      Math.abs(alpha - (plain.data[offset + 3] ?? 0))
    if (difference > MIN_DIFFERENCE) changed[index] = 1
    if (alpha < MIN_ALPHA) continue
    const x = index % width
    const y = Math.floor(index / width)
    if (red <= CHANNEL_TOLERANCE && 255 - green <= CHANNEL_TOLERANCE && blue <= CHANNEL_TOLERANCE) {
      grow(marker, x, y)
    } else if (
      255 - red <= CHANNEL_TOLERANCE &&
      green <= CHANNEL_TOLERANCE &&
      255 - blue <= CHANNEL_TOLERANCE
    ) {
      grow(design, x, y)
    }
  }
  if (marker.count === 0) return { kind: 'failed', reason: 'NO_RECTANGLE' }
  const markerWidth = marker.maxX - marker.minX + 1
  const markerHeight = marker.maxY - marker.minY + 1
  // The marker is half the design, so half the smallest box a print area may be.
  if (
    markerWidth * 200 < MIN_BOX_PERCENT * width ||
    markerHeight * 200 < MIN_BOX_PERCENT * height
  ) {
    return { kind: 'failed', reason: 'NO_RECTANGLE' }
  }
  if (
    marker.count * 100 < MIN_FILL_PERCENT * markerWidth * markerHeight ||
    // A marker the product's edge cuts through has no design beyond that edge.
    design.minX >= marker.minX ||
    design.minY >= marker.minY ||
    design.maxX <= marker.maxX ||
    design.maxY <= marker.maxY
  ) {
    return { kind: 'failed', reason: 'LOW_FILL' }
  }
  const left = marker.minX - markerWidth / 2
  const top = marker.minY - markerHeight / 2
  const right = left + 2 * markerWidth
  const bottom = top + 2 * markerHeight
  if (left < 0 || top < 0 || right > width || bottom > height) {
    return { kind: 'failed', reason: 'OUTSIDE_PICTURE' }
  }

  const boxLeft = Math.round(left)
  const boxTop = Math.round(top)
  const boxWidth = Math.round(right) - boxLeft
  const boxHeight = Math.round(bottom) - boxTop
  const visible = Buffer.alloc(boxWidth * boxHeight * 4)
  let visiblePixels = 0
  for (let y = 0; y < boxHeight; y++) {
    for (let x = 0; x < boxWidth; x++) {
      if (changed[(boxTop + y) * width + boxLeft + x] !== 1) continue
      visible[(y * boxWidth + x) * 4 + 3] = 255
      visiblePixels++
    }
  }
  const mask =
    visiblePixels * 100 >= MIN_FILL_PERCENT * boxWidth * boxHeight
      ? null
      : await sharp(visible, { raw: { width: boxWidth, height: boxHeight, channels: 4 } })
          .resize(MASK_EDGE_PIXELS, MASK_EDGE_PIXELS, { fit: 'fill' })
          .png({ palette: true, colours: MASK_COLOURS })
          .toBuffer()
  const leftPct = hundredths(left, width)
  const topPct = hundredths(top, height)
  return {
    kind: 'measured',
    frame: {
      leftPct: leftPct / 100,
      topPct: topPct / 100,
      widthPct: (hundredths(right, width) - leftPct) / 100,
      heightPct: (hundredths(bottom, height) - topPct) / 100,
    },
    mask: mask === null ? null : `data:image/png;base64,${mask.toString('base64')}`,
    visiblePixels,
  }
}

const pixelsOf = (image: Uint8Array) =>
  sharp(image).ensureAlpha().raw().toBuffer({ resolveWithObject: true })

const emptyBox = () => ({
  minX: Number.MAX_SAFE_INTEGER,
  minY: Number.MAX_SAFE_INTEGER,
  maxX: -1,
  maxY: -1,
  count: 0,
})

const grow = (box: ReturnType<typeof emptyBox>, x: number, y: number) => {
  box.minX = Math.min(box.minX, x)
  box.minY = Math.min(box.minY, y)
  box.maxX = Math.max(box.maxX, x)
  box.maxY = Math.max(box.maxY, y)
  box.count++
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
  | { kind: 'measured'; hotspot: string; frame: PrintFrame; mask: string | null }
  | { kind: 'failed'; detail: string }
  | { kind: 'unavailable'; detail: string }

export type SpodCalibration = ReturnType<typeof createCalibration>

/**
 * The calibration half of one run; one instance lives exactly as long as the run. The design is
 * uploaded once, on the first product type that needs it, and every product type is measured once,
 * on its first article. Only answers are cached: a refused call is asked again by the next article
 * that needs it, so one hiccup costs one article. Per product type that is `2 + candidates` paced
 * calls (views, hotspot check, one preview per candidate) plus the unpaced downloads of the blank
 * mockup and the previews.
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

  /**
   * One hotspot rendered on one colour at 1000 × 1000 and measured against `blank`, or why it
   * could not be. `visiblePixels` is what the hotspots of a type are compared by.
   */
  const preview = async (
    productTypeId: number,
    appearanceId: number,
    front: SpodView,
    design: string,
    hotspot: string,
    blank: Uint8Array,
  ): Promise<Calibration & { visiblePixels?: number }> => {
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
    const measurement = await measurePrintFrame(png.value.bytes, blank)
    return measurement.kind === 'measured'
      ? { ...measurement, hotspot }
      : { kind: 'failed', detail: `${hotspot}: ${measurement.reason}` }
  }

  /**
   * Every front hotspot that accepts the design, previewed and measured against the blank mockup of
   * the same colour; the hotspot that shows the most of the design wins (an all-over print of a
   * round button beats the square inside it) and a tie goes to the first in the view's order. When
   * nothing measured, the reasons of every attempt are the detail, and the result is `unavailable`
   * only when no attempt got as far as a picture.
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
    const mockup = mockupOf(front, appearanceId)
    if (mockup === undefined) return { kind: 'failed', detail: 'NO_FRONT_MOCKUP' }
    // Mockups come as WebP unless the URL asks for PNG, like the previews they are compared with.
    const blank = await client.download(`${mockup},mediaType=png`, access.timeoutSeconds)
    if (blank.kind === 'failed') {
      return { kind: 'unavailable', detail: `mockup download: ${blank.error}` }
    }

    const attempts: Awaited<ReturnType<typeof preview>>[] = []
    for (const hotspot of candidates) {
      attempts.push(
        await preview(productTypeId, appearanceId, front, design.value, hotspot, blank.value.bytes),
      )
    }
    let largest: { calibration: Calibration; visiblePixels: number } | undefined
    for (const { visiblePixels, ...calibration } of attempts) {
      if (visiblePixels !== undefined && visiblePixels > (largest?.visiblePixels ?? -1)) {
        largest = { calibration, visiblePixels }
      }
    }
    if (largest !== undefined) return largest.calibration
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
      const front = frontView(answer.value)
      return front === undefined ? null : (mockupOf(front, appearanceId) ?? null)
    },

    /**
     * The hotspot, frame, and mask of `productTypeId`, measured once per run on the colour of its
     * first article; they are the same on every colour. An unavailable answer is not cached.
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
const CALIBRATION_EDGE_PIXELS = 2000
/** The partner takes multiples of 50; 1000 px makes a pixel a tenth of a percent. */
const PREVIEW_EDGE_PIXELS = 1000
/** Below this alpha a pixel belongs to the renderer's anti-aliased edge, not to the design. */
const MIN_ALPHA = 250
/** How far a channel may stray from `#FF00FF` and still be the design: the renderer resamples. */
const CHANNEL_TOLERANCE = 24
/** How far a pixel's four channels together stray from the blank mockup when it is printed on. */
const MIN_DIFFERENCE = 40
const MIN_BOX_PERCENT = 2
const MIN_FILL_PERCENT = 97
/** The mask is stretched over the frame by the browser; 256 px keeps a round edge smooth. */
const MASK_EDGE_PIXELS = 256
/** Sixteen shades of alpha smooth the edge and keep the mask near 2 kB. */
const MASK_COLOURS = 16
