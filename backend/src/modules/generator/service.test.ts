import { describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import pino from 'pino'
import { testDatabase } from '@test-support/database'
import { rejectionOf } from '@test-support/values'
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

  test('two parallel requests with one coin left invoke the provider at most once', async () => {
    let providerCalls = 0
    const { service } = harness(async (image) => {
      providerCalls += 1
      await Bun.sleep(50)
      return image
    })
    const owner = { kind: 'guest', token: 'one-coin-race' } as const
    await sql`INSERT INTO magic_coins (guest_session_token, balance) VALUES ('one-coin-race', 1)`.execute(
      database.db,
    )
    const outcomes = await Promise.all([
      service.generate(owner, received),
      service.generate(owner, received),
    ])
    expect(outcomes.map((outcome) => outcome.kind).sort()).toEqual([
      'generated',
      'insufficientCoins',
    ])
    expect(providerCalls).toBe(1)
    expect(await createMagicCoins(database.db).readBalance(owner)).toBe(0)
  })

  test('a provider upstream failure refunds the upfront coin', async () => {
    const { service } = harness(() => Promise.resolve(null))
    const owner = { kind: 'guest', token: 'refund-upstream' } as const
    await sql`INSERT INTO magic_coins (guest_session_token, balance) VALUES ('refund-upstream', 3)`.execute(
      database.db,
    )
    expect(await service.generate(owner, received)).toEqual({ kind: 'upstreamFailure' })
    expect(await createMagicCoins(database.db).readBalance(owner)).toBe(3)
  })

  test('an unexpected provider error refunds the upfront coin and rethrows', async () => {
    const { service } = harness(() => Promise.reject(new Error('unexpected failure')))
    const owner = { kind: 'guest', token: 'refund-throw' } as const
    await sql`INSERT INTO magic_coins (guest_session_token, balance) VALUES ('refund-throw', 4)`.execute(
      database.db,
    )
    expect(await rejectionOf(service.generate(owner, received))).toBe('unexpected failure')
    expect(await createMagicCoins(database.db).readBalance(owner)).toBe(4)
  })

  test('a refund that fails after an upstream failure logs without leaking the guest token', async () => {
    const { service, calls, logged } = harness(async () => {
      await sql`ALTER TABLE magic_coins RENAME TO magic_coins_away`.execute(database.db)
      return null
    })
    try {
      expect(await service.generate({ kind: 'guest', token: 'unlucky' }, received)).toEqual({
        kind: 'upstreamFailure',
      })
    } finally {
      await sql`ALTER TABLE magic_coins_away RENAME TO magic_coins`.execute(database.db)
    }
    expect(calls).toEqual(['printFormats', 'composedText', 'generate'])
    expect(logged.join('\n')).toContain('Magic Coin refund failed for guest owner')
    expect(logged.join('\n')).not.toContain('unlucky')
  })
})
