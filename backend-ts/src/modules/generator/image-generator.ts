import type { PrintAspectRatio } from '@/modules/article'

/** Bytes plus the media type they are in: the upload, the provider's result, and the answer. */
export interface RawImage {
  bytes: Uint8Array<ArrayBuffer>
  contentType: string
}

/**
 * The image types the module accepts and serves, lowercase. One list for both directions: the
 * upload is checked against it, and so is the type fal.ai reports for the generated image.
 */
export const ALLOWED_IMAGE_CONTENT_TYPES: ReadonlySet<string> = new Set([
  'image/jpeg',
  'image/png',
  'image/webp',
])

/**
 * The seam that keeps the network out of the service: an image, the composed prompt text, and the
 * article's print format go in, the generated image comes out. `null` means "the provider did not
 * deliver an image" in every one of its forms; the reason is logged where it is known, in the
 * adapter. The ratio is passed in the provider's own spelling (`16:9`, `1:1`), which is also the
 * article module's stored value, so nothing translates it.
 */
export type ImageGenerator = (
  image: RawImage,
  prompt: string,
  aspectRatio: PrintAspectRatio,
) => Promise<RawImage | null>

/**
 * The generator of a dummy-mode deployment: it answers the uploaded image unchanged, in its own
 * content type, and ignores the ratio. The coin check and the spend still run; dummy mode avoids
 * provider cost, it does not change what the endpoint does.
 */
export const dummyImageGenerator: ImageGenerator = (image) => Promise.resolve(image)
