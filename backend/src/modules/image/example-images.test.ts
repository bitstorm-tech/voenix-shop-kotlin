import { describe, expect, spyOn, test } from 'bun:test'
import { createLogger } from '@/platform/logging'
import { createExampleImages } from './example-images'
import type { ImageStorage } from './service'

const STORED = '0b5e7f6a-3c1d-4e2f-9a8b-7c6d5e4f3a2b.webp'

const logger = createLogger('example-images-test')
const exampleImages = (storage: Partial<ImageStorage>) =>
  createExampleImages(
    {
      store: () => Promise.reject(new Error('unused')),
      exists: (filename) => Promise.resolve(filename === STORED),
      delete: () => Promise.resolve(),
      originalPaths: () => Promise.resolve(new Map()),
      ...storage,
    },
    'examples',
    logger,
  )

describe('checkSubmitted', () => {
  test('accepts no name and a stored name, and reports a malformed or missing one on the field', async () => {
    const images = exampleImages({})
    expect(await images.checkSubmitted('exampleImageFilename', null)).toEqual({
      kind: 'ok',
      value: null,
    })
    expect(await images.checkSubmitted('exampleImageFilename', STORED)).toEqual({
      kind: 'ok',
      value: null,
    })
    expect(await images.checkSubmitted('mugVariants[2].exampleImageFilename', '../x.webp')).toEqual(
      {
        kind: 'invalid',
        errors: {
          'mugVariants[2].exampleImageFilename': [
            'Example image filename must be the name of an uploaded image',
          ],
        },
      },
    )
    expect(
      await images.checkSubmitted('exampleImageFilename', STORED.replace('0b5e', '1b5e')),
    ).toEqual({
      kind: 'invalid',
      errors: { exampleImageFilename: ['Example image does not exist'] },
    })
  })
})

describe('deleteObsolete', () => {
  test('deletes the name, does nothing for none, and only logs a failing delete', async () => {
    const deleted: string[] = []
    await exampleImages({
      delete: (filename) => {
        deleted.push(filename)
        return Promise.resolve()
      },
    }).deleteObsolete(STORED)
    expect(deleted).toEqual([STORED])
    const warn = spyOn(logger, 'warn').mockImplementation(() => undefined)
    const failing = exampleImages({ delete: () => Promise.reject(new Error('disk gone')) })
    await failing.deleteObsolete(null)
    expect(warn).not.toHaveBeenCalled()
    await failing.deleteObsolete(STORED)
    expect(warn).toHaveBeenCalledTimes(1)
  })
})
