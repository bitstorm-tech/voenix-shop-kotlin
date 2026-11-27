import type { MiddlewareHandler } from 'hono'
import type { Kysely } from 'kysely'
import type { AccountDirectory } from '@/modules/account'
import type { ArticleCatalog } from '@/modules/article'
import type { CartModule } from '@/modules/cart'
import type { EmailModule } from '@/modules/email'
import type { Orders } from '@/modules/order'
import type { PromptCatalog } from '@/modules/prompt'
import type { Auth } from '@/platform/auth/auth'
import type { Tables } from '@/platform/db/database'
import { createLogger } from '@/platform/logging'
import type { Settings } from '@/platform/settings'
import { createFalBackgroundRemover, createFalImageGenerator } from './fal'
import { dummyBackgroundRemover, dummyImageGenerator } from './image-generator'
import { createMagicCoinRequestsService } from './magic-coin-requests'
import { createMagicCoins } from './magic-coins'
import { generatorRoutes } from './routes'
import { createGeneratorService } from './service'

export type {
  AdminBlockedMagicCoinAccount,
  AdminMagicCoinRequest,
  CustomerMagicCoinRequestStatus,
} from './magic-coin-requests'

const noopRateLimit: MiddlewareHandler = async (_c, next) => {
  await next()
}

/**
 * The generator module with its Magic Coins and manual refill review workflow:
 * `POST /api/generator/generate`, `GET /api/magic-coins/request-status`,
 * `POST /api/magic-coins/requests`, `/api/admin/magic-coins/*`, and the admin's
 * `POST /api/admin/images/background-removal`. Exports `pendingRequestCount` for the admin menu
 * badge. Which generator and background remover run is decided here, once: the dummies in dummy
 * mode, fal.ai otherwise.
 */
export function createGeneratorModule({
  db,
  settings,
  articleCatalog,
  promptCatalog,
  accounts,
  orders,
  carts,
  outbox,
  alertEmail = null,
  frontendBaseUrl = 'http://localhost:5173',
  guestTokens,
  rateLimit,
  requestRateLimit = noopRateLimit,
  fetch = globalThis.fetch,
}: {
  db: Kysely<Tables>
  settings: Pick<Settings['generator'], 'dummyMode' | 'apiKey'>
  articleCatalog: Pick<ArticleCatalog, 'printFormats'>
  promptCatalog: Pick<PromptCatalog, 'composedText'>
  accounts: Pick<AccountDirectory, 'findByIds' | 'findByEmail'>
  orders: Pick<Orders, 'paidOrderCounts'>
  carts: Pick<CartModule, 'activeCartItemCounts'>
  outbox: Pick<EmailModule['outbox'], 'enqueue'>
  alertEmail?: string | null
  frontendBaseUrl?: string
  guestTokens: Auth['guestTokens']
  rateLimit: MiddlewareHandler
  requestRateLimit?: MiddlewareHandler
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
  const requests = createMagicCoinRequestsService({
    db,
    coins,
    accounts,
    orders,
    carts,
    outbox,
    alertEmail,
    frontendBaseUrl,
    logger,
  })
  const removeBackground = settings.dummyMode
    ? dummyBackgroundRemover
    : createFalBackgroundRemover(fal)
  return {
    routes: generatorRoutes({
      generator,
      removeBackground,
      requests,
      guestTokens,
      rateLimit,
      requestRateLimit,
    }),
    pendingRequestCount: requests.pendingCount,
  }
}

export type GeneratorModule = ReturnType<typeof createGeneratorModule>
