import { describe, expect, it } from 'vitest'
import { sizeRange, sortSizes } from '@/lib/tshirtSizes'

describe('sortSizes', () => {
  it('orders letter sizes the way a shop lists them', () => {
    expect(sortSizes(['S', '3XL', '4XL', '5XL', 'L', 'M', 'XL', 'XXL'])).toEqual([
      'S',
      'M',
      'L',
      'XL',
      'XXL',
      '3XL',
      '4XL',
      '5XL',
    ])
  })

  it('keeps unknown sizes in catalog order after the known ones', () => {
    expect(sortSizes(['122/128', 'M', '98/104', 'S'])).toEqual(['S', 'M', '122/128', '98/104'])
  })
})

describe('sizeRange', () => {
  it('spans from the smallest to the largest size', () => {
    expect(sizeRange(['XL', 'S', 'M'])).toBe('S – XL')
  })

  it('names a single size and returns null for none', () => {
    expect(sizeRange(['M'])).toBe('M')
    expect(sizeRange([])).toBeNull()
  })
})
