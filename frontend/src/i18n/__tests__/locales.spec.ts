import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
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

const sourceRoot = join(dirname(fileURLToPath(import.meta.url)), '..', '..')
const sourceExtensions = ['.ts', '.vue']

function collectSourceFiles(directory: string): string[] {
  return readdirSync(directory).flatMap((entry) => {
    const path = join(directory, entry)
    if (statSync(path).isDirectory()) {
      return collectSourceFiles(path)
    }

    return sourceExtensions.some((extension) => path.endsWith(extension)) ? [path] : []
  })
}

/** Namespaces that T-shirt support retired; assembling the dotted prefix keeps this file clean. */
const retiredNamespaces = ['mugCard', 'mugOverview', 'mugConfigurator', 'megaMenu']

describe('locale files', () => {
  it('carry the same key set in German and English', () => {
    const germanKeys = flattenKeys(de).sort()
    const englishKeys = flattenKeys(en).sort()

    expect(germanKeys).toEqual(englishKeys)
  })

  it('no longer contain the mug-only namespaces', () => {
    for (const namespace of retiredNamespaces) {
      expect(Object.keys(de)).not.toContain(namespace)
      expect(Object.keys(en)).not.toContain(namespace)
    }
  })
})

describe('translation usages', () => {
  it('reference no retired namespace anywhere in the frontend sources', () => {
    const retiredPrefixes = retiredNamespaces.map((namespace) => `${namespace}.`)
    const offenders = collectSourceFiles(sourceRoot).filter((path) => {
      const content = readFileSync(path, 'utf8')
      return retiredPrefixes.some((prefix) => content.includes(prefix))
    })

    expect(offenders).toEqual([])
  })
})
