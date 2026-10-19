import { lstat, readdir } from 'node:fs/promises'
import { join } from 'node:path'
import { formatOfFilename } from './codec'
import { isPlainFilename, resolveFile, safeRelativePath, type ImageRoots } from './files'
import { PRINT_IMAGE_FOLDER, type ImageVisibility } from './service'

/** How old an unreferenced original must be before it counts as an orphan. */
export const ORPHAN_MINIMUM_AGE_MS = 7 * 24 * 60 * 60 * 1000

/**
 * The public originals one owning module refers to, as paths below the public root
 * (`<folder>/<filename>`). Article and Prompt implement it; the composition root hands them in.
 */
export type PublicImageReferences = () => Promise<Iterable<string>>

/**
 * The cart's registry of print images. `inUse` answers the file names a cart or order line still
 * points at; `release` removes the registration of `filename` before its file is deleted and
 * answers `false` when a line points at it after all (no registration at all is `true`).
 */
export interface PrintImageRegistry {
  inUse: () => Promise<Iterable<string>>
  release: (filename: string) => Promise<boolean>
}

/** One original below a root: `path` is relative to the root of `visibility`. */
export interface ImageLocation {
  visibility: ImageVisibility
  path: string
}

export interface OrphanImage extends ImageLocation {
  size: number
  modifiedAt: Date
}

const keyOf = ({ visibility, path }: ImageLocation) => `${visibility}/${path}`

/**
 * Finds and deletes orphans: originals that no owning module refers to and that are older than
 * `ORPHAN_MINIMUM_AGE_MS`. The age keeps a pre-upload whose form is still open, a print image whose
 * editor is still open, and a running SPOD sync out of the list. The whole public root is scanned
 * (a file in a folder no module owns is an orphan too), of the private root only the print images.
 * Temporary files (a leading dot), symbolic links, and names without an image extension are never
 * listed.
 */
export function createOrphanImages({
  roots,
  remove,
  publicReferences,
  printImages,
  now = () => new Date(),
}: {
  roots: ImageRoots
  remove: (location: ImageLocation) => Promise<void>
  publicReferences: PublicImageReferences[]
  printImages: PrintImageRegistry
  now?: () => Date
}) {
  const referenced = async (): Promise<Set<string>> => {
    const [publicPaths, printImageNames] = await Promise.all([
      Promise.all(publicReferences.map((references) => references())),
      printImages.inUse(),
    ])
    const keys = new Set<string>()
    for (const paths of publicPaths) {
      for (const path of paths) keys.add(keyOf({ visibility: 'public', path }))
    }
    for (const filename of printImageNames) {
      keys.add(keyOf({ visibility: 'private', path: `${PRINT_IMAGE_FOLDER}/${filename}` }))
    }
    return keys
  }

  const isOldEnough = (modifiedAt: Date) =>
    now().getTime() - modifiedAt.getTime() >= ORPHAN_MINIMUM_AGE_MS

  /**
   * The candidate at `location`, or `null` when it names no listable original. A path that runs
   * through a symbolic link anywhere names nothing: its real file could be one a module refers to
   * under another path.
   */
  const candidate = async (location: ImageLocation): Promise<OrphanImage | null> => {
    if (!isListable(location)) return null
    const target = join(roots[location.visibility], location.path)
    const original = await resolveFile(roots[location.visibility], location.path)
    if (typeof original !== 'object' || original.path !== target) return null
    const stats = await lstat(target).catch(() => null)
    if (stats === null || !stats.isFile()) return null
    return { ...location, size: stats.size, modifiedAt: stats.mtime }
  }

  return {
    /** Every orphan, oldest first. */
    list: async (): Promise<OrphanImage[]> => {
      const inUse = await referenced()
      const found = [
        ...(await scan(roots.public, '', true)).map((path) => ({
          visibility: 'public' as const,
          path,
        })),
        ...(await scan(join(roots.private, PRINT_IMAGE_FOLDER), PRINT_IMAGE_FOLDER, false)).map(
          (path) => ({ visibility: 'private' as const, path }),
        ),
      ]
      const orphans: OrphanImage[] = []
      for (const location of found) {
        if (inUse.has(keyOf(location))) continue
        const image = await candidate(location)
        if (image !== null && isOldEnough(image.modifiedAt)) orphans.push(image)
      }
      return orphans.sort((a, b) => a.modifiedAt.getTime() - b.modifiedAt.getTime())
    },

    /**
     * Deletes each of `locations` that is an orphan right now, with its derivations, and a print
     * image's registration first. Everything else (in use, too young, gone, not listable) is kept
     * untouched; the answer says which was which.
     */
    delete: async (
      locations: ImageLocation[],
    ): Promise<{ deleted: ImageLocation[]; kept: ImageLocation[] }> => {
      const inUse = await referenced()
      const deleted: ImageLocation[] = []
      const kept: ImageLocation[] = []
      for (const location of locations) {
        const image = inUse.has(keyOf(location)) ? null : await candidate(location)
        const released =
          image !== null &&
          isOldEnough(image.modifiedAt) &&
          (location.visibility === 'public' ||
            (await printImages.release(location.path.slice(PRINT_IMAGE_FOLDER.length + 1))))
        if (released) {
          await remove(location)
          deleted.push(location)
        } else {
          kept.push(location)
        }
      }
      return { deleted, kept }
    },
  }
}

export type OrphanImages = ReturnType<typeof createOrphanImages>

/**
 * Whether `location` names an original the orphan list may contain: a safe relative path with an
 * image extension and no temporary (dot) segment; below the private root only a print image.
 */
function isListable({ visibility, path }: ImageLocation): boolean {
  if (safeRelativePath(path) === null || formatOfFilename(path) === null) return false
  if (path.split('/').some((segment) => segment.startsWith('.'))) return false
  if (visibility === 'public') return true
  const [folder, filename, ...rest] = path.split('/')
  return folder === PRINT_IMAGE_FOLDER && filename !== undefined && rest.length === 0
    ? isPlainFilename(filename)
    : false
}

/**
 * The paths (prefixed with `prefix`) of the regular files below `directory`, descending into
 * subdirectories when `recursive`. Symbolic links are neither followed nor listed.
 */
async function scan(directory: string, prefix: string, recursive: boolean): Promise<string[]> {
  const entries = await readdir(directory, { withFileTypes: true }).catch(() => [])
  const paths: string[] = []
  for (const entry of entries) {
    const path = prefix === '' ? entry.name : `${prefix}/${entry.name}`
    if (entry.isFile()) paths.push(path)
    else if (recursive && entry.isDirectory()) {
      paths.push(...(await scan(join(directory, entry.name), path, true)))
    }
  }
  return paths
}
