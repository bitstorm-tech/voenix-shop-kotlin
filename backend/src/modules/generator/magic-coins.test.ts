import { beforeAll, describe, expect, test } from 'bun:test'
import { sql } from 'kysely'
import { testDatabase } from '@test-support/database'
import { createMagicCoins } from './magic-coins'

const database = testDatabase()
beforeAll(async () => {
  await sql`INSERT INTO users (id, email, password_hash)
    VALUES (21, 'customer-21@example.com', 'hash')`.execute(database.db)
})
const rowsOf = async (token: string) =>
  (
    await sql<{
      balance: number
    }>`SELECT balance FROM magic_coins WHERE guest_session_token = ${token}`.execute(database.db)
  ).rows

describe('magic coins', () => {
  test('concurrent first contacts of one guest create exactly one balance', async () => {
    const coins = createMagicCoins(database.db)
    const owner = { kind: 'guest', token: 'first-contact' } as const
    expect(await Promise.all([1, 2, 3, 4].map(() => coins.balance(owner)))).toEqual([
      10, 10, 10, 10,
    ])
    expect(await rowsOf('first-contact')).toEqual([{ balance: 10 }])
  })

  test('a spend takes one coin and is refused at zero', async () => {
    const coins = createMagicCoins(database.db)
    const owner = { kind: 'user', userId: 21 } as const
    await sql`INSERT INTO magic_coins (user_id, balance) VALUES (21, 1)`.execute(database.db)
    expect(await coins.spend(owner)).toBe(true)
    expect(await coins.spend(owner)).toBe(false)
    expect(await coins.balance(owner)).toBe(0)
  })

  test('concurrent spends of the last coins never take the balance below zero', async () => {
    const coins = createMagicCoins(database.db)
    const owner = { kind: 'guest', token: 'last-coins' } as const
    await sql`INSERT INTO magic_coins (guest_session_token, balance) VALUES ('last-coins', 2)`.execute(
      database.db,
    )
    const spends = await Promise.all([1, 2, 3, 4, 5, 6].map(() => coins.spend(owner)))
    expect(spends.filter(Boolean)).toHaveLength(2)
    expect(await rowsOf('last-coins')).toEqual([{ balance: 0 }])
  })
})
