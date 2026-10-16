import { describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import pino from 'pino'
import { testDatabase } from '@test-support/database'
import type { ImageGenerator } from './image-generator'
import { createMagicCoins } from './magic-coins'
import { createGeneratorService } from './service'

const database = testDatabase()
const IMAGE = { bytes: new Uint8Array([1, 2, 3]), contentType: 'image/png' }

/** The service on recording fakes: every step it takes lands in `calls`. */
function harness(generateImage: ImageGenerator) {
  const calls: string[] = []
  const logged: string[] = []
  const service = createGeneratorService({
    coins: createMagicCoins(database.db),
    articleCatalog: {
      printFormats: (ids) => {
        calls.push('printFormats')
        return Promise.resolve(new Map([...ids].map((id) => [id, '1:1'] as const)))
      },
    },
    promptCatalog: {
      composedText: () => {
        calls.push('composedText')
        return Promise.resolve('text')
      },
    },
    generateImage: (...args) => {
      calls.push('generate')
      return generateImage(...args)
    },
    logger: pino({ level: 'trace' }, { write: (line: string) => logged.push(line) }),
  })
  return { service, calls, logged }
}

const received = { image: IMAGE, promptId: 1, articleId: 1 }

describe('the order of one generation', () => {
  test('an empty balance asks nothing else', async () => {
    const { service, calls } = harness(() => Promise.resolve(IMAGE))
    const owner = { kind: 'guest', token: 'broke' } as const
    await sql`INSERT INTO magic_coins (guest_session_token, balance) VALUES ('broke', 0)`.execute(
      database.db,
    )
    expect(await service.generate(owner, received)).toEqual({ kind: 'insufficientCoins' })
    expect(calls).toEqual([])
  })

  test('a spend that fails after the generation only logs and keeps the image', async () => {
    const { service, calls, logged } = harness(async (image) => {
      await sql`ALTER TABLE magic_coins RENAME TO magic_coins_away`.execute(database.db)
      return image
    })
    try {
      expect(await service.generate({ kind: 'guest', token: 'unlucky' }, received)).toEqual({
        kind: 'generated',
        image: IMAGE,
      })
    } finally {
      await sql`ALTER TABLE magic_coins_away RENAME TO magic_coins`.execute(database.db)
    }
    expect(calls).toEqual(['printFormats', 'composedText', 'generate'])
    expect(logged.join('\n')).toContain('Magic Coin spend failed for guest owner')
    expect(logged.join('\n')).not.toContain('unlucky')
  })
})
