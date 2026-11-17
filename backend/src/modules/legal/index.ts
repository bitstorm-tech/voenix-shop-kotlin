import { Hono } from 'hono'
import { z } from 'zod'
import type { Fetch } from '@/platform/fetch'
import { apiError } from '@/platform/http/respond'
import { createLogger, errorDetails } from '@/platform/logging'

/** The iubenda Privacy & Cookie Policy of the shop's iubenda site; its API is public, no key. */
const POLICY_URL = 'https://www.iubenda.com/api/privacy-policy/48736531'
const SOURCES = {
  privacyPolicy: `${POLICY_URL}/no-markup`,
  cookiePolicy: `${POLICY_URL}/cookie-policy/no-markup`,
} as const
const MAX_AGE_MILLIS = 60 * 60 * 1000
const TIMEOUT_MILLIS = 10_000

const iubendaAnswer = z.object({ success: z.literal(true), content: z.string().min(1) })

const logger = createLogger('legal')

export interface PrivacyDocuments {
  privacyPolicy: string
  cookiePolicy: string
}

/**
 * The privacy and cookie policy, written and kept in iubenda and served by the shop itself. The
 * backend reads them, not the browser: iubenda's embed script would send every visitor's IP to
 * iubenda before they have read a word about it. A copy older than an hour is served while a fresh
 * one loads in the background, and a failed refresh keeps the copy, so an iubenda outage only
 * shows when the process never read the documents at all.
 */
export function createLegalModule({ fetch, now = Date.now }: { fetch: Fetch; now?: () => number }) {
  let cached: { documents: PrivacyDocuments; fetchedAt: number } | null = null
  let refreshing: Promise<void> | null = null

  const load = async (url: string) => {
    const response = await fetch(url, {
      redirect: 'manual',
      signal: AbortSignal.timeout(TIMEOUT_MILLIS),
    })
    if (!response.ok) throw new Error(`iubenda answered HTTP ${String(response.status)}`)
    return iubendaAnswer.parse(await response.json()).content
  }

  const refresh = () =>
    (refreshing ??= Promise.all([load(SOURCES.privacyPolicy), load(SOURCES.cookiePolicy)])
      .then(([privacyPolicy, cookiePolicy]) => {
        cached = { documents: { privacyPolicy, cookiePolicy }, fetchedAt: now() }
      })
      .catch((error: unknown) => {
        logger.warn({ error: errorDetails(error) }, 'iubenda could not be read')
      })
      .finally(() => {
        refreshing = null
      }))

  const documents = async () => {
    if (cached === null) await refresh()
    else if (now() - cached.fetchedAt > MAX_AGE_MILLIS) void refresh()
    return cached?.documents ?? null
  }

  const routes = new Hono()
  routes.get('/api/legal/privacy', async (c) => {
    const found = await documents()
    if (found === null) return c.json(apiError('The privacy policy could not be loaded'), 502)
    return c.json(found)
  })
  return { routes }
}
