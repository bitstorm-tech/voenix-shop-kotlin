import { defineStore } from 'pinia'
import { ApiError, fetchJson } from '@/lib/api'

/**
 * The print image a cart or order line points at is gone: the id resolves to nothing the caller may
 * read. `GET /api/images/guest/{size}/{id}` answers that with a `404`, and there is no code on the
 * route, so the status is the discriminator (`docs/dev/backend/image-package.md`). No retry repairs
 * it — the only way forward is a fresh upload.
 */
export class PrintImageGoneError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'PrintImageGoneError'
  }
}

/**
 * Reading a print image as a blob. Most of the app renders these ids as `<img src>` URLs and never
 * needs this store; it exists for the one place that has to hold the bytes — the redesign, which
 * seeds a new editor draft from the image an order line was printed with.
 */
export const usePrintImagesStore = defineStore('print-images', () => {
  async function fetchPrintImageBlob(imageId: number, size: number): Promise<Blob> {
    try {
      return await fetchJson<Blob>(`/api/images/guest/${size}/${imageId}`, {
        responseType: 'blob',
      })
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) {
        throw new PrintImageGoneError(err.message)
      }

      throw err
    }
  }

  return { fetchPrintImageBlob }
})
