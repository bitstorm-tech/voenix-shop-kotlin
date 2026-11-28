import { describe, expect, test } from 'bun:test'
import type { SpodStockKey } from '@/modules/article'
import type { SpodAccess, SpodResult } from '@/modules/spod'
import { createSpodStockSync, type StockClient } from './stock'

const access = (destinationId: number): SpodAccess => ({
  destinationId,
  environment: 'PRODUCTION',
  accessToken: 'token',
  timeoutSeconds: 5,
})

const FAILED = { kind: 'failed', error: 'PROVIDER_UNAVAILABLE', ambiguous: true } as const

/** The sync over fake destinations 1 and 2, a fake partner, and a recorder of what it stores. */
const kit = (spod: Partial<StockClient>, keys: SpodStockKey[] = []) => {
  const recorded: [number, string, Record<string, number>, boolean][] = []
  const sync = createSpodStockSync({
    destinations: {
      spodDestinationIds: () => Promise.resolve([1, 2]),
      catalogSource: (id) => Promise.resolve({ supplierId: id, access: access(id) }),
    },
    spod: {
      stock: () => Promise.reject(new Error('unexpected')),
      stockOf: () => Promise.reject(new Error('unexpected')),
      ...spod,
    },
    stock: {
      record: (destinationId, environment, stock, complete) => {
        recorded.push([destinationId, environment, Object.fromEntries(stock), complete])
        return Promise.resolve(0)
      },
      keysOf: () => Promise.resolve(keys),
    },
  })
  return { sync, recorded }
}

/** A stock listing of `count` SKUs `s0`, `s1`, …, answered page by page. */
const listing =
  (count: number, answeredCount = count) =>
  (_access: SpodAccess, limit: number, offset: number) => {
    const items: Record<string, number> = {}
    for (let index = offset; index < Math.min(count, offset + limit); index++) {
      items[`s${String(index)}`] = index
    }
    return Promise.resolve({
      kind: 'answered',
      value: { items, count: answeredCount },
    } as SpodResult<{ items: Record<string, number>; count: number }>)
  }

describe('the stock scan', () => {
  test('reads every page and records a complete listing per destination', async () => {
    const offsets: number[] = []
    const { sync, recorded } = kit({
      stock: (a, limit, offset) => {
        offsets.push(offset)
        return listing(150)(a, limit, offset)
      },
    })
    await sync.scan()
    expect(offsets).toEqual([0, 100, 0, 100])
    expect(
      recorded.map(([id, , stock, complete]) => [id, Object.keys(stock).length, complete]),
    ).toEqual([
      [1, 150, true],
      [2, 150, true],
    ])
  })

  test('a count the pages do not reach is recorded as partial, so no SKU counts as gone', async () => {
    const { sync, recorded } = kit({ stock: listing(3, 5) })
    await sync.scan()
    expect(recorded.map(([, , , complete]) => complete)).toEqual([false, false])
  })

  test('a page the partner does not answer records nothing for that destination', async () => {
    const { sync, recorded } = kit({
      stock: (a, limit, offset) =>
        a.destinationId === 1 && offset > 0
          ? Promise.resolve(FAILED)
          : listing(150)(a, limit, offset),
    })
    await sync.scan()
    expect(recorded.map(([id]) => id)).toEqual([2])
  })
})

describe('refresh', () => {
  test('records the answered SKUs as partial and skips the unanswered and other environments', async () => {
    const asked: string[] = []
    const { sync, recorded } = kit(
      {
        stockOf: (_access, sku) => {
          asked.push(sku)
          return Promise.resolve(sku === 'down' ? FAILED : { kind: 'answered', value: 0 })
        },
      },
      [
        { variantId: 1, destinationId: 1, environment: 'PRODUCTION', sku: 'gone' },
        { variantId: 2, destinationId: 1, environment: 'PRODUCTION', sku: 'down' },
        { variantId: 3, destinationId: 1, environment: 'STAGING', sku: 'staging' },
      ],
    )
    await sync.refresh([1, 2, 3])
    expect(asked).toEqual(['gone', 'down'])
    expect(recorded).toEqual([[1, 'PRODUCTION', { gone: 0 }, false]])
  })

  test('a cart without SPOD lines asks the partner nothing', async () => {
    const { sync, recorded } = kit({})
    await sync.refresh([7])
    expect(recorded).toEqual([])
  })
})
