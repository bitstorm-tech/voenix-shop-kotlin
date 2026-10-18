import { describe, expect, test } from 'bun:test'
import { MAX_IMAGE_BYTES } from './codec'
import { readUploadedImage } from './upload'

const form = (...parts: [string, string | Blob][]) => {
  const data = new FormData()
  for (const [name, value] of parts) data.append(name, value)
  return data
}

describe('readUploadedImage', () => {
  test('reads the file part with its content type and skips text parts of the same name', async () => {
    const result = await readUploadedImage(
      form(
        ['file', 'text'],
        ['other', new Blob([new Uint8Array(4)])],
        ['file', new Blob([new Uint8Array(16)], { type: 'image/webp' })],
      ),
    )
    expect(result).toEqual({
      kind: 'received',
      upload: { bytes: new Uint8Array(16), contentType: 'image/webp' },
    })
  })

  test('a body without a file part is missing one', async () => {
    expect(await readUploadedImage(form(['file', 'text']))).toEqual({ kind: 'missing' })
  })

  test('accepts exactly 10 MiB and refuses one byte more', async () => {
    const sized = (size: number) =>
      form(['file', new Blob([new Uint8Array(size)], { type: 'image/png' })])
    expect((await readUploadedImage(sized(MAX_IMAGE_BYTES))).kind).toBe('received')
    expect(await readUploadedImage(sized(MAX_IMAGE_BYTES + 1))).toEqual({ kind: 'tooLarge' })
  })
})
