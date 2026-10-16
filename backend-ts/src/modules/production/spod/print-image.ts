import { stat } from 'node:fs/promises'
import sharp from 'sharp'

/** The partner scales a design onto the garment; beyond this many pixels nothing improves. */
export const MAX_EDGE_PIXELS = 4096
/** 9.5 MiB: below the partner's 10 MB limit, with room for the multipart envelope. */
export const MAX_PNG_BYTES = 9_961_472
/** Three halvings take a 4096 px edge to 512 px; anything beyond that is not a print image. */
export const MAX_SHRINK_ATTEMPTS = 3

/** The bounded print-image problems; the values are persisted error codes. */
export type PrintImageError =
  /** No image on the line, or nothing at the path yet. */
  | 'PRINT_IMAGE_MISSING'
  /** sharp cannot decode the file. */
  | 'PRINT_IMAGE_UNREADABLE'
  /** Still over the byte budget after the bounded shrink. */
  | 'PRINT_IMAGE_TOO_LARGE'

/**
 * The PNG the partner's design upload accepts, made from a stored print image (WebP). The longest
 * edge is capped at `MAX_EDGE_PIXELS`; while the PNG is over `maxBytes` the image is halved and
 * encoded again, at most `MAX_SHRINK_ATTEMPTS` times. PNG is lossless, so making it smaller means
 * making the image smaller, and the bound keeps a pathological image from looping forever.
 * Transparency is kept. `maxBytes` is a parameter so a test can pin the loop with a small image.
 */
export async function printImagePng(
  path: string | null,
  maxBytes = MAX_PNG_BYTES,
): Promise<{ kind: 'converted'; png: Buffer } | { kind: 'failed'; error: PrintImageError }> {
  const isFile =
    path !== null &&
    (await stat(path).then(
      (s) => s.isFile(),
      () => false,
    ))
  if (path === null || !isFile) return { kind: 'failed', error: 'PRINT_IMAGE_MISSING' }
  try {
    let png = await sharp(path)
      .resize(MAX_EDGE_PIXELS, MAX_EDGE_PIXELS, { fit: 'inside', withoutEnlargement: true })
      .png()
      .toBuffer({ resolveWithObject: true })
    for (let shrinks = 0; png.data.length > maxBytes; shrinks++) {
      if (shrinks === MAX_SHRINK_ATTEMPTS) return { kind: 'failed', error: 'PRINT_IMAGE_TOO_LARGE' }
      png = await sharp(png.data)
        .resize(
          Math.max(1, Math.floor(png.info.width / 2)),
          Math.max(1, Math.floor(png.info.height / 2)),
        )
        .png()
        .toBuffer({ resolveWithObject: true })
    }
    return { kind: 'converted', png: png.data }
  } catch {
    return { kind: 'failed', error: 'PRINT_IMAGE_UNREADABLE' }
  }
}
