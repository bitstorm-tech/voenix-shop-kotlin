import sharp, { type Metadata, type Sharp } from 'sharp'

/**
 * Everything sharp does for this module: parsing a requested size, deriving a resized file from an
 * original, and normalizing an upload to WebP. The output settings are the Kotlin backend's: JPEG
 * quality 85 on white, PNG compression level 6, lossy WebP quality 85 with alpha quality 100,
 * effort (libwebp's method) 4, and sharp YUV.
 */

/** More decoded pixels than this are refused: a 40-megapixel image alone holds about 160 MB. */
const MAX_DECODED_PIXELS = 40_000_000
/** The most bytes an upload may have; the reader and the storage both enforce it. */
export const MAX_IMAGE_BYTES = 10 * 1024 * 1024
const MAX_DIMENSION = 4096

export type ImageFormat = 'jpeg' | 'png' | 'webp'

export const CONTENT_TYPES: Record<ImageFormat, string> = {
  jpeg: 'image/jpeg',
  png: 'image/png',
  webp: 'image/webp',
}

/** The format a delivery name asks for, by its extension. */
export function formatOfFilename(filename: string): ImageFormat | null {
  const extension = filename.slice(filename.lastIndexOf('.') + 1).toLowerCase()
  if (extension === 'jpg' || extension === 'jpeg') return 'jpeg'
  if (extension === 'png' || extension === 'webp') return extension
  return null
}

function formatOfContentType(value: string): ImageFormat | null {
  const type = (value.split(';')[0] ?? '').trim().toLowerCase()
  const format = Object.entries(CONTENT_TYPES).find(([, contentType]) => contentType === type)
  return format === undefined ? null : (format[0] as ImageFormat)
}

/**
 * A requested size: one width (`300`) or a box (`300x200`), each dimension 1 through 4096. The
 * image is scaled as large as possible inside it, keeping its aspect ratio: no crop, no padding,
 * and a smaller original is upscaled.
 */
export interface ImageSize {
  width: number
  height: number | null
  /** The canonical spelling, which is also the cache directory of this size. */
  key: string
}

export function parseImageSize(value: string): ImageSize | null {
  const match = /^(\d+)(?:x(\d+))?$/.exec(value)
  if (match === null) return null
  const width = dimension(match[1])
  const height = match[2] === undefined ? null : dimension(match[2])
  if (width === null || (match[2] !== undefined && height === null)) return null
  return {
    width,
    height,
    key: height === null ? String(width) : `${String(width)}x${String(height)}`,
  }
}

function dimension(digits: string | undefined): number | null {
  const value = Number(digits)
  return Number.isInteger(value) && value >= 1 && value <= MAX_DIMENSION ? value : null
}

/** Writes `original` resized to `size` in `format` to `target`. */
export async function writeDerivative(
  original: string,
  size: ImageSize,
  format: ImageFormat,
  target: string,
): Promise<void> {
  const resized = sharp(original, { limitInputPixels: MAX_DECODED_PIXELS }).resize(
    size.height === null
      ? { width: size.width }
      : { width: size.width, height: size.height, fit: 'inside' },
  )
  switch (format) {
    case 'jpeg':
      await resized.flatten({ background: '#ffffff' }).jpeg({ quality: 85 }).toFile(target)
      return
    case 'png':
      await resized.png({ compressionLevel: 6 }).toFile(target)
      return
    case 'webp':
      await webp(resized).toFile(target)
      return
  }
}

const webp = (image: Sharp) =>
  image.webp({ quality: 85, alphaQuality: 100, effort: 4, smartSubsample: true })

/**
 * The upload as WebP bytes, or the message its rejection reports on the `file` field: 1 byte
 * through 10 MiB, at most 40 megapixels, and a declared JPEG, PNG, or WebP content type that matches
 * the format sharp detects, so GIF is refused however it is offered.
 */
export async function normalizeToWebp(
  bytes: Uint8Array,
  contentType: string,
): Promise<{ webp: Buffer } | { rejection: string }> {
  const declared = formatOfContentType(contentType)
  if (declared === null) return { rejection: 'Only JPEG, PNG, and WebP uploads are supported' }
  if (bytes.length === 0) return { rejection: 'Image must not be empty' }
  if (bytes.length > MAX_IMAGE_BYTES) return { rejection: 'Image must not exceed 10 MiB' }
  let metadata: Metadata
  try {
    metadata = await sharp(bytes).metadata()
  } catch {
    return { rejection: 'Unsupported or invalid image data' }
  }
  if (metadata.width * metadata.height > MAX_DECODED_PIXELS) {
    return { rejection: 'Decoded image exceeds 40 megapixels' }
  }
  if (!['jpeg', 'png', 'webp'].includes(metadata.format)) {
    return { rejection: 'Invalid image data' }
  }
  if (metadata.format !== declared) {
    return { rejection: 'Declared and decoded image formats differ' }
  }
  try {
    return { webp: await webp(sharp(bytes, { limitInputPixels: MAX_DECODED_PIXELS })).toBuffer() }
  } catch {
    return { rejection: 'Invalid image data' }
  }
}
