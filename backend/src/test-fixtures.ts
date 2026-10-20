import { mkdtemp } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createApplication } from '@/composition'
import type { MolliePayment, MolliePayments } from '@/modules/order'
import type { DatabaseSettings } from '@/platform/db/database'
import { loadSettings, type Settings } from '@/platform/settings'
import { TEST_SESSION_SECRET } from '@test-support/http'

/**
 * What the composed tests share: the settings a deployment would carry pointed at a test database
 * and temporary roots, a Mollie stub, and the whole application built on them. Test-only. The
 * checkout tests, which run the one journey that spans five modules, use it from their module
 * folder.
 */

export const WEBHOOK_SECRET = 'composed-test-webhook-secret'

/** The application's `Settings` for `database`, with every root below `root` and dummy mode on. */
export function composedSettings(
  database: DatabaseSettings,
  root: string,
  extra: Record<string, string> = {},
): Settings {
  return loadSettings({
    DATABASE_HOST: database.host,
    DATABASE_PORT: String(database.port),
    DATABASE_NAME: database.name,
    DATABASE_USERNAME: database.username,
    DATABASE_PASSWORD: database.password,
    DATABASE_SEARCH_PATH: database.searchPath,
    // Room for the requests a concurrency journey runs at once.
    DATABASE_MAXIMUM_POOL_SIZE: '8',
    // The secret of `testAuth`, so its sessions, CSRF pairs, and guest cookies are read here.
    AUTH_SESSION_SECRET: TEST_SESSION_SECRET,
    GENERATOR_DUMMY_MODE: 'true',
    MOLLIE_API_KEY: 'test_composed_mollie_key',
    MOLLIE_WEBHOOK_URL: `https://voenix.test/api/payments/webhook/${WEBHOOK_SECRET}`,
    MOLLIE_WEBHOOK_SECRET: WEBHOOK_SECRET,
    PRODUCTION_ARTIFACT_ROOT: join(root, 'artifacts'),
    IMAGE_PUBLIC_ROOT: join(root, 'images', 'public'),
    IMAGE_PRIVATE_ROOT: join(root, 'images', 'private'),
    IMAGE_CACHE_ROOT: join(root, 'images', 'cache'),
    ...extra,
  })
}

/**
 * A Mollie that records its calls. `create` answers an `OPEN` payment for the order's total (or
 * `null` while `refuse` is set) after yielding, so two concurrent checkouts really overlap; `find`
 * answers what `report` has set, which is how a test plays Mollie's side of a webhook.
 */
export function stubMollie() {
  const reported = new Map<string, MolliePayment>()
  const calls = { create: 0, find: 0, cancel: [] as string[] }
  const state = { refuse: false }
  const mollie: MolliePayments = {
    create: async (order) => {
      calls.create += 1
      await Bun.sleep(50)
      if (state.refuse) return null
      const id = `tr_${String(order.orderId)}_${String(calls.create)}`
      const payment: MolliePayment = {
        id,
        status: 'OPEN',
        amountCents: order.totalCents,
        checkoutUrl: `https://mollie.test/checkout/${id}`,
      }
      reported.set(id, payment)
      return payment
    },
    find: (id) => {
      calls.find += 1
      return Promise.resolve(reported.get(id) ?? null)
    },
    cancel: (id) => {
      calls.cancel.push(id)
      return Promise.resolve(true)
    },
  }
  /** Plays Mollie's side: the payment `id` now has `status` (and optionally another amount). */
  const report = (id: string, change: Partial<MolliePayment>) => {
    const payment = reported.get(id)
    if (payment === undefined) throw new Error(`No stub payment ${id}`)
    reported.set(id, { ...payment, ...change })
  }
  const reset = () => {
    reported.clear()
    calls.create = 0
    calls.find = 0
    calls.cancel = []
    state.refuse = false
  }
  return { mollie, calls, state, report, reset }
}

/** The whole application on `database`, with a Mollie stub and a `fetch` that reaches nothing. */
export async function composedApplication(database: DatabaseSettings) {
  const root = await mkdtemp(join(tmpdir(), 'voenix-composed-'))
  const mollie = stubMollie()
  const application = await createApplication(composedSettings(database, root), {
    mollie: mollie.mollie,
    fetch: () => Promise.reject(new Error('The composed tests reach no network')),
  })
  return { ...application, mollie, root }
}

export type ComposedApplication = Awaited<ReturnType<typeof composedApplication>>
