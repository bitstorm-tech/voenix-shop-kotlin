import { randomUUID } from 'node:crypto'
import { rename, rm, stat, utimes, writeFile } from 'node:fs/promises'
import { basename, dirname, join } from 'node:path'
import { invalid, notFound, ok, type OperationResult } from '@/platform/operation'
import {
  CONTENT_TYPES,
  formatOfFilename,
  normalizeToWebp,
  parseImageSize,
  writeDerivative,
  type ImageFormat,
  type ImageSize,
} from './codec'
import {
  deleteDerivations,
  directoryInside,
  isFresh,
  isPlainFilename,
  resolveFile,
  safeRelativePath,
  type ImageRoots,
} from './files'
import { FILE_PART_NAME, type ImageUpload } from './upload'

export type ImageVisibility = 'public' | 'private'

/** The one directory below the private root that holds print-image originals. */
export const PRINT_IMAGE_FOLDER = 'print-images'

/** A file ready to be sent: a derived image in the cache. */
export interface ImageResource {
  path: string
  contentType: string
  size: number
  lastModified: Date
}

/**
 * Stores, checks, deletes, and resolves the originals of one folder. Every accepted upload is
 * normalized to WebP under a generated name (a lowercase UUID with dashes plus `.webp`). A name
 * that is not a plain file name names nothing: `exists` answers `false`, `delete` does nothing,
 * and `originalPaths` leaves it out.
 */
export interface ImageStorage {
  store: (upload: ImageUpload) => Promise<OperationResult<{ filename: string }>>
  exists: (filename: string) => Promise<boolean>
  /** Removes the original and every derivation of it; deleting a missing file is not an error. */
  delete: (filename: string) => Promise<void>
  /**
   * The readable original of each stored name, for a consumer that has to read the bytes (the
   * production PDF). A name the storage cannot answer for is absent from the map. The paths are a
   * snapshot: a reader still has to survive a file deleted right after the call.
   */
  originalPaths: (filenames: Iterable<string>) => Promise<Map<string, string>>
}

const INVALID_FILENAME = 'Invalid image filename'
const invalidField = (field: string, message: string) => invalid({ [field]: [message] })

export function createImageService(roots: ImageRoots) {
  /** The derivation running for a cache path: concurrent misses for one key share it. */
  const derivations = new Map<string, Promise<void>>()

  const derive = (cachePath: string, original: string, size: ImageSize, format: ImageFormat) => {
    let running = derivations.get(cachePath)
    if (running === undefined) {
      running = publishDerivative(cachePath, original, size, format).finally(() => {
        derivations.delete(cachePath)
      })
      derivations.set(cachePath, running)
    }
    return running
  }

  /** The derived file for `size` of the original at `filename`, created on a cache miss. */
  const get = async (
    visibility: ImageVisibility,
    requestedSize: string,
    filename: string,
  ): Promise<OperationResult<ImageResource>> => {
    const size = parseImageSize(requestedSize)
    if (size === null) {
      return invalidField('size', 'Size must be width or widthxheight from 1 through 4096')
    }
    const relative = safeRelativePath(filename)
    if (relative === null) return invalidField('filename', INVALID_FILENAME)
    const format = formatOfFilename(relative)
    if (format === null) return invalidField('filename', 'Unsupported image format')
    const original = await resolveFile(roots[visibility], relative)
    if (original === 'invalid') return invalidField('filename', INVALID_FILENAME)
    if (original === 'missing') return notFound

    const cachePath = join(roots.cache, visibility, size.key, relative)
    if (!(await isFresh(roots.cache, cachePath, original.path))) {
      const directory = await directoryInside(roots.cache, dirname(cachePath))
      if (directory === null) return invalidField('filename', INVALID_FILENAME)
      await derive(join(directory, basename(cachePath)), original.path, size, format)
    }
    const derived = await stat(cachePath)
    return ok({
      path: cachePath,
      contentType: CONTENT_TYPES[format],
      size: derived.size,
      lastModified: derived.mtime,
    })
  }

  /**
   * Removes the original at `path` below the root of `visibility` and every derivation of it; a
   * missing original is not an error. `path` must already be a safe relative path.
   */
  const remove = async ({ visibility, path }: { visibility: ImageVisibility; path: string }) => {
    const original = await resolveFile(roots[visibility], path)
    if (typeof original === 'object') await rm(original.path, { force: true })
    await deleteDerivations(join(roots.cache, visibility), path)
  }

  const storage = (visibility: ImageVisibility, folder: string): ImageStorage => {
    const root = roots[visibility]
    const originalOf = async (filename: string) =>
      isPlainFilename(filename) ? resolveFile(root, join(folder, filename)) : 'invalid'
    return {
      store: async (upload) => {
        const normalized = await normalizeToWebp(upload.bytes, upload.contentType)
        if ('rejection' in normalized) return invalidField(FILE_PART_NAME, normalized.rejection)
        const directory = await directoryInside(root, join(root, folder))
        if (directory === null) throw new Error(`Image folder ${folder} leaves its root`)
        const filename = `${randomUUID()}.webp`
        await publish(join(directory, filename), (temporary) =>
          writeFile(temporary, normalized.webp),
        )
        return ok({ filename })
      },
      exists: async (filename) => typeof (await originalOf(filename)) === 'object',
      delete: async (filename) => {
        if (isPlainFilename(filename)) await remove({ visibility, path: join(folder, filename) })
      },
      originalPaths: async (filenames) => {
        const paths = new Map<string, string>()
        for (const filename of new Set(filenames)) {
          const original = await originalOf(filename)
          if (typeof original === 'object') paths.set(filename, original.path)
        }
        return paths
      },
    }
  }

  return {
    get,
    remove,
    /** The storage of one folder below the public root; `folder` is a safe relative path. */
    publicStorage: (folder: string): ImageStorage => {
      if (safeRelativePath(folder) === null) {
        throw new Error(`Public image folder must be a safe relative path: ${folder}`)
      }
      return storage('public', folder)
    },
    /** The print images below the private root. Callers pass file names, never the folder. */
    privateStorage: storage('private', PRINT_IMAGE_FOLDER),
  }
}

export type ImageService = ReturnType<typeof createImageService>

/**
 * Derives `original` into `cachePath`. The derived file takes the original's modification time, so
 * an original replaced while it was being derived is newer than the published file, and the next
 * request derives it again instead of serving stale pixels as fresh.
 */
async function publishDerivative(
  cachePath: string,
  original: string,
  size: ImageSize,
  format: ImageFormat,
): Promise<void> {
  const source = await stat(original)
  await publish(cachePath, async (temporary) => {
    await writeDerivative(original, size, format, temporary)
    await utimes(temporary, source.atime, source.mtime)
  })
}

/**
 * Writes `target` through a unique temporary file in its directory and an atomic rename, so no
 * reader ever sees a partly written file. The temporary file is removed when anything fails.
 */
async function publish(target: string, write: (temporary: string) => Promise<void>) {
  const temporary = join(dirname(target), `.${basename(target)}.${randomUUID()}.tmp`)
  try {
    await write(temporary)
    await rename(temporary, target)
  } finally {
    await rm(temporary, { force: true })
  }
}
