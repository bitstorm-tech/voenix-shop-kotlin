import type { MiddlewareHandler } from 'hono'
import type { Kysely } from 'kysely'
import type { ArticleCatalog } from '@/modules/article'
import type { PromptCatalog } from '@/modules/prompt'
import type { Auth } from '@/platform/auth/auth'
import type { Tables } from '@/platform/db/database'
import { createLogger } from '@/platform/logging'
import type { Settings } from '@/platform/settings'
import { createFalImageGenerator } from './fal'
import { dummyImageGenerator } from './image-generator'
import { createMagicCoins } from './magic-coins'
import { generatorRoutes } from './routes'
import { createGeneratorService } from './service'

/**
 * The generator module with its Magic Coins: `POST /api/generator/generate` and
 * `GET /api/magic-coins/balance`. It exports no capability. Which image generator runs is decided
 * here, once: the dummy in dummy mode, fal.ai otherwise. `rateLimit` is the platform's per-IP
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
  const generator = createGeneratorService({
    coins,
    articleCatalog,
    promptCatalog,
    generateImage: settings.dummyMode
      ? dummyImageGenerator
      : createFalImageGenerator({ apiKey: settings.apiKey, fetch, logger }),
    logger,
  })
  return { routes: generatorRoutes({ generator, coins, guestTokens, rateLimit }) }
}

export type GeneratorModule = ReturnType<typeof createGeneratorModule>
