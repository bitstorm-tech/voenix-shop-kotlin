import type { MiddlewareHandler } from 'hono'
import type { Kysely } from 'kysely'
import type { ArticleCatalog } from '@/modules/article'
import type { PromptCatalog } from '@/modules/prompt'
import type { Auth } from '@/platform/auth/auth'
import type { Tables } from '@/platform/db/database'
import { createLogger } from '@/platform/logging'
import type { Settings } from '@/platform/settings'
import { createFalBackgroundRemover, createFalImageGenerator } from './fal'
import { dummyBackgroundRemover, dummyImageGenerator } from './image-generator'
import { createMagicCoins } from './magic-coins'
import { generatorRoutes } from './routes'
import { createGeneratorService } from './service'

/**
 * The generator module with its Magic Coins: `POST /api/generator/generate`,
 * `GET /api/magic-coins/balance`, and the admin's `POST /api/admin/images/background-removal`. It
 * exports no capability. Which generator and background remover run is decided here, once: the
 * dummies in dummy mode, fal.ai otherwise. `rateLimit` is the platform's per-IP
 * limiter middleware; `fetch` is the fal.ai adapter's HTTP client.
 */
export function createGeneratorModule({
  db,
  settings,
  articleCatalog,
  promptCatalog,
  guestTokens,
  rateLimit,
  fetch = globalThis.fetch,
}: {
  db: Kysely<Tables>
  settings: Settings['generator']
  articleCatalog: Pick<ArticleCatalog, 'printFormats'>
  promptCatalog: Pick<PromptCatalog, 'composedText'>
  guestTokens: Auth['guestTokens']
  rateLimit: MiddlewareHandler
  fetch?: (url: string, init: RequestInit) => Promise<Response>
}) {
  const logger = createLogger('generator')
  const coins = createMagicCoins(db)
  const fal = { apiKey: settings.apiKey, fetch, logger }
  const generator = createGeneratorService({
    coins,
    articleCatalog,
    promptCatalog,
    generateImage: settings.dummyMode ? dummyImageGenerator : createFalImageGenerator(fal),
    logger,
  })
  const removeBackground = settings.dummyMode
    ? dummyBackgroundRemover
    : createFalBackgroundRemover(fal)
  return { routes: generatorRoutes({ generator, removeBackground, coins, guestTokens, rateLimit }) }
}
