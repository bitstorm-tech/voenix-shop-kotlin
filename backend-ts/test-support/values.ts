import sharp from 'sharp'

/** The message a promise rejected with, or `'resolved'`. */
export const rejectionOf = (promise: Promise<unknown>): Promise<string> =>
  promise.then(
    () => 'resolved',
    (error: unknown) => (error instanceof Error ? error.message : String(error)),
  )

/** A small PNG of one colour, a valid upload for every image folder. */
export const pngBytes = (color = '#336699'): Promise<Buffer> =>
  sharp({ create: { width: 40, height: 30, channels: 3, background: color } })
    .png()
    .toBuffer()
