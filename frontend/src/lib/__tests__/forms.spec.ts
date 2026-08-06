import { describe, expect, it } from 'vitest'
import { optionalText } from '../forms'

describe('optionalText', () => {
  it('returns the trimmed value for non-empty input', () => {
    expect(optionalText('  hello  ')).toBe('hello')
  })

  it('returns null for empty or whitespace-only input', () => {
    expect(optionalText('')).toBeNull()
    expect(optionalText('   ')).toBeNull()
  })
})
