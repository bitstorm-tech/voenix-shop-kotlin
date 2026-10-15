import { describe, expect, test } from 'bun:test'
import { MAX_IMAGE_BYTES, readGenerationUpload } from './upload'

const png = (size: number, type = 'image/png') => new Blob([new Uint8Array(size)], { type })

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
          ['image', png(4, 'image/jpeg')],
          ['promptId', '42'],
          ['image', png(16)],
        ),
      ),
    ).toEqual({
      kind: 'received',
      image: { bytes: new Uint8Array(16), contentType: 'image/png' },
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
    expect(await readGenerationUpload(form(['image', png(0)]))).toEqual(rejection('image', image))
    for (const promptId of [[], [['promptId', 'abc']], [['promptId', png(2)]]] as const) {
      expect(await readGenerationUpload(form(['image', png(2)], ...promptId))).toEqual(
        rejection('promptId', 'A numeric prompt id is required'),
      )
    }
    expect(
      await readGenerationUpload(form(['image', png(2)], ['promptId', '1'], ['articleId', '1.5'])),
    ).toEqual(rejection('articleId', 'A numeric article id is required'))
  })

  test('only JPEG, PNG, and WebP are accepted, whatever the casing', async () => {
    const withType = (type: string) =>
      readGenerationUpload(form(['image', png(2, type)], ['promptId', '1'], ['articleId', '1']))
    for (const type of ['image/jpeg', 'image/png', 'image/webp'])
      expect((await withType(type)).kind).toBe('received')
    expect(await withType('text/html')).toEqual(
      rejection('image', 'Image must be a JPEG, PNG, or WebP file'),
    )
  })

  test('an image of exactly 10 MiB passes, one byte more is refused, even before a smaller one', async () => {
    const ids: [string, string][] = [
      ['promptId', '1'],
      ['articleId', '1'],
    ]
    expect((await readGenerationUpload(form(['image', png(MAX_IMAGE_BYTES)], ...ids))).kind).toBe(
      'received',
    )
    expect(await readGenerationUpload(form(['image', png(MAX_IMAGE_BYTES + 1)], ...ids))).toEqual(
      TOO_LARGE,
    )
    expect(
      await readGenerationUpload(
        form(['image', png(MAX_IMAGE_BYTES + 1)], ['image', png(2)], ...ids),
      ),
    ).toEqual(TOO_LARGE)
  })

  test('file parts of any name adding up past 20 MiB are refused on the image field', async () => {
    expect(
      await readGenerationUpload(
        form(
          ['other', png(MAX_IMAGE_BYTES)],
          ['image', png(MAX_IMAGE_BYTES)],
          ['promptId', '1'],
          ['articleId', '1'],
          ['extra', png(1)],
        ),
      ),
    ).toEqual(TOO_LARGE)
  })
})
