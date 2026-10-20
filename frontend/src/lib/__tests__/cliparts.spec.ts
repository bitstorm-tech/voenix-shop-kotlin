import { describe, expect, it } from 'vitest'
import de from '@/i18n/locales/de.json'
import en from '@/i18n/locales/en.json'
import { CLIPART_CATEGORIES, CLIPART_IDS, clipartSvg, clipartUrl } from '@/lib/cliparts'

describe('cliparts', () => {
  it('offers at least 30 distinct cliparts', () => {
    expect(CLIPART_IDS.length).toBeGreaterThanOrEqual(30)
    expect(new Set(CLIPART_IDS).size).toBe(CLIPART_IDS.length)
  })

  it('builds every clipart as a well-formed square SVG with an intrinsic size', () => {
    for (const id of CLIPART_IDS) {
      const svg = clipartSvg(id)
      const document = new DOMParser().parseFromString(svg, 'image/svg+xml')

      expect(document.querySelector('parsererror')).toBeNull()
      const root = document.documentElement
      expect(root.getAttribute('viewBox')).toBe('0 0 100 100')
      expect(root.getAttribute('width')).toBe(root.getAttribute('height'))
      expect(root.children.length).toBeGreaterThan(0)
      expect(svg).not.toMatch(/NaN|undefined/)
    }
  })

  it('encodes the SVG as a data URL', () => {
    expect(decodeURIComponent(clipartUrl('heart').replace('data:image/svg+xml,', ''))).toBe(
      clipartSvg('heart'),
    )
  })

  it('names every category and clipart in both locales', () => {
    for (const messages of [de, en]) {
      const cliparts = messages.editor.cliparts as {
        categories: Record<string, string>
        options: Record<string, string>
      }
      for (const category of CLIPART_CATEGORIES) {
        expect(cliparts.categories[category.id]).toBeTruthy()
      }
      for (const id of CLIPART_IDS) {
        expect(cliparts.options[id]).toBeTruthy()
      }
    }
  })
})
