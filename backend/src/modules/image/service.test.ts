import { afterAll, beforeAll, describe, expect, spyOn, test } from 'bun:test'
import { mkdtemp, readdir, rm, stat, utimes, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import sharp from 'sharp'
import * as codec from './codec'
import { STORED_IMAGE_FILENAME } from './example-images'
import { prepareImageRoots, type ImageRoots } from './files'
import { createImageService, type ImageService } from './service'

let directory: string
let roots: ImageRoots
let service: ImageService

const image = (format: 'png' | 'jpeg' | 'webp' | 'gif', width = 60, height = 40) =>
  sharp({ create: { width, height, channels: 3, background: '#336699' } })
    .toFormat(format)
    .toBuffer()

beforeAll(async () => {
  directory = await mkdtemp(join(tmpdir(), 'image-service-'))
  roots = prepareImageRoots({
    publicRoot: join(directory, 'public'),
    privateRoot: join(directory, 'private'),
    cacheRoot: join(directory, 'cache'),
  })
  service = createImageService(roots)
})

afterAll(async () => {
  await rm(directory, { recursive: true, force: true })
})

/** A 60×40 JPEG whose EXIF orientation 6 says "rotate 90° clockwise to view": upright it is 40×60. */
const sidewaysJpeg = () =>
  sharp({ create: { width: 60, height: 40, channels: 3, background: '#336699' } })
    .jpeg()
    .withMetadata({ orientation: 6 })
    .toBuffer()

const derivedOf = async (size: string, filename: string) => {
  const result = await service.get('public', size, filename)
  if (result.kind !== 'ok') throw new Error(`Expected an image, got ${result.kind}`)
  return result.value
}

describe('derivation', () => {
  test('fits the image into the size, upscaling a smaller original', async () => {
    await writeFile(join(roots.public, 'fit.png'), await image('png', 300, 200))
    expect(await sharp((await derivedOf('150', 'fit.png')).path).metadata()).toMatchObject({
      width: 150,
      height: 100,
    })
    expect(await sharp((await derivedOf('100x100', 'fit.png')).path).metadata()).toMatchObject({
      width: 100,
      height: 67,
    })
    expect(await sharp((await derivedOf('600', 'fit.png')).path).metadata()).toMatchObject({
      width: 600,
    })
  })

  test('turns an original upright by its EXIF orientation', async () => {
    await writeFile(join(roots.public, 'sideways.jpg'), await sidewaysJpeg())
    expect(await sharp((await derivedOf('30', 'sideways.jpg')).path).metadata()).toMatchObject({
      width: 30,
      height: 45,
    })
  })

  test('runs once for concurrent requests of one key and leaves no temporary file', async () => {
    await writeFile(join(roots.public, 'busy.png'), await image('png', 800, 600))
    const derivations = spyOn(codec, 'writeDerivative')
    try {
      const results = await Promise.all(
        Array.from({ length: 8 }, () => derivedOf('200', 'busy.png')),
      )
      expect(derivations).toHaveBeenCalledTimes(1)
      expect(new Set(results.map((result) => result.lastModified.getTime())).size).toBe(1)
    } finally {
      derivations.mockRestore()
    }
    expect(await readdir(join(roots.cache, 'public', '200'))).toEqual(['busy.png'])
  })

  test('serves the cache until the original is replaced', async () => {
    const original = join(roots.public, 'replaced.png')
    await writeFile(original, await image('png', 100, 100))
    const first = await derivedOf('50', 'replaced.png')
    expect((await derivedOf('50', 'replaced.png')).lastModified).toEqual(first.lastModified)

    await writeFile(original, await image('png', 100, 50))
    const later = new Date(first.lastModified.getTime() + 5_000)
    await utimes(original, later, later)
    const second = await derivedOf('50', 'replaced.png')
    expect(second.lastModified).toEqual(later)
    expect(await sharp(second.path).metadata()).toMatchObject({ width: 50, height: 25 })
  })

  test('a missing original is not found even when a cached file remains', async () => {
    const original = join(roots.public, 'gone.png')
    await writeFile(original, await image('png'))
    await derivedOf('20', 'gone.png')
    await rm(original)
    expect(await service.get('public', '20', 'gone.png')).toEqual({ kind: 'notFound' })
  })
})

describe('storage', () => {
  const storage = () => service.privateStorage

  test('normalizes JPEG, PNG, and WebP to WebP under a generated name', async () => {
    for (const format of ['jpeg', 'png', 'webp'] as const) {
      const stored = await storage().store({
        bytes: await image(format),
        contentType: `image/${format}`,
      })
      if (stored.kind !== 'ok') throw new Error(`The ${format} upload was rejected`)
      expect(stored.value.filename).toMatch(STORED_IMAGE_FILENAME)
      const [path] = (await storage().originalPaths([stored.value.filename])).values()
      expect(await sharp(path).metadata()).toMatchObject({ format: 'webp', width: 60, height: 40 })
    }
  })

  test('stores an upload upright by its EXIF orientation', async () => {
    const stored = await storage().store({ bytes: await sidewaysJpeg(), contentType: 'image/jpeg' })
    if (stored.kind !== 'ok') throw new Error('The sideways upload was rejected')
    const [path] = (await storage().originalPaths([stored.value.filename])).values()
    expect(await sharp(path).metadata()).toMatchObject({ width: 40, height: 60 })
  })

  test('rejects non-image, disguised, empty, and oversized uploads on the file field', async () => {
    const rejection = async (bytes: Uint8Array, contentType: string) => {
      const result = await storage().store({ bytes, contentType })
      return result.kind === 'invalid' ? result.errors : result
    }
    expect(await rejection(await image('gif'), 'image/gif')).toEqual({
      file: ['Only JPEG, PNG, and WebP uploads are supported'],
    })
    expect(await rejection(await image('gif'), 'image/png')).toEqual({
      file: ['Invalid image data'],
    })
    expect(await rejection(new TextEncoder().encode('not an image'), 'image/png')).toEqual({
      file: ['Unsupported or invalid image data'],
    })
    expect(await rejection(await image('png'), 'image/jpeg')).toEqual({
      file: ['Declared and decoded image formats differ'],
    })
    expect(await rejection(new Uint8Array(), 'image/png')).toEqual({
      file: ['Image must not be empty'],
    })
    expect(await rejection(new Uint8Array(codec.MAX_IMAGE_BYTES + 1), 'image/png')).toEqual({
      file: ['Image must not exceed 10 MiB'],
    })
  })

  test('refuses more than 40 megapixels before decoding', async () => {
    const header = await sharp({
      create: { width: 8_001, height: 5_000, channels: 3, background: '#000000' },
    })
      .png({ compressionLevel: 0 })
      .toBuffer()
    const result = await storage().store({
      bytes: header.subarray(0, 64),
      contentType: 'image/png',
    })
    expect(result).toEqual({
      kind: 'invalid',
      errors: { file: ['Decoded image exceeds 40 megapixels'] },
    })
  })

  test('deletes the original and its derivations; exists and originalPaths follow', async () => {
    const stored = await storage().store({ bytes: await image('png'), contentType: 'image/png' })
    if (stored.kind !== 'ok') throw new Error('The upload was rejected')
    const { filename } = stored.value
    expect(await storage().exists(filename)).toBe(true)
    expect((await service.get('private', '30', `print-images/${filename}`)).kind).toBe('ok')

    await storage().delete(filename)
    await storage().delete(filename)
    expect(await storage().exists(filename)).toBe(false)
    expect(await readdir(join(roots.cache, 'private', '30', 'print-images'))).toEqual([])
    expect(await storage().originalPaths([filename])).toEqual(new Map())
  })

  test('originalPaths omits unknown names and names that are not plain file names', async () => {
    const stored = await storage().store({ bytes: await image('png'), contentType: 'image/png' })
    if (stored.kind !== 'ok') throw new Error('The upload was rejected')
    const { filename } = stored.value
    const paths = await storage().originalPaths([
      filename,
      'unknown.webp',
      `../print-images/${filename}`,
    ])
    expect([...paths.keys()]).toEqual([filename])
    expect((await stat(paths.get(filename) ?? '')).isFile()).toBe(true)
    expect(await storage().exists('../secret.webp')).toBe(false)
  })

  test('a public folder rejects an unsafe path', () => {
    expect(() => service.publicStorage('../outside')).toThrow(
      'Public image folder must be a safe relative path: ../outside',
    )
  })
})

describe('roots', () => {
  test('refuse a file and overlapping roots', async () => {
    const file = join(directory, 'a-file')
    await writeFile(file, '')
    const settings = {
      publicRoot: file,
      privateRoot: join(directory, 'p'),
      cacheRoot: join(directory, 'c'),
    }
    expect(() => prepareImageRoots(settings)).toThrow(`Image root is not a directory: ${file}`)
    expect(() =>
      prepareImageRoots({ ...settings, publicRoot: join(directory, 'c', 'public') }),
    ).toThrow('Image roots must not overlap')
  })
})
