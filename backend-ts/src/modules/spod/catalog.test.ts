import { describe, expect, test } from 'bun:test'
import { downloadUrl, parseColorHex } from '@/modules/spod'

/**
 * The partner's colour format is undocumented, so this is the whole contract: whatever is
 * recognizable becomes one lowercase `#rrggbb`, everything else `null`, never a guessed colour.
 */
describe('parseColorHex', () => {
  test('every accepted spelling becomes the same lowercase six-digit colour', () => {
    expect(parseColorHex('#0A0B0C')).toBe('#0a0b0c')
    expect(parseColorHex('0a0b0c')).toBe('#0a0b0c')
    expect(parseColorHex('#ABC')).toBe('#aabbcc')
    expect(parseColorHex('abc')).toBe('#aabbcc')
    expect(parseColorHex('  #FFFFFF  ')).toBe('#ffffff')
  })

  test('a comma separated list is read as its first colour', () => {
    expect(parseColorHex('#112233,#445566')).toBe('#112233')
    expect(parseColorHex(' #112233 , #445566 ')).toBe('#112233')
    expect(parseColorHex('abc,def,123')).toBe('#aabbcc')
  })

  test('anything else is no colour at all', () => {
    for (const value of [
      null,
      '',
      '   ',
      ',',
      'black',
      '#12345',
      '#1234567',
      '#0a0b0g',
      'rgb(1,2,3)',
      '##0a0b0c',
    ]) {
      expect(parseColorHex(value)).toBeNull()
    }
  })
})

const IMAGE_SERVER = 'https://image.spreadshirtmedia.net/image-server/v1'

describe('downloadUrl', () => {
  test('the lookupId placeholder is replaced by the product id of the same answer', () => {
    expect(
      downloadUrl({
        productId: 598279462,
        imageUrl: `${IMAGE_SERVER}/products/lookupId/views/1,width=1000,appearanceId=2,mediaType=png/1`,
      }),
    ).toBe(`${IMAGE_SERVER}/products/598279462/views/1,width=1000,appearanceId=2,mediaType=png/1`)
  })

  test('a URL without the placeholder, or an answer without a product id, is used as it is', () => {
    const resolved = `${IMAGE_SERVER}/products/598279462/views/1,appearanceId=2,mediaType=png/1`
    const placeholder = `${IMAGE_SERVER}/products/lookupId/views/1,appearanceId=2,mediaType=png/1`

    expect(downloadUrl({ productId: 598279462, imageUrl: resolved })).toBe(resolved)
    expect(downloadUrl({ productId: 0, imageUrl: placeholder })).toBe(placeholder)
    expect(downloadUrl({ productId: 598279462, imageUrl: '' })).toBe('')
  })
})
