import { beforeAll, describe, expect, test } from 'bun:test'
import sharp from 'sharp'
import { MAX_IMAGE_BYTES, readGenerationUpload } from './upload'

let validPng: Uint8Array<ArrayBuffer>
let validJpeg: Uint8Array<ArrayBuffer>
let validWebp: Uint8Array<ArrayBuffer>
let validGif: Uint8Array<ArrayBuffer>

const toBytes = (buffer: Buffer): Uint8Array<ArrayBuffer> => new Uint8Array(buffer)

beforeAll(async () => {
  const base = () => sharp({ create: { width: 8, height: 8, channels: 3, background: '#336699' } })
  validPng = toBytes(await base().png().toBuffer())
  validJpeg = toBytes(await base().jpeg().toBuffer())
  validWebp = toBytes(await base().webp().toBuffer())
  validGif = toBytes(await base().gif().toBuffer())
})

const rawBlob = (size: number, type = 'image/png') => new Blob([new Uint8Array(size)], { type })
const imageBlob = (bytes: Uint8Array<ArrayBuffer>, type: string) => new Blob([bytes], { type })

/** A valid JPEG padded with trailing bytes after EOI to hit an exact byte size. */
function jpegOfSize(size: number): Blob {
  const padded = new Uint8Array(size)
  padded.set(validJpeg)
  return new Blob([padded], { type: 'image/jpeg' })
}

const form = (...parts: (readonly [string, string | Blob])[]) => {
  const data = new FormData()
  for (const [name, value] of parts) data.append(name, value)
  return data
}

const rejection = (field: string, message: string) => ({
  kind: 'rejected' as const,
  field,
  message,
})
const TOO_LARGE = rejection(
  'image',
  'Image files may carry at most 10 MiB each and 20 MiB per request',
)

describe('readGenerationUpload', () => {
  test('reads the three parts in any order, the last of a repeated part winning', async () => {
    expect(
      await readGenerationUpload(
        form(
          ['articleId', ' 9 '],
          ['promptId', '1'],
          ['image', imageBlob(validJpeg, 'image/jpeg')],
          ['promptId', '42'],
          ['image', imageBlob(validPng, 'image/png')],
        ),
      ),
    ).toEqual({
      kind: 'received',
      image: { bytes: validPng, contentType: 'image/png' },
      promptId: 42,
      articleId: 9,
    })
  })

  test('a missing or empty image, and a missing or non-numeric id, name the field to fix first', async () => {
    const image = 'An image file is required'
    expect(await readGenerationUpload(form(['promptId', '1']))).toEqual(rejection('image', image))
    expect(await readGenerationUpload(form(['image', 'text'], ['promptId', '1']))).toEqual(
      rejection('image', image),
    )
    expect(await readGenerationUpload(form(['image', rawBlob(0)]))).toEqual(
      rejection('image', image),
    )
    for (const promptId of [[], [['promptId', 'abc']], [['promptId', rawBlob(2)]]] as const) {
      expect(await readGenerationUpload(form(['image', rawBlob(2)], ...promptId))).toEqual(
        rejection('promptId', 'A numeric prompt id is required'),
      )
    }
    expect(
      await readGenerationUpload(
        form(['image', rawBlob(2)], ['promptId', '1'], ['articleId', '1.5']),
      ),
    ).toEqual(rejection('articleId', 'A numeric article id is required'))
  })

  test('only valid JPEG, PNG, and WebP images matching their declared type are accepted', async () => {
    const submit = (blob: Blob) =>
      readGenerationUpload(form(['image', blob], ['promptId', '1'], ['articleId', '1']))

    for (const [bytes, type] of [
      [validJpeg, 'image/jpeg'],
      [validPng, 'image/png'],
      [validWebp, 'image/webp'],
    ] as const) {
      expect((await submit(imageBlob(bytes, type))).kind).toBe('received')
    }
    expect(await submit(rawBlob(16, 'text/html'))).toEqual(
      rejection('image', 'Image must be a JPEG, PNG, or WebP file'),
    )
    expect(await submit(rawBlob(16, 'image/png'))).toEqual(
      rejection('image', 'Unsupported or invalid image data'),
    )
    expect(await submit(imageBlob(validGif, 'image/png'))).toEqual(
      rejection('image', 'Invalid image data'),
    )
    expect(await submit(imageBlob(validJpeg, 'image/png'))).toEqual(
      rejection('image', 'Declared and decoded image formats differ'),
    )
  })

  test('an image past 40 megapixels is refused', async () => {
    const huge = toBytes(
      await sharp({
        create: { width: 7000, height: 6000, channels: 3, background: '#ffffff' },
      })
        .png({ compressionLevel: 9 })
        .toBuffer(),
    )
    expect(
      await readGenerationUpload(
        form(['image', imageBlob(huge, 'image/png')], ['promptId', '1'], ['articleId', '1']),
      ),
    ).toEqual(rejection('image', 'Decoded image exceeds 40 megapixels'))
  })

  test('an image of exactly 10 MiB passes, one byte more is refused, even before a smaller one', async () => {
    const ids: [string, string][] = [
      ['promptId', '1'],
      ['articleId', '1'],
    ]
    expect(
      (await readGenerationUpload(form(['image', jpegOfSize(MAX_IMAGE_BYTES)], ...ids))).kind,
    ).toBe('received')
    expect(
      await readGenerationUpload(form(['image', rawBlob(MAX_IMAGE_BYTES + 1)], ...ids)),
    ).toEqual(TOO_LARGE)
    expect(
      await readGenerationUpload(
        form(
          ['image', rawBlob(MAX_IMAGE_BYTES + 1)],
          ['image', imageBlob(validPng, 'image/png')],
          ...ids,
        ),
      ),
    ).toEqual(TOO_LARGE)
  })

  test('file parts of any name adding up past 20 MiB are refused on the image field', async () => {
    expect(
      await readGenerationUpload(
        form(
          ['other', rawBlob(MAX_IMAGE_BYTES)],
          ['image', rawBlob(MAX_IMAGE_BYTES)],
          ['promptId', '1'],
          ['articleId', '1'],
          ['extra', rawBlob(1)],
        ),
      ),
    ).toEqual(TOO_LARGE)
  })
})
