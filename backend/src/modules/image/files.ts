import { accessSync, constants, mkdirSync, realpathSync, statSync } from 'node:fs'
import { lstat, mkdir, readdir, realpath, rm, stat } from 'node:fs/promises'
import { dirname, join, resolve, sep } from 'node:path'
import type { Settings } from '@/platform/settings'

/**
 * The filesystem rules of the module: the three roots checked once at startup, and the path checks
 * every later read and write goes through, so no request can reach a file outside its root.
 */

export interface ImageRoots {
  public: string
  private: string
  cache: string
}

/**
 * Resolves the configured roots against the working directory, creates missing ones, and answers
 * their real paths. Fails when a root is a file, is not writable, or overlaps another root, so every
 * later path can be compared against a stable boundary.
 */
export function prepareImageRoots(settings: Settings['image']): ImageRoots {
  const roots = [settings.publicRoot, settings.privateRoot, settings.cacheRoot].map(prepareRoot)
  roots.forEach((root, index) => {
    for (const other of roots.slice(index + 1)) {
      if (isInside(root, other) || isInside(other, root)) {
        throw new Error(`Image roots must not overlap: ${root} and ${other}`)
      }
    }
  })
  const [publicRoot = '', privateRoot = '', cacheRoot = ''] = roots
  return { public: publicRoot, private: privateRoot, cache: cacheRoot }
}

function prepareRoot(configured: string): string {
  const absolute = resolve(configured)
  const existing = statSync(absolute, { throwIfNoEntry: false })
  if (existing !== undefined && !existing.isDirectory()) {
    throw new Error(`Image root is not a directory: ${absolute}`)
  }
  mkdirSync(absolute, { recursive: true })
  const real = realpathSync(absolute)
  try {
    accessSync(real, constants.W_OK)
  } catch {
    throw new Error(`Image root is not writable: ${real}`)
  }
  return real
}

const isInside = (path: string, root: string) => path === root || path.startsWith(root + sep)

/**
 * `value` when it is a relative path that stays where it was written: not blank, no backslash, no
 * NUL, and no empty, blank, `.`, or `..` segment (a leading `/` is an empty first segment). This
 * only refuses names that are unsafe under any root; containment against the real root is still
 * checked when the path is resolved.
 */
export function safeRelativePath(value: string): string | null {
  if (value.trim() === '' || value.includes('\\') || value.includes('\0')) return null
  const unsafe = value.split('/').some((s) => s.trim() === '' || s === '.' || s === '..')
  return unsafe ? null : value
}

/** A plain file name, what a storage caller passes: never a path. */
export const isPlainFilename = (value: string) =>
  safeRelativePath(value) !== null && !value.includes('/')

/**
 * The regular file at `relative` below `root`. `invalid` when its real path (after symbolic links)
 * leaves the root; `missing` when nothing is there or it is not a regular file.
 */
export async function resolveFile(
  root: string,
  relative: string,
): Promise<{ path: string } | 'missing' | 'invalid'> {
  const target = join(root, relative)
  if ((await lstat(target).catch(() => null)) === null) return 'missing'
  const real = await realpath(target).catch(() => null)
  if (real === null || !isInside(real, root)) return 'invalid'
  return (await stat(real)).isFile() ? { path: real } : 'missing'
}

/**
 * Creates `directory` (which lies below `root`) and answers its real path, or `null` when an
 * existing ancestor or the directory itself is a symbolic link out of the root. The ancestor is
 * checked first, so nothing is ever created outside the root.
 */
export async function directoryInside(root: string, directory: string): Promise<string | null> {
  let existing = directory
  while ((await lstat(existing).catch(() => null)) === null) existing = dirname(existing)
  const realAncestor = await realpath(existing).catch(() => null)
  if (realAncestor === null || !isInside(realAncestor, root)) return null
  await mkdir(directory, { recursive: true })
  const real = await realpath(directory)
  return isInside(real, root) && (await stat(real)).isDirectory() ? real : null
}

/**
 * Whether the cached file at `path` may be served for `original`: a regular file (not a link)
 * inside the cache root, at least as new as the original. Millisecond precision, because a derived
 * file carries its original's modification time at that precision.
 */
export async function isFresh(cacheRoot: string, path: string, original: string): Promise<boolean> {
  const cached = await lstat(path).catch(() => null)
  if (cached === null || !cached.isFile()) return false
  const real = await realpath(path).catch(() => null)
  if (real === null || !isInside(real, cacheRoot)) return false
  return Math.floor(cached.mtimeMs) >= Math.floor((await stat(original)).mtimeMs)
}

/**
 * Deletes every size derivation of `relative` below `visibilityRoot` (`cache/<visibility>`). Size
 * directories that are symbolic links, and derivations whose real path leaves their size
 * directory, are left alone.
 */
export async function deleteDerivations(visibilityRoot: string, relative: string): Promise<void> {
  if ((await lstat(visibilityRoot).catch(() => null))?.isDirectory() !== true) return
  for (const size of await readdir(visibilityRoot, { withFileTypes: true })) {
    if (!size.isDirectory()) continue
    const cached = await resolveFile(join(visibilityRoot, size.name), relative)
    if (typeof cached === 'object') await rm(cached.path, { force: true })
  }
}
