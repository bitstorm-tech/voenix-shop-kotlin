import { afterEach, beforeEach, describe, expect, test } from 'bun:test'
import { mkdir, mkdtemp, rm, stat, symlink, utimes, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { prepareImageRoots, type ImageRoots } from './files'
import { createOrphanImages, ORPHAN_MINIMUM_AGE_MS, type PrintImageRegistry } from './orphans'
import { createImageService } from './service'

const NOW = new Date('2026-10-19T12:00:00Z')
const OLD = new Date(NOW.getTime() - ORPHAN_MINIMUM_AGE_MS - 1000)
const YOUNG = new Date(NOW.getTime() - ORPHAN_MINIMUM_AGE_MS + 60_000)

let directory: string
let roots: ImageRoots
let publicInUse: string[]
let printImagesInUse: string[]
let released: string[]
let refusedRelease: Set<string>

/** Writes a file at `path` below `root` and gives it the modification time `at`. */
const place = async (root: string, path: string, at = OLD) => {
  const target = join(root, path)
  await mkdir(dirname(target), { recursive: true })
  await writeFile(target, 'bytes')
  await utimes(target, at, at)
}

const exists = (path: string) =>
  stat(path).then(
    () => true,
    () => false,
  )

const orphans = () => {
  const printImages: PrintImageRegistry = {
    inUse: () => Promise.resolve(printImagesInUse),
    release: (filename) => {
      released.push(filename)
      return Promise.resolve(!refusedRelease.has(filename))
    },
  }
  return createOrphanImages({
    roots,
    remove: createImageService(roots).remove,
    publicReferences: [() => Promise.resolve(publicInUse)],
    printImages,
    now: () => NOW,
  })
}

beforeEach(async () => {
  directory = await mkdtemp(join(tmpdir(), 'image-orphans-'))
  roots = prepareImageRoots({
    publicRoot: join(directory, 'public'),
    privateRoot: join(directory, 'private'),
    cacheRoot: join(directory, 'cache'),
  })
  publicInUse = []
  printImagesInUse = []
  released = []
  refusedRelease = new Set()
})

afterEach(async () => {
  await rm(directory, { recursive: true, force: true })
})

describe('list', () => {
  test('lists old unreferenced originals of both roots, oldest first', async () => {
    const older = new Date(OLD.getTime() - 1000)
    await place(roots.public, 'prompt-example-images/used.webp')
    await place(roots.public, 'prompt-example-images/orphan.webp')
    await place(roots.public, 'unknown-folder/deep/orphan.png', older)
    await place(roots.private, 'print-images/used.webp')
    await place(roots.private, 'print-images/orphan.webp')
    publicInUse = ['prompt-example-images/used.webp']
    printImagesInUse = ['used.webp']

    const listed = await orphans().list()

    expect(listed.map(({ visibility, path }) => `${visibility}/${path}`)).toEqual([
      'public/unknown-folder/deep/orphan.png',
      'public/prompt-example-images/orphan.webp',
      'private/print-images/orphan.webp',
    ])
    expect(listed[0]).toMatchObject({ size: 5, modifiedAt: older })
  })

  test('leaves out young files, temporary files, other files, links, and other private folders', async () => {
    await place(roots.public, 'young.webp', YOUNG)
    await place(roots.public, 'folder/.orphan.webp.1234.tmp')
    await place(roots.public, 'notes.txt')
    await place(roots.public, 'target.webp')
    await symlink(join(roots.public, 'target.webp'), join(roots.public, 'link.webp'))
    await place(roots.private, 'other/orphan.webp')
    publicInUse = ['target.webp']

    expect(await orphans().list()).toEqual([])
  })
})

describe('delete', () => {
  test('deletes an orphan with its derivations and keeps everything else', async () => {
    await place(roots.public, 'folder/orphan.webp')
    await place(roots.cache, 'public/300/folder/orphan.webp')
    await place(roots.public, 'folder/used.webp')
    await place(roots.public, 'folder/young.webp', YOUNG)
    publicInUse = ['folder/used.webp']

    const answer = await orphans().delete([
      { visibility: 'public', path: 'folder/orphan.webp' },
      { visibility: 'public', path: 'folder/used.webp' },
      { visibility: 'public', path: 'folder/young.webp' },
      { visibility: 'public', path: 'folder/missing.webp' },
      { visibility: 'public', path: '../private/print-images/x.webp' },
    ])

    expect(answer.deleted).toEqual([{ visibility: 'public', path: 'folder/orphan.webp' }])
    expect(answer.kept).toHaveLength(4)
    expect(await exists(join(roots.public, 'folder/orphan.webp'))).toBe(false)
    expect(await exists(join(roots.cache, 'public/300/folder/orphan.webp'))).toBe(false)
    expect(await exists(join(roots.public, 'folder/used.webp'))).toBe(true)
    expect(await exists(join(roots.public, 'folder/young.webp'))).toBe(true)
  })

  test('releases a print image before deleting it and keeps it when the release is refused', async () => {
    await place(roots.private, 'print-images/free.webp')
    await place(roots.private, 'print-images/raced.webp')
    await place(roots.private, 'print-images/used.webp')
    printImagesInUse = ['used.webp']
    refusedRelease.add('raced.webp')

    const answer = await orphans().delete([
      { visibility: 'private', path: 'print-images/free.webp' },
      { visibility: 'private', path: 'print-images/raced.webp' },
      { visibility: 'private', path: 'print-images/used.webp' },
    ])

    expect(answer.deleted).toEqual([{ visibility: 'private', path: 'print-images/free.webp' }])
    expect(released).toEqual(['free.webp', 'raced.webp'])
    expect(await exists(join(roots.private, 'print-images/free.webp'))).toBe(false)
    expect(await exists(join(roots.private, 'print-images/raced.webp'))).toBe(true)
  })

  test('never deletes through a symbolic link', async () => {
    await place(roots.public, 'used.webp')
    await symlink(join(roots.public, 'used.webp'), join(roots.public, 'link.webp'))
    publicInUse = ['used.webp']

    const answer = await orphans().delete([{ visibility: 'public', path: 'link.webp' }])

    expect(answer.deleted).toEqual([])
    expect(await exists(join(roots.public, 'used.webp'))).toBe(true)
  })
})
