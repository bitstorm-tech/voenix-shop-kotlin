import type { Auth } from '@/platform/auth/auth'
import type { Logger } from '@/platform/logging'
import type { Settings } from '@/platform/settings'
import { createExampleImages } from './example-images'
import { prepareImageRoots } from './files'
import { guestImageRoute, imageRoutes, type GuestImageResolver } from './routes'
import { createImageService } from './service'

export type { ExampleImages } from './example-images'
export type { GuestImageResolver } from './routes'
export type { ImageStorage } from './service'
export type { ImageUpload, UploadedImage } from './upload'
export { exampleImageUpload, FILE_PART_NAME, readUploadedImage, uploadRejection } from './upload'

/**
 * The image module. Creating it checks the three roots (fails when one is a file, not writable,
 * or overlaps another). It answers the public and private delivery routes, the guest route to be
 * installed once the cart's resolver exists, the print-image storage, the storage of a public
 * folder, and the example-image rule built on it.
 */
export function createImageModule({
  settings,
  guestTokens,
}: {
  settings: Settings['image']
  guestTokens: Auth['guestTokens']
}) {
  const service = createImageService(prepareImageRoots(settings))
  return {
    routes: imageRoutes(service),
    /** `GET /api/images/guest/{size}/{id}`, answered through `resolver`. */
    guestImageRoute: (resolver: GuestImageResolver) =>
      guestImageRoute(service, guestTokens, resolver),
    publicStorage: service.publicStorage,
    privateStorage: service.privateStorage,
    /** The example-image rule for one public folder, logging under the owning module's `logger`. */
    exampleImages: (folder: string, logger: Logger) =>
      createExampleImages(service.publicStorage(folder), folder, logger),
  }
}

export type ImageModule = ReturnType<typeof createImageModule>
