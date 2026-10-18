import type { Logger } from '@/platform/logging'
import { invalid, ok, type OperationResult } from '@/platform/operation'
import type { ImageStorage } from './service'

/** The shape of every name the storage mints: a lowercase UUID with dashes and `.webp`. */
export const STORED_IMAGE_FILENAME =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.webp$/

/**
 * The rule every module follows for a public image a row refers to by name: store it before the
 * row exists, check every submitted name against the storage before the write, and delete a name
 * nothing refers to any more after the write committed. `logger` is the owning module's, so a
 * failed cleanup is reported under that module's name.
 */
export function createExampleImages(storage: ImageStorage, folder: string, logger: Logger) {
  return {
    /** Stores an upload and answers the name a following write submits. */
    store: storage.store,

    /**
     * Whether `filename` names a file of this folder, reported on `field`; `null` means "no
     * example image" and is valid. A name without the minted shape never reaches the storage.
     */
    checkSubmitted: async (
      field: string,
      filename: string | null,
    ): Promise<OperationResult<null>> => {
      if (filename === null) return ok(null)
      if (!STORED_IMAGE_FILENAME.test(filename)) {
        return invalid({
          [field]: ['Example image filename must be the name of an uploaded image'],
        })
      }
      return (await storage.exists(filename))
        ? ok(null)
        : invalid({ [field]: ['Example image does not exist'] })
    },

    /**
     * Removes a file no row referred to when the write committed. A failure is only logged: a row
     * written after that commit can refer to the file again, and an orphan is not the client's
     * problem.
     */
    deleteObsolete: async (filename: string | null): Promise<void> => {
      if (filename === null) return
      try {
        await storage.delete(filename)
      } catch (error) {
        logger.warn({ err: error, filename, folder }, 'Could not delete example image')
      }
    },
  }
}

export type ExampleImages = ReturnType<typeof createExampleImages>
