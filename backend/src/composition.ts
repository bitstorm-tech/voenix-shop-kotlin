import { statSync } from 'node:fs'
import { basename, extname, join, resolve } from 'node:path'
import type { Context, Hono } from 'hono'
import { getConnInfo, serveStatic } from '@hono/bun'
import { createAccessTokens, createAccountModule, createSessionAccounts } from '@/modules/account'
import { createArticleModule } from '@/modules/article'
import { createCartModule, createPrintImageReads } from '@/modules/cart'
import { createCheckoutModule } from '@/modules/checkout'
import { createCountryModule } from '@/modules/country'
import { createEmailModule } from '@/modules/email'
import { createGeneratorModule } from '@/modules/generator'
import { createImageModule } from '@/modules/image'
import { createLegalModule } from '@/modules/legal'
import { createMcpModule } from '@/modules/mcp'
import { createOperationsModule } from '@/modules/operations'
import { createOrderModule, type MolliePayments } from '@/modules/order'
import { createPricingModule } from '@/modules/pricing'
import { createProductionModule } from '@/modules/production'
import { createPromotionModule } from '@/modules/promotion'
import { createPromptModule } from '@/modules/prompt'
import { createSpodClient } from '@/modules/spod'
import { createSupplierModule } from '@/modules/supplier'
import { createAuth } from '@/platform/auth/auth'
import { createDatabase } from '@/platform/db/database'
import { migrate } from '@/platform/db/migrate'
import { createApp } from '@/platform/http/app'
import { databaseReadyCheck, healthRoutes } from '@/platform/http/health'
import { legacyRedirectRoutes } from '@/platform/http/legacy-redirects'
import { createLogger } from '@/platform/logging'
import { createClientIpRateLimiter } from '@/platform/ratelimit/client-ip-rate-limiter'
import type { Settings } from '@/platform/settings'

const log = createLogger('composition')

/**
 * The composition root: the whole application from one `Settings` object. It migrates the database
 * first (the production module reads a table while it is created, and every module must see the
 * schema it was written for), opens the one pool, and creates every module after the modules it
 * consumes, in the order of the Kotlin `Application.kt` minus the late binding. `start()` starts the
 * three workers; `stop()` ends them and the pool.
 *
 * `overrides` is for the composed tests only, and a deployment never sets it: `mollie` replaces the
 * provider adapter with a stub, `fetch` is the one HTTP client the email delivery, the image
 * generator, the print-on-demand client, and the legal documents share.
 */
