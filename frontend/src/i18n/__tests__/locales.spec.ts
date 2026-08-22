import { describe, expect, it } from 'vitest'
import de from '../locales/de.json'
import en from '../locales/en.json'

type Messages = Record<string, unknown>

/**
 * Every leaf of a locale file as a dotted path. Arrays are addressed by index so that a list of
 * shipping cards counts as several keys, exactly like the objects around it.
 */
function flattenKeys(messages: unknown, prefix = ''): string[] {
  if (Array.isArray(messages)) {
    return messages.flatMap((entry, index) => flattenKeys(entry, `${prefix}[${index}]`))
  }

  if (typeof messages === 'object' && messages !== null) {
    return Object.entries(messages as Messages).flatMap(([key, value]) =>
      flattenKeys(value, prefix === '' ? key : `${prefix}.${key}`),
    )
  }

  return [prefix]
}

describe('locale files', () => {
  it('carry the same key set in German and English', () => {
    const germanKeys = flattenKeys(de).sort()
    const englishKeys = flattenKeys(en).sort()

    expect(germanKeys).toEqual(englishKeys)
  })
})
