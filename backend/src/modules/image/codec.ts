import sharp, { type Metadata, type Sharp } from 'sharp'

/**
 * Everything sharp does for this module: parsing a requested size, deriving a resized file from an
 * original, and normalizing an upload to WebP. The output settings are the Kotlin backend's: JPEG
 * quality 85 on white, PNG compression level 6, lossy WebP quality 85 with alpha quality 100,
 * effort (libwebp's method) 4, and sharp YUV. Every output is turned upright by the EXIF
 * orientation first: the encoders drop that tag, so a photo stored sideways would stay sideways.
 */

/** More decoded pixels than this are refused: a 40-megapixel image alone holds about 160 MB. */
const MAX_DECODED_PIXELS = 40_000_000
/** The most bytes an upload may have; the reader and the storage both enforce it. */
export const MAX_IMAGE_BYTES = 10 * 1024 * 1024

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
 * The widths the frontend requests; every other size is refused. Each width is a cache directory
 * that is never cleaned, and the public route is anonymous, so a free size would let anyone fill
 * the disk. The image is scaled to the width, keeping its aspect ratio, and a smaller original is
 * upscaled.
 */
export const IMAGE_WIDTHS = [80, 200, 400, 1000, 1600] as const

/** The width a requested size names, or `null`; the spelling must match exactly (no `080`). */
export function parseImageWidth(value: string): number | null {
  return IMAGE_WIDTHS.find((width) => String(width) === value) ?? null
}

/** Writes `original` resized to `width` in `format` to `target`. */
export async function writeDerivative(
  original: string,
  width: number,
  format: ImageFormat,
  target: string,
): Promise<void> {
  const resized = sharp(original, { limitInputPixels: MAX_DECODED_PIXELS })
    .autoOrient()
    .resize({ width })
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
    const upright = sharp(bytes, { limitInputPixels: MAX_DECODED_PIXELS }).autoOrient()
    return { webp: await webp(upright).toBuffer() }
  } catch {
    return { rejection: 'Invalid image data' }
  }
}