export async function createApplication(
  settings: Settings,
  overrides: {
    mollie?: MolliePayments
    fetch?: (url: string, init: RequestInit) => Promise<Response>
    peerAddress?: (c: Context) => string
  } = {},
) {
  const appliedMigrations = await migrate(settings.database)
  if (appliedMigrations.length > 0) {
    log.info({ applied: appliedMigrations }, 'Applied database migrations')
  }
  const db = createDatabase(settings.database)
  const fetch = overrides.fetch ?? globalThis.fetch
  const peerAddress = overrides.peerAddress ?? bunPeerAddress
  const rateLimit = (options: { limit: number; windowSeconds: number; globalLimit?: number }) =>
    createClientIpRateLimiter({
      trustForwardedFor: settings.rateLimit.trustForwardedFor,
      peerAddress,
      ...options,
    }).middleware
  try {
    const auth = createAuth({
      ...settings.auth,
      accessTokens: createAccessTokens(db),
      sessions: createSessionAccounts(db),
    })
    const app = createApp(auth, { https: settings.auth.secureCookies })
    const { guestTokens } = auth
    const frontendBaseUrl = settings.frontend.baseUrl

    // The master data every customer-facing module reads and none of them writes.
    const country = createCountryModule({ db })
    const pricing = createPricingModule({ db })
    const supplier = createSupplierModule({ db, countries: country })
    const email = createEmailModule({ db, settings: settings.email, fetch })
    const image = createImageModule({ settings: settings.image, guestTokens })
    // The one print-on-demand client: one pacer holds the partner's request budget for the whole
    // application; article syncs the SPOD catalog through it, production submits orders.
    const spod = createSpodClient({ fetch })
    const promotion = createPromotionModule({ db })
    const article = createArticleModule({
      db,
      images: image,
      prices: pricing.priceCatalog,
      suppliers: supplier,
      spod,
    })
    const prompt = createPromptModule({
      db,
      priceCatalog: pricing.priceCatalog,
      exampleImages: image.exampleImages,
    })
    // Account exports the supplier link the production routes resolve on every request.
    const account = createAccountModule({
      db,
      auth,
      userEmails: email.userEmails,
      frontendBaseUrl,
      rateLimits: {
        login: rateLimit({ limit: 20, windowSeconds: 15 * 60 }),
        register: rateLimit({ limit: 5, windowSeconds: 60 * 60 }),
        forgotPassword: rateLimit({ limit: 5, windowSeconds: 60 * 60 }),
        resendConfirmation: rateLimit({ limit: 5, windowSeconds: 60 * 60 }),
      },
    })

    // Order comes before production, which is created with order's reads; the two admin PDF
    // routes need production's generator and are mounted once it exists. The print image read is
    // the cart's, created on its own because the cart module itself comes after order.
    const order = createOrderModule({
      db,
      settings: { mollie: settings.mollie, frontendBaseUrl },
      articleCatalog: article.articleCatalog,
      promotionCodes: promotion.promotionCodes,
      email,
      images: image,
      printImages: createPrintImageReads(db),
      guestTokens,
      ...(overrides.mollie === undefined ? {} : { mollie: overrides.mollie }),
    })
    const production = await createProductionModule({
      db,
      settings: settings.production,
      email,
      suppliers: supplier,
      articleCatalog: article.articleCatalog,
      spodCatalogSync: article.spodCatalogSync,
      spodStock: article.spodStock,
      spod,
      accounts: account.supplierAccounts,
      orders: order.orders,
      alertEmails: settings.operations.alertEmails,
    })
    // The cart owns the print images the guest image route serves, so that route is installed
    // once both sides exist, like the orphan routes, which ask every module that refers to images.
    const cart = createCartModule({
      db,
      articleCatalog: article.articleCatalog,
      promptCatalog: prompt.promptCatalog,
      promotionCodes: promotion.promotionCodes,
      orders: order.orders,
      images: image,
      guestTokens,
      promotionRateLimit: rateLimit({ limit: 20, windowSeconds: 15 * 60 }),
    })
    const generator = createGeneratorModule({
      db,
      settings: settings.generator,
      articleCatalog: article.articleCatalog,
      promptCatalog: prompt.promptCatalog,
      accounts: account.accountDirectory,
      orders: order.orders,
      carts: cart,
      outbox: email.outbox,
      alertEmails: settings.operations.alertEmails,
      frontendBaseUrl,
      guestTokens,
      rateLimit: rateLimit({
        limit: 20,
        windowSeconds: 60 * 60,
        globalLimit: settings.generator.globalMaxPerHour,
      }),
      requestRateLimit: rateLimit({ limit: 10, windowSeconds: 60 * 60 }),
      fetch,
    })
    // Operations looks across the pipeline through the findings of the three owning modules.
    const operations = createOperationsModule({
      db,
      orders: order.orders,
      production,
      outbox: email.outbox,
      magicCoins: generator,
      settings: settings.operations,
      frontendBaseUrl,
    })
    const legal = createLegalModule({ fetch })
    const checkout = createCheckoutModule({
      db,
      checkoutCarts: cart.checkoutCarts,
      promotionCodes: promotion.promotionCodes,
      orders: order.orders,
      countries: country,
      refreshSpodStock: production.refreshSpodStock,
      guestTokens,
    })

    for (const routes of [
      country.routes,
      pricing.routes,
      supplier.routes,
      image.routes,
      promotion.routes,
      article.routes,
      prompt.routes,
      account.routes,
      generator.routes,
      order.routes,
      order.adminPdfRoutes(production.productionPdfGenerator),
      production.routes,
      operations.routes,
      cart.routes,
      image.guestImageRoute(cart.guestImageResolver),
      image.orphanImageRoutes({
        publicImages: [article.imageReferences, prompt.imageReferences],
        printImages: cart.printImageRegistry,
      }),
      checkout.routes,
      legal.routes,
    ]) {
      app.route('/', routes)
    }
    // Last: the MCP tools read the admin routes mounted above from `app` itself.
    app.route('/', createMcpModule({ app, frontendBaseUrl }).routes)
    // Operational probes and legacy WordPress 301 redirects sit ahead of the SPA fallback.
    app.route('/', healthRoutes(databaseReadyCheck(db)))
    app.route('/', legacyRedirectRoutes())
    // The frontend itself, when this deployment carries one: the full-stack image sets
    // FRONTEND_DIST_PATH; in development it stays unset and Vite serves the frontend.
    if (settings.frontend.distPath !== null) serveFrontend(app, settings.frontend.distPath)

    return {
      app,
      start: () => {
        email.worker.start()
        production.worker.start()
        operations.worker.start()
      },
      stop: async () => {
        await Promise.all([
          email.worker.stop(),
          production.worker.stop(),
          operations.worker.stop(),
          article.spodCatalogSync.stop(),
        ])
        await db.destroy()
      },
    }
  } catch (error) {
    await db.destroy()
    throw error
  }
}

/** Vite content-hashes these on every build, so a browser may cache them forever. */
const CACHED_FOR_A_YEAR = new Set(['.js', '.css', '.woff2'])

/**
 * Serves the built frontend from `distPath`: a request that matches a file answers with the file,
 * everything else outside `/api` answers `index.html`, the SPA fallback that lets the frontend
 * router read the URL. The cache headers mirror the split Vite produces: `index.html` and the
 * service worker `sw.js` are revalidated on every load, content-hashed assets are cached for a year.
 * A dist directory without an `index.html` is a broken full-stack build and refuses to start.
 */
function serveFrontend(app: Hono, distPath: string): void {
  const root = resolve(distPath)
  if (statSync(join(root, 'index.html'), { throwIfNoEntry: false })?.isFile() !== true) {
    throw new Error(`FRONTEND_DIST_PATH points to ${root}, but there is no index.html in it`)
  }
  const onFound = (path: string, c: { header: (name: string, value: string) => void }) => {
    const extension = extname(path)
    if (extension === '.html' || basename(path) === 'sw.js') {
      c.header('Cache-Control', 'no-cache, no-store')
    } else if (CACHED_FOR_A_YEAR.has(extension)) {
      c.header('Cache-Control', 'max-age=31536000, public, immutable')
    }
  }
  app.use('*', serveStatic({ root, onFound }))
  const fallback = serveStatic({ root, path: 'index.html', onFound })
  app.get('*', (c, next) => (c.req.path.startsWith('/api/') ? next() : fallback(c, next)))
}

/** Reads the peer address from Bun's server; falls back to `''` when invoked via `app.request`. */
function bunPeerAddress(c: Context): string {
  try {
    return getConnInfo(c).remote.address ?? ''
  } catch {
    return ''
  }
}
