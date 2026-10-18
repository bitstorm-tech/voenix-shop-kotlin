import { statSync } from 'node:fs'
import { basename, extname, join, resolve } from 'node:path'
import type { Hono } from 'hono'
import { getConnInfo, serveStatic } from '@hono/bun'
import { createAccountModule } from '@/modules/account'
import { createArticleModule } from '@/modules/article'
import { createCartModule, createPrintImageReads } from '@/modules/cart'
import { createCheckoutModule } from '@/modules/checkout'
import { createCountryModule } from '@/modules/country'
import { createEmailModule } from '@/modules/email'
import { createGeneratorModule } from '@/modules/generator'
import { createImageModule } from '@/modules/image'
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
import { createClientIpRateLimiter } from '@/platform/ratelimit/client-ip-rate-limiter'
import type { Settings } from '@/platform/settings'

/**
 * The composition root: the whole application from one `Settings` object. It migrates the database
 * first (the production module reads a table while it is created, and every module must see the
 * schema it was written for), opens the one pool, and creates every module after the modules it
 * consumes, in the order of the Kotlin `Application.kt` minus the late binding. `start()` starts the
 * three workers; `stop()` ends them and the pool.
 *
 * `overrides` is for the composed tests only, and a deployment never sets it: `mollie` replaces the
 * provider adapter with a stub, `fetch` is the one HTTP client the email delivery, the image
 * generator, and the print-on-demand client share.
 */
export async function createApplication(
  settings: Settings,
  overrides: {
    mollie?: MolliePayments
    fetch?: (url: string, init: RequestInit) => Promise<Response>
  } = {},
) {
  await migrate(settings.database)
  const db = createDatabase(settings.database)
  const fetch = overrides.fetch ?? globalThis.fetch
  try {
    const auth = createAuth(settings.auth)
    const app = createApp(auth)
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
    const account = createAccountModule({ db, auth, userEmails: email.userEmails, frontendBaseUrl })
    const generator = createGeneratorModule({
      db,
      settings: settings.generator,
      articleCatalog: article.articleCatalog,
      promptCatalog: prompt.promptCatalog,
      guestTokens,
      rateLimit: createClientIpRateLimiter({
        trustForwardedFor: settings.rateLimit.trustForwardedFor,
        peerAddress: (c) => getConnInfo(c).remote.address ?? '',
      }).middleware,
      fetch,
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
      spod,
      accounts: account.supplierAccounts,
      orders: order.orders,
      alertEmail: settings.operations.alertEmail,
    })
    // Operations looks across the pipeline through the findings of the three owning modules.
    const operations = createOperationsModule({
      db,
      orders: order.orders,
      production,
      outbox: email.outbox,
      settings: settings.operations,
      frontendBaseUrl,
    })
    // The cart owns the print images the guest image route serves, so that route is installed
    // once both sides exist. The checkout is the last consumer and exports nothing.
    const cart = createCartModule({
      db,
      articleCatalog: article.articleCatalog,
      promptCatalog: prompt.promptCatalog,
      promotionCodes: promotion.promotionCodes,
      orders: order.orders,
      images: image,
      guestTokens,
    })
    const checkout = createCheckoutModule({
      db,
      checkoutCarts: cart.checkoutCarts,
      promotionCodes: promotion.promotionCodes,
      orders: order.orders,
      countries: country,
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
      checkout.routes,
    ]) {
      app.route('/', routes)
    }
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
        await Promise.all([email.worker.stop(), production.worker.stop(), operations.worker.stop()])
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
